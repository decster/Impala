// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.planner;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.authorization.AuthorizationConfig;
import com.cloudera.impala.catalog.CatalogException;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.ImpalaException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.common.RuntimeEnv;
import com.cloudera.impala.service.Frontend;
import com.cloudera.impala.testutil.ImpaladTestCatalog;
import com.cloudera.impala.testutil.TestFileParser;
import com.cloudera.impala.testutil.TestFileParser.Section;
import com.cloudera.impala.testutil.TestFileParser.TestCase;
import com.cloudera.impala.testutil.TestUtils;
import com.cloudera.impala.thrift.ImpalaInternalServiceConstants;
import com.cloudera.impala.thrift.TDescriptorTable;
import com.cloudera.impala.thrift.TExecRequest;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.THBaseKeyRange;
import com.cloudera.impala.thrift.THdfsFileSplit;
import com.cloudera.impala.thrift.THdfsPartition;
import com.cloudera.impala.thrift.THdfsScanNode;
import com.cloudera.impala.thrift.THdfsTable;
import com.cloudera.impala.thrift.TNetworkAddress;
import com.cloudera.impala.thrift.TPlanFragment;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TQueryCtx;
import com.cloudera.impala.thrift.TQueryExecRequest;
import com.cloudera.impala.thrift.TScanRangeLocations;
import com.cloudera.impala.thrift.TTableDescriptor;
import com.cloudera.impala.thrift.TTupleDescriptor;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class PlannerTest {
  private final static Logger LOG = LoggerFactory.getLogger(PlannerTest.class);
  private final static boolean GENERATE_OUTPUT_FILE = true;
  private static Frontend frontend_ = new Frontend(
      AuthorizationConfig.createAuthDisabledConfig(), new ImpaladTestCatalog());
  private final String testDir_ = "functional-planner/queries/PlannerTest";
  private final String outDir_ = "/tmp/PlannerTest/";

  // Map from plan ID (TPlanNodeId) to the plan node with that ID.
  private final Map<Integer, TPlanNode> planMap_ = Maps.newHashMap();
  // Map from tuple ID (TTupleId) to the tuple descriptor with that ID.
  private final Map<Integer, TTupleDescriptor> tupleMap_ = Maps.newHashMap();
  // Map from table ID (TTableId) to the table descriptor with that ID.
  private final Map<Integer, TTableDescriptor> tableMap_ = Maps.newHashMap();

  @BeforeClass
  public static void setUp() throws Exception {
    // Use 8 cores for resource estimation.
    RuntimeEnv.INSTANCE.setNumCores(8);
    // Set test env to control the explain level.
    RuntimeEnv.INSTANCE.setTestEnv(true);
  }

  @AfterClass
  public static void cleanUp() {
    RuntimeEnv.INSTANCE.reset();
  }

  /**
   * Clears the old maps and constructs new maps based on the new
   * execRequest so that findPartitions() can locate various thrift
   * metadata structures quickly.
   */
  private void buildMaps(TQueryExecRequest execRequest) {
    // Build maps that will be used by findPartition().
    planMap_.clear();
    tupleMap_.clear();
    tableMap_.clear();
    for (TPlanFragment frag: execRequest.fragments) {
      for (TPlanNode node: frag.plan.nodes) {
        planMap_.put(node.node_id, node);
      }
    }
    if (execRequest.isSetDesc_tbl()) {
      TDescriptorTable descTbl = execRequest.desc_tbl;
      for (TTupleDescriptor tupleDesc: descTbl.tupleDescriptors) {
        tupleMap_.put(tupleDesc.id, tupleDesc);
      }
      if (descTbl.isSetTableDescriptors()) {
        for (TTableDescriptor tableDesc: descTbl.tableDescriptors) {
          tableMap_.put(tableDesc.id, tableDesc);
        }
      }
    }
  }

  /**
   * Look up the partition corresponding to the plan node (identified by
   * nodeId) and a file split.
   */
  private THdfsPartition findPartition(int nodeId, THdfsFileSplit split) {
    TPlanNode node = planMap_.get(nodeId);
    Preconditions.checkNotNull(node);
    Preconditions.checkState(node.node_id == nodeId && node.isSetHdfs_scan_node());
    THdfsScanNode scanNode = node.getHdfs_scan_node();
    int tupleId = scanNode.getTuple_id();
    TTupleDescriptor tupleDesc = tupleMap_.get(tupleId);
    Preconditions.checkNotNull(tupleDesc);
    Preconditions.checkState(tupleDesc.id == tupleId);
    TTableDescriptor tableDesc = tableMap_.get(tupleDesc.tableId);
    Preconditions.checkNotNull(tableDesc);
    Preconditions.checkState(tableDesc.id == tupleDesc.tableId &&
        tableDesc.isSetHdfsTable());
    THdfsTable hdfsTable = tableDesc.getHdfsTable();
    THdfsPartition partition = hdfsTable.getPartitions().get(split.partition_id);
    Preconditions.checkNotNull(partition);
    Preconditions.checkState(partition.id == split.partition_id);
    return partition;
  }

  /**
   * Verify that all THdfsPartitions included in the descriptor table are referenced by
   * at least one scan range or part of an inserted table.  PrintScanRangeLocations
   * will implicitly verify the converse (it'll fail if a scan range references a
   * table/partition descriptor that is not present).
   */
  private void testHdfsPartitionsReferenced(TQueryExecRequest execRequest,
      String query, StringBuilder errorLog) {
    long insertTableId = -1;
    // Collect all partitions that are referenced by a scan range.
    Set<THdfsPartition> scanRangePartitions = Sets.newHashSet();
    if (execRequest.per_node_scan_ranges != null) {
      for (Map.Entry<Integer, List<TScanRangeLocations>> entry:
           execRequest.per_node_scan_ranges.entrySet()) {
        if (entry.getValue() == null) {
          continue;
        }
        for (TScanRangeLocations locations: entry.getValue()) {
          if (locations.scan_range.isSetHdfs_file_split()) {
            THdfsFileSplit split = locations.scan_range.getHdfs_file_split();
            THdfsPartition partition = findPartition(entry.getKey(), split);
            scanRangePartitions.add(partition);
          }
        }
      }
    }
    if (execRequest.isSetFinalize_params()) {
      insertTableId = execRequest.getFinalize_params().getTable_id();
    }
    boolean first = true;
    // Iterate through all partitions of the descriptor table and verify all partitions
    // are referenced.
    if (execRequest.isSetDesc_tbl() && execRequest.desc_tbl.isSetTableDescriptors()) {
      for (TTableDescriptor tableDesc: execRequest.desc_tbl.tableDescriptors) {
        // All partitions of insertTableId are okay.
        if (tableDesc.getId() == insertTableId) continue;
        if (!tableDesc.isSetHdfsTable()) continue;
        THdfsTable hdfsTable = tableDesc.getHdfsTable();
        for (Map.Entry<Long, THdfsPartition> e :
             hdfsTable.getPartitions().entrySet()) {
          THdfsPartition partition = e.getValue();
          if (!scanRangePartitions.contains(partition)) {
            if (first) errorLog.append("query:\n" + query + "\n");
            errorLog.append(
                " unreferenced partition: HdfsTable: " + tableDesc.getId() +
                " HdfsPartition: " + partition.getId() + "\n");
            first = false;
          }
        }
      }
    }
  }

  /**
   * Construct a string representation of the scan ranges for this request.
   */
  private StringBuilder PrintScanRangeLocations(TQueryExecRequest execRequest) {
    StringBuilder result = new StringBuilder();
    if (execRequest.per_node_scan_ranges == null) {
      return result;
    }
    for (Map.Entry<Integer, List<TScanRangeLocations>> entry:
        execRequest.per_node_scan_ranges.entrySet()) {
      result.append("NODE " + entry.getKey().toString() + ":\n");
      if (entry.getValue() == null) {
        continue;
      }

      for (TScanRangeLocations locations: entry.getValue()) {
        // print scan range
        result.append("  ");
        if (locations.scan_range.isSetHdfs_file_split()) {
          THdfsFileSplit split = locations.scan_range.getHdfs_file_split();
          THdfsPartition partition = findPartition(entry.getKey(), split);
          Path filePath = new Path(partition.getLocation(), split.file_name);
          result.append("HDFS SPLIT " + filePath.toString() + " "
              + Long.toString(split.offset) + ":" + Long.toString(split.length));
        }
        if (locations.scan_range.isSetHbase_key_range()) {
          THBaseKeyRange keyRange = locations.scan_range.getHbase_key_range();
          Integer hostIdx = locations.locations.get(0).host_idx;
          TNetworkAddress networkAddress = execRequest.getHost_list().get(hostIdx);
          result.append("HBASE KEYRANGE ");
          result.append("port=" + networkAddress.port + " ");
          if (keyRange.isSetStartKey()) {
            result.append(HBaseScanNode.printKey(keyRange.getStartKey().getBytes()));
          } else {
            result.append("<unbounded>");
          }
          result.append(":");
          if (keyRange.isSetStopKey()) {
            result.append(HBaseScanNode.printKey(keyRange.getStopKey().getBytes()));
          } else {
            result.append("<unbounded>");
          }
        }
        result.append("\n");
      }
    }
    return result;
  }

  /**
   * Extracts and returns the expected error message from expectedPlan.
   * Returns null if expectedPlan is empty or its first element is not an error message.
   * The accepted format for error messages is 'not implemented: expected error message'
   * Returns the empty string if expectedPlan starts with 'not implemented' but no
   * expected error message was given.
   */
  private String getExpectedErrorMessage(ArrayList<String> expectedPlan) {
    if (expectedPlan.isEmpty()) return null;
    if (!expectedPlan.get(0).toLowerCase().startsWith("not implemented")) return null;
    // Find first ':' and extract string on right hand side as error message.
    int ix = expectedPlan.get(0).indexOf(":");
    if (ix + 1 > 0) {
      return expectedPlan.get(0).substring(ix + 1).trim();
    } else {
      return "";
    }
  }

  private void handleNotImplException(String query, String expectedErrorMsg,
      StringBuilder errorLog, StringBuilder actualOutput, Throwable e) {
    boolean isImplemented = expectedErrorMsg == null;
    actualOutput.append("not implemented: " + e.getMessage() + "\n");
    if (isImplemented) {
      errorLog.append("query:\n" + query + "\nPLAN not implemented: "
          + e.getMessage() + "\n");
    } else {
      // Compare actual and expected error messages.
      if (expectedErrorMsg != null && !expectedErrorMsg.isEmpty()) {
        if (!e.getMessage().toLowerCase().equals(expectedErrorMsg.toLowerCase())) {
          errorLog.append("query:\n" + query + "\nExpected error message: '"
              + expectedErrorMsg + "'\nActual error message: '"
              + e.getMessage() + "'\n");
        }
      }
    }
  }

  /**
   * Produces single-node and distributed plans for testCase and compares
   * plan and scan range results.
   * Appends the actual single-node and distributed plan as well as the printed
   * scan ranges to actualOutput, along with the requisite section header.
   * locations to actualScanRangeLocations; compares both to the appropriate sections
   * of 'testCase'.
   */
  private void RunTestCase(TestCase testCase, StringBuilder errorLog,
      StringBuilder actualOutput, String dbName)
      throws CatalogException {
    String query = testCase.getQuery();
    LOG.info("running query " + query);
    TQueryCtx queryCtx = TestUtils.createQueryContext(
        dbName, System.getProperty("user.name"));
    queryCtx.request.query_options.setExplain_level(TExplainLevel.STANDARD);
    queryCtx.request.query_options.allow_unsupported_formats = true;
    // single-node plan and scan range locations
    testSingleNodePlan(testCase, queryCtx, errorLog, actualOutput);
    // distributed plan
    testDistributedPlan(testCase, queryCtx, errorLog, actualOutput);
  }

  /**
   * Produces single-node plan for testCase and compares actual plan with expected plan,
   * as well as the scan range locations.
   * If testCase contains no expected single-node plan then this function is a no-op.
   */
  private void testSingleNodePlan(TestCase testCase, TQueryCtx queryCtx,
      StringBuilder errorLog, StringBuilder actualOutput) throws CatalogException {
    ArrayList<String> expectedPlan = testCase.getSectionContents(Section.PLAN);
    // Test case has no expected single-node plan. Do not test it.
    if (expectedPlan == null || expectedPlan.isEmpty()) return;
    String query = testCase.getQuery();
    String expectedErrorMsg = getExpectedErrorMessage(expectedPlan);
    queryCtx.request.getQuery_options().setNum_nodes(1);
    queryCtx.request.setStmt(query);
    boolean isImplemented = expectedErrorMsg == null;
    StringBuilder explainBuilder = new StringBuilder();

    TExecRequest execRequest = null;
    String locationsStr = null;
    actualOutput.append(Section.PLAN.getHeader() + "\n");
    try {
      execRequest = frontend_.createExecRequest(queryCtx, explainBuilder);
      buildMaps(execRequest.query_exec_request);
      String explainStr = removeExplainHeader(explainBuilder.toString());
      actualOutput.append(explainStr);
      if (!isImplemented) {
        errorLog.append(
            "query produced PLAN\nquery=" + query + "\nplan=\n" + explainStr);
      } else {
        LOG.info("single-node plan: " + explainStr);
        String result = TestUtils.compareOutput(
            Lists.newArrayList(explainStr.split("\n")), expectedPlan, true);
        if (!result.isEmpty()) {
          errorLog.append("section " + Section.PLAN.toString() + " of query:\n" + query
              + "\n" + result);
        }
        // Query exec request may not be set for DDL, e.g., CTAS.
        if (execRequest.isSetQuery_exec_request()) {
          testHdfsPartitionsReferenced(execRequest.query_exec_request, query, errorLog);
          locationsStr =
              PrintScanRangeLocations(execRequest.query_exec_request).toString();
        }
      }
    } catch (ImpalaException e) {
      if (e instanceof AnalysisException) {
        errorLog.append(
            "query:\n" + query + "\nanalysis error: " + e.getMessage() + "\n");
        return;
      } else if (e instanceof InternalException) {
        errorLog.append(
            "query:\n" + query + "\ninternal error: " + e.getMessage() + "\n");
        return;
      } if (e instanceof NotImplementedException) {
        handleNotImplException(query, expectedErrorMsg, errorLog, actualOutput, e);
      } else if (e instanceof CatalogException) {
        // TODO: do we need to rethrow?
        throw (CatalogException) e;
      } else {
        errorLog.append(
            "query:\n" + query + "\nunhandled exception: " + e.getMessage() + "\n");
      }
    }

    // compare scan range locations
    LOG.info("scan range locations: " + locationsStr);
    ArrayList<String> expectedLocations =
        testCase.getSectionContents(Section.SCANRANGELOCATIONS);

    if (expectedLocations.size() > 0 && locationsStr != null) {
      // Locations' order does not matter.
      String result = TestUtils.compareOutput(
          Lists.newArrayList(locationsStr.split("\n")), expectedLocations, false);
      if (!result.isEmpty()) {
        errorLog.append("section " + Section.SCANRANGELOCATIONS + " of query:\n"
            + query + "\n" + result);
      }
      actualOutput.append(Section.SCANRANGELOCATIONS.getHeader() + "\n");
      // Print the locations out sorted since the order is random and messed up
      // the diffs. The values in locationStr contains "Node X" labels as well
      // as paths.
      ArrayList<String> locations = Lists.newArrayList(locationsStr.split("\n"));
      ArrayList<String> perNodeLocations = Lists.newArrayList();

      for (int i = 0; i < locations.size(); ++i) {
        if (locations.get(i).startsWith("NODE")) {
          if (!perNodeLocations.isEmpty()) {
            Collections.sort(perNodeLocations);
            actualOutput.append(Joiner.on("\n").join(perNodeLocations)).append("\n");
            perNodeLocations.clear();
          }
          actualOutput.append(locations.get(i)).append("\n");
        } else {
          perNodeLocations.add(locations.get(i));
        }
      }

      if (!perNodeLocations.isEmpty()) {
        Collections.sort(perNodeLocations);
        actualOutput.append(Joiner.on("\n").join(perNodeLocations)).append("\n");
      }

      // TODO: check that scan range locations are identical in both cases
    }
  }


  /**
  * Produces distributed plan for testCase and compares actual plan with expected plan.
  * If testCase contains no expected distributed plan then this function is a no-op.
  */
 private void testDistributedPlan(TestCase testCase, TQueryCtx queryCtx,
     StringBuilder errorLog, StringBuilder actualOutput) throws CatalogException {
   ArrayList<String> expectedPlan =
       testCase.getSectionContents(Section.DISTRIBUTEDPLAN);
   // Test case has no expected distributed plan. Do not test it.
   if (expectedPlan == null || expectedPlan.isEmpty()) return;
   String query = testCase.getQuery();
   String expectedErrorMsg = getExpectedErrorMessage(expectedPlan);
   queryCtx.request.getQuery_options().setNum_nodes(
       ImpalaInternalServiceConstants.NUM_NODES_ALL);
   queryCtx.request.setStmt(query);
   boolean isImplemented = expectedErrorMsg == null;
   StringBuilder explainBuilder = new StringBuilder();
   actualOutput.append(Section.DISTRIBUTEDPLAN.getHeader() + "\n");
   TExecRequest execRequest = null;
   try {
     // distributed plan
     execRequest = frontend_.createExecRequest(queryCtx, explainBuilder);
     String explainStr = removeExplainHeader(explainBuilder.toString());
     actualOutput.append(explainStr);
     if (!isImplemented) {
       errorLog.append(
           "query produced DISTRIBUTEDPLAN\nquery=" + query + "\nplan=\n"
           + explainStr);
     } else {
       LOG.info("distributed plan: " + explainStr);
       String result = TestUtils.compareOutput(
           Lists.newArrayList(explainStr.split("\n")), expectedPlan, true);
       if (!result.isEmpty()) {
         errorLog.append("section " + Section.DISTRIBUTEDPLAN.toString()
             + " of query:\n" + query + "\n" + result);
       }
     }
   } catch (ImpalaException e) {
     if (e instanceof AnalysisException) {
       errorLog.append(
           "query:\n" + query + "\nanalysis error: " + e.getMessage() + "\n");
       return;
     } else if (e instanceof InternalException) {
       errorLog.append(
           "query:\n" + query + "\ninternal error: " + e.getMessage() + "\n");
       return;
     } if (e instanceof NotImplementedException) {
       handleNotImplException(query, expectedErrorMsg, errorLog, actualOutput, e);
     } else if (e instanceof CatalogException) {
       throw (CatalogException) e;
     } else {
       errorLog.append(
           "query:\n" + query + "\nunhandled exception: " + e.getMessage() + "\n");
     }
   } catch (IllegalStateException ie) {
       errorLog.append(
           "query:\n" + query + "\nunhandled exception: " + ie.getMessage() + "\n");
   }
  }

  /**
   * Strips out the header containing resource estimates and the warning about missing
   * stats from the given explain plan, because the estimates can change easily with
   * stats/cardinality.
   */
  private String removeExplainHeader(String explain) {
    String[] lines = explain.split("\n");
    // Find the first empty line - the end of the header.
    for (int i = 0; i < lines.length - 1; ++i) {
      if (lines[i].isEmpty()) {
        return Joiner.on("\n").join(Arrays.copyOfRange(lines, i + 1 , lines.length))
            + "\n";
      }
    }
    return explain;
  }

  private void runPlannerTestFile(String testFile, String dbName) {
    String fileName = testDir_ + "/" + testFile + ".test";
    TestFileParser queryFileParser = new TestFileParser(fileName);
    StringBuilder actualOutput = new StringBuilder();

    queryFileParser.parseFile();
    StringBuilder errorLog = new StringBuilder();
    for (TestCase testCase : queryFileParser.getTestCases()) {
      actualOutput.append(testCase.getSectionAsString(Section.QUERY, true, "\n"));
      actualOutput.append("\n");
      try {
        RunTestCase(testCase, errorLog, actualOutput, dbName);
      } catch (CatalogException e) {
        errorLog.append(String.format("Failed to plan query\n%s\n%s",
            testCase.getQuery(), e.getMessage()));
      }
      actualOutput.append("====\n");
    }

    // Create the actual output file
    if (GENERATE_OUTPUT_FILE) {
      try {
        File outDirFile = new File(outDir_);
        outDirFile.mkdirs();
        FileWriter fw = new FileWriter(outDir_ + testFile + ".test");
        fw.write(actualOutput.toString());
        fw.close();
      } catch (IOException e) {
        errorLog.append("Unable to create output file: " + e.getMessage());
      }
    }

    if (errorLog.length() != 0) {
      fail(errorLog.toString());
    }
  }

  private void runPlannerTestFile(String testFile) {
    runPlannerTestFile(testFile, "default");
  }

  @Test
  public void testPredicatePropagation() {
    runPlannerTestFile("predicate-propagation");
  }

  @Test
  public void testConstant() {
    runPlannerTestFile("constant");
  }

  @Test
  public void testEmpty() {
    runPlannerTestFile("empty");
  }

  @Test
  public void testDistinct() {
    runPlannerTestFile("distinct");
  }

  @Test
  public void testAggregation() {
    runPlannerTestFile("aggregation");
  }

  @Test
  public void testAnalyticFns() {
    runPlannerTestFile("analytic-fns");
  }

  @Test
  public void testHbase() {
    runPlannerTestFile("hbase");
  }

  @Test
  public void testInsert() {
    runPlannerTestFile("insert");
  }

  @Test
  public void testHdfs() {
    runPlannerTestFile("hdfs");
  }

  @Test
  public void testJoins() {
    runPlannerTestFile("joins");
  }

  @Test
  public void testJoinOrder() {
    runPlannerTestFile("join-order");
  }

  @Test
  public void testOuterJoins() {
    runPlannerTestFile("outer-joins");
  }

  @Test
  public void testOrder() {
    runPlannerTestFile("order");
  }

  @Test
  public void testTopN() {
    runPlannerTestFile("topn");
  }

  @Test
  public void testInlineView() {
    runPlannerTestFile("inline-view");
  }

  @Test
  public void testInlineViewLimit() {
    runPlannerTestFile("inline-view-limit");
  }

  @Test
  public void testSubqueryRewrite() {
    runPlannerTestFile("subquery-rewrite");
  }

  @Test
  public void testUnion() {
    runPlannerTestFile("union");
  }

  @Test
  public void testValues() {
    runPlannerTestFile("values");
  }

  @Test
  public void testViews() {
    runPlannerTestFile("views");
  }

  @Test
  public void testWithClause() {
    runPlannerTestFile("with-clause");
  }

  @Test
  public void testDistinctEstimate() {
    runPlannerTestFile("distinct-estimate");
  }

  @Test
  public void testDataSourceTables() {
    runPlannerTestFile("data-source-tables");
  }

  @Test
  public void testDdl() {
    runPlannerTestFile("ddl");
  }

  @Test
  public void testTpch() {
    runPlannerTestFile("tpch-all");
  }

  @Test
  public void testTpcds() {
    // Join order has been optimized for Impala. Uses ss_date as partition key.
    runPlannerTestFile("tpcds-all", "tpcds");
  }
}
