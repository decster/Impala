#!/usr/bin/env python
# Copyright 2012 Cloudera Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import csv
import sys
from cStringIO import StringIO


class PrettyOutputFormatter(object):
  def __init__(self, prettytable):
    self.prettytable = prettytable

  def format(self, rows):
    # Clear rows that already exist in the table.
    self.prettytable.clear_rows()
    try:
      map(self.prettytable.add_row, rows)
      return self.prettytable.get_string()
    except Exception, e:
      # beeswax returns each row as a tab separated string. If a string column
      # value in a row has tabs, it will break the row split. Default to displaying
      # raw results. This will change with a move to hiveserver2.
      # Reference:  https://issues.cloudera.org/browse/IMPALA-116
      error_msg = ("Prettytable cannot resolve string columns values that have "
                   " embedded tabs. Reverting to tab delimited text output")
      print >>sys.stderr, error_msg
      return '\n'.join(['\t'.join(row) for row in rows])


class DelimitedOutputFormatter(object):
  def __init__(self, field_delim="\t"):
    if field_delim:
      self.field_delim = field_delim.decode('string-escape')
      if len(self.field_delim) != 1:
        error_msg = ("Illegal delimiter %s, the delimiter "
                     "must be a 1-character string." % self.field_delim)
        raise ValueError, error_msg

  def format(self, rows):
    # csv.writer expects a file handle to the input.
    # cStringIO is used as the temporary buffer.
    temp_buffer = StringIO()
    writer = csv.writer(temp_buffer, delimiter=self.field_delim,
                        lineterminator='\n', quoting=csv.QUOTE_MINIMAL)
    writer.writerows(rows)
    rows = temp_buffer.getvalue().decode('utf-8').rstrip('\n')
    temp_buffer.close()
    return rows


class OutputStream(object):
  def __init__(self, formatter, filename=None):
    """Helper class for writing query output.

    User should invoke the `write(data)` method of this object.
    `data` is a list of lists.
    """
    self.formatter = formatter
    self.handle = sys.stdout
    self.filename = filename
    if self.filename:
      try:
        self.handle = open(self.filename, 'ab')
      except IOError, err:
        print >>sys.stderr, "Error opening file %s: %s" % (self.filename, str(err))
        print >>sys.stderr, "Writing to stdout"

  def write(self, data):
    print >>self.handle, self.formatter.format(data).encode('utf-8')
    self.handle.flush()

  def __del__(self):
    # If the output file cannot be opened, OutputStream defaults to sys.stdout.
    # Don't close the file handle if it points to sys.stdout.
    if self.filename and self.handle != sys.stdout:
      self.handle.close()
