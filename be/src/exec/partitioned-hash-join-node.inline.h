// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef IMPALA_EXEC_PARTITIONED_HASH_JOIN_NODE_INLINE_H
#define IMPALA_EXEC_PARTITIONED_HASH_JOIN_NODE_INLINE_H

#include "exec/partitioned-hash-join-node.h"

#include "runtime/buffered-tuple-stream.inline.h"

namespace impala {

inline void PartitionedHashJoinNode::ResetForProbe() {
  current_probe_row_ = NULL;
  probe_batch_pos_ = 0;
  matched_probe_ = true;
  hash_tbl_iterator_.reset();
}

inline bool PartitionedHashJoinNode::AppendRow(BufferedTupleStream* stream,
    TupleRow* row) {
  if (LIKELY(stream->AddRow(row))) return true;
  status_ = stream->status();
  if (!status_.ok()) return false;
  // We ran out of memory. Pick a partition to spill.
  status_ = SpillPartition();
  if (!status_.ok()) return false;
  if (!stream->AddRow(row)) {
    // Can this happen? we just spilled a partition so this shouldn't fail.
    status_ = Status("Could not spill row.");
    return false;
  }
  return true;
}

}

#endif
