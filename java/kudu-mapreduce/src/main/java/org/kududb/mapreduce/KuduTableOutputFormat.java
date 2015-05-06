// Copyright (c) 2014, Cloudera, inc.
// Confidential Cloudera Information: Covered by NDA.
package org.kududb.mapreduce;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.DeferredGroupException;
import org.kududb.client.*;
import org.kududb.client.AsyncKuduClient;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * Use {@link
 * KuduTableMapReduceUtil#initTableOutputFormat(org.apache.hadoop.mapreduce.Job, String, boolean)}
 * to correctly setup this output format, then {@link
 * KuduTableMapReduceUtil#getTableFromContext(org.apache.hadoop.mapreduce.TaskInputOutputContext)}
 * to get a KuduTable.
 * </p>
 *
 * <p>
 * Hadoop doesn't have the concept of "closing" the output format so in order to release the
 * resources we assume that once either
 * {@link #checkOutputSpecs(org.apache.hadoop.mapreduce.JobContext)}
 * or {@link TableRecordWriter#close(org.apache.hadoop.mapreduce.TaskAttemptContext)}
 * have been called that the object won't be used again and the AsyncKuduClient is shut down.
 * </p>
 */
public class KuduTableOutputFormat extends OutputFormat<NullWritable,Operation>
    implements Configurable {

  private static final Logger LOG = LoggerFactory.getLogger(KuduTableOutputFormat.class);

  /** Job parameter that specifies the output table. */
  static final String OUTPUT_TABLE_KEY = "kudu.mapreduce.output.table";

  /** Job parameter that specifies where the masters are */
  static final String MASTER_QUORUM_KEY = "kudu.mapreduce.master.quorum";

  /** Job parameter that specifies how long we wait for operations to complete */
  static final String OPERATION_TIMEOUT_MS_KEY = "kudu.mapreduce.operation.timeout.ms";

  /** Number of rows that are buffered before flushing to the tablet server */
  static final String BUFFER_ROW_COUNT_KEY = "kudu.mapreduce.buffer.row.count";

  /**
   * Job parameter that specifies which key is to be used to reach the KuduTableOutputFormat
   * belonging to the caller
   */
  static final String MULTITON_KEY = "kudu.mapreduce.multitonkey";

  /**
   * This multiton is used so that the tasks using this output format/record writer can find
   * their KuduTable without having a direct dependency on this class,
   * with the additional complexity that the output format cannot be shared between threads.
   */
  private static final ConcurrentHashMap<String, KuduTableOutputFormat> MULTITON = new
      ConcurrentHashMap<String, KuduTableOutputFormat>();

  /**
   * This counter helps indicate which task log to look at since rows that weren't applied will
   * increment this counter.
   */
  public static enum Counters { ROWS_WITH_ERRORS }

  private Configuration conf = null;

  private AsyncKuduClient client;
  private KuduTable table;
  private AsyncKuduSession session;
  private long operationTimeoutMs;

  @Override
  public void setConf(Configuration entries) {
    this.conf = new Configuration(entries);

    String masterAddress = this.conf.get(MASTER_QUORUM_KEY);
    String tableName = this.conf.get(OUTPUT_TABLE_KEY);
    this.operationTimeoutMs = this.conf.getLong(OPERATION_TIMEOUT_MS_KEY, 10000);
    int bufferSpace = this.conf.getInt(BUFFER_ROW_COUNT_KEY, 1000);

    this.client = KuduTableMapReduceUtil.getAsyncClient(masterAddress);
    Deferred<KuduTable> d = client.openTable(tableName);
    try {
      this.table = d.join(this.operationTimeoutMs);
    } catch (Exception ex) {
      throw new RuntimeException("Could not obtain the table from the master, " +
          "is the master running and is this table created? tablename=" + tableName + " and " +
          "master address= " + masterAddress, ex);
    }
    this.session = client.newSession();
    this.session.setTimeoutMillis(this.operationTimeoutMs);
    this.session.setFlushMode(AsyncKuduSession.FlushMode.AUTO_FLUSH_BACKGROUND);
    this.session.setMutationBufferSpace(bufferSpace);
    String multitonKey = String.valueOf(Thread.currentThread().getId());
    assert(MULTITON.get(multitonKey) == null);
    MULTITON.put(multitonKey, this);
    entries.set(MULTITON_KEY, multitonKey);
  }

  private void shutdownClient() throws IOException {
    try {
      client.shutdown().join(operationTimeoutMs);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public static KuduTable getKuduTable(String multitonKey) {
    return MULTITON.get(multitonKey).getKuduTable();
  }

  private KuduTable getKuduTable() {
    return this.table;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public RecordWriter<NullWritable, Operation> getRecordWriter(TaskAttemptContext taskAttemptContext)
      throws IOException, InterruptedException {
    return new TableRecordWriter(this.session);
  }

  @Override
  public void checkOutputSpecs(JobContext jobContext) throws IOException, InterruptedException {
    shutdownClient();
  }

  @Override
  public OutputCommitter getOutputCommitter(TaskAttemptContext taskAttemptContext) throws
      IOException, InterruptedException {
    return new KuduTableOutputCommitter();
  }

  protected class TableRecordWriter extends RecordWriter<NullWritable, Operation> {

    private final AtomicLong rowsWithErrors = new AtomicLong();
    private final AsyncKuduSession session;

    public TableRecordWriter(AsyncKuduSession session) {
      this.session = session;
    }

    @Override
    public void write(NullWritable key, Operation operation)
        throws IOException, InterruptedException {
      // We'll loop until the Operation's attempts hits the default max.
      while (true) {
        try {
          Deferred<OperationResponse> d = session.apply(operation);
          d.addErrback(defaultErrorCB);
          break;
        } catch (PleaseThrottleException ex) {
          try {
            ex.getDeferred().join(operationTimeoutMs);
          } catch (Exception e) {
              // we don't care, it will get registered in defaultErrorCB,
              // we'll still try to apply our operation.
          }
        }
      }
    }

    @Override
    public void close(TaskAttemptContext taskAttemptContext) throws IOException,
        InterruptedException {
      try {
        Deferred<ArrayList<OperationResponse>> d = session.close();
        d.addErrback(defaultErrorCB);
        d.join(operationTimeoutMs);
        shutdownClient();
      } catch (Exception e) {
        // Same as for write, it will get registered in defaultErrorCB.
      } finally {
        if (taskAttemptContext != null) {
          // This is the only place where we have access to the context in the record writer,
          // so set the counter here.
          taskAttemptContext.getCounter(Counters.ROWS_WITH_ERRORS).setValue(rowsWithErrors.get());
        }
      }
    }

    private Callback<Object, Object> defaultErrorCB = new Callback<Object, Object>() {
      @Override
      public Object call(Object arg) throws Exception {
        rowsWithErrors.incrementAndGet();
        if (arg == null) {
          LOG.warn("The error callback was triggered after applying a row but a message wasn't " +
              "provided");
        } else if (arg instanceof Exception) {
          RowsWithErrorException rwe;
          if (arg instanceof DeferredGroupException) {
            rwe = RowsWithErrorException.fromDeferredGroupException((DeferredGroupException) arg);
          } else if (arg instanceof RowsWithErrorException) {
            rwe = (RowsWithErrorException) arg;
          } else {
            LOG.warn("Encountered an exception after applying rows", arg);
            return null;
          }
          if (rwe != null) {

            // Assuming we had a leader election, see KUDU-568.
            if (rwe.areAllErrorsOfAlreadyPresentType(false)) {
              return null;
            }
            int rowErrorsCount = rwe.getErrors().size();
            rowsWithErrors.addAndGet(rowErrorsCount - 1); // Here we know the real count.
            LOG.warn("Got per errors for " + rowErrorsCount + " rows, " +
                "the first one being " + rwe.getErrors().get(0).getStatus());
          } else {
            LOG.warn("Got a DeferredGroupException without per-row errors", arg);
          }
        } else {
          LOG.warn("Encountered an error after applying rows: " + arg);
        }
        return null;
      }
    };
  }
}