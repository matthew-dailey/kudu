// Copyright (c) 2013, Cloudera, inc.
// Confidential Cloudera Information: Covered by NDA.
package kudu.rpc;

import kudu.ColumnSchema;
import kudu.Schema;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import kudu.Type;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * This class can either start its own cluster or rely on an existing one.
 * By default it assumes that the master is at localhost:64000.
 * The cluster's configuration flags is found at flagsPath as defined in the pom file.
 * Set startCluster to true in order have the test start the cluster for you.
 * All those properties are set via surefire's systemPropertyVariables, meaning this:
 * $ mvn test -DstartCluster=false
 * will use an existing cluster at default address found above.
 *
 * The test creates a table with a unique(ish) name which it deletes at the end.
 */
public class TestKuduSession extends BaseKuduTest {
  // Generate a unique table name
  private static final String TABLE_NAME =
      TestKuduSession.class.getName()+"-"+System.currentTimeMillis();

  private static Schema schema = getBasicSchema();
  private static KuduTable table;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    BaseKuduTest.setUpBeforeClass();
    createTable(TABLE_NAME, schema, new CreateTableBuilder());

    table = openTable(TABLE_NAME);
  }

  @Test(timeout = 100000)
  public void test() throws Exception {

    KuduSession session = client.newSession();
    // disable the low watermark until we need it
    session.setMutationBufferLowWatermark(1f);

    // First testing KUDU-232, the cache is empty and we want to force flush. We force the flush
    // interval to be higher than the sleep time so that we don't background flush while waiting.
    // If our subsequent manual flush throws, it means the logic to block on in-flight tablet
    // lookups in flush isn't working properly.
    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_BACKGROUND);
    session.setFlushInterval(DEFAULT_SLEEP + 1000);
    Deferred<OperationResponse> d = session.apply(createInsert(0));
    session.flush().join(DEFAULT_SLEEP);
    assertTrue(exists(0));
    // set back to default
    session.setFlushInterval(1000);

    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    for (int i = 1; i < 10; i++) {
      session.apply(createInsert(i)).join(DEFAULT_SLEEP);
    }

    assertEquals(10, countInRange(0, 10));

    session.setFlushMode(KuduSession.FlushMode.MANUAL_FLUSH);
    session.setMutationBufferSpace(10);

    session.apply(createInsert(10));

    try {
      session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    } catch (IllegalArgumentException ex) {
      /* expected, flush mode remains manual */
    }

    assertFalse(exists(10));

    for (int i = 11; i < 20; i++) {
      session.apply(createInsert(i));
    }

    assertEquals(0, countInRange(10, 20));
    try {
      session.apply(createInsert(20));
    } catch (NonRecoverableException ex) {
      /* expected, buffer would be too big */
    }
    assertEquals(0, countInRange(10, 20)); // the buffer should still be full

    session.flush().join(DEFAULT_SLEEP);
    assertEquals(10, countInRange(10, 20)); // now everything should be there

    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_BACKGROUND);

    d = session.apply(createInsert(20));
    Thread.sleep(50); // waiting a minimal amount of time to make sure the interval is in effect
    assertFalse(exists(20));
    // Add 10 items, the last one will stay in the buffer
    for (int i = 21; i < 30; i++) {
      d = session.apply(createInsert(i));
    }
    Deferred<OperationResponse> buffered = session.apply(createInsert(30));
    long now = System.currentTimeMillis();
    d.join(DEFAULT_SLEEP); // Ok to use the last d, everything is going to the buffer
    // auto flush will force flush if the buffer is full as it should be now
    // so we check that we didn't wait the full interval
    long elapsed = System.currentTimeMillis() - now;
    assertTrue(elapsed < 950);
    assertEquals(10, countInRange(20, 31));
    buffered.join();
    assertEquals(11, countInRange(20, 31));

    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    Update update = createUpdate(30);
    update.addInt(schema.getColumn(2).getName(), 999);
    update.addString(schema.getColumn(3).getName(), "updated data");
    d = session.apply(update);
    d.addErrback(defaultErrorCB);
    d.join(DEFAULT_SLEEP);
    assertEquals(31, countInRange(0, 31));

    Delete del = createDelete(30);
    d = session.apply(del);
    d.addErrback(defaultErrorCB);
    d.join(DEFAULT_SLEEP);
    assertEquals(30, countInRange(0, 31));

    session.setFlushMode(KuduSession.FlushMode.MANUAL_FLUSH);
    session.setMutationBufferSpace(35);
    for (int i = 0; i < 20; i++) {
      buffered = session.apply(createDelete(i));
    }
    assertEquals(30, countInRange(0, 31));
    session.flush();
    buffered.join(DEFAULT_SLEEP);
    assertEquals(10, countInRange(0, 31));

    for (int i = 30; i < 40; i++) {
      session.apply(createInsert(i));
    }

    for (int i = 20; i < 30; i++) {
      buffered = session.apply(createDelete(i));
    }

    assertEquals(10, countInRange(0, 40));
    session.flush();
    buffered.join(DEFAULT_SLEEP);
    assertEquals(10, countInRange(0, 40));

    // Test nulls
    // add 10 rows with the nullable column set to null
    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_SYNC);
    for (int i = 40; i < 50; i++) {
      session.apply(createInsertWithNull(i)).join(DEFAULT_SLEEP);
    }

    // now scan those rows and make sure the column is null
    assertEquals(10, countNullColumns(40, 50));

    // Test sending edits too fast
    session.setFlushMode(KuduSession.FlushMode.AUTO_FLUSH_BACKGROUND);
    session.setMutationBufferSpace(10);

    // The buffer has a capacity of 10, we insert 21 rows, meaning we fill the first one,
    // force flush, fill a second one before the first one could come back,
    // and the 21st row will be sent back.
    boolean gotException = false;
    for (int i = 50; i < 71; i++) {
      try {
        session.apply(createInsert(i));
      } catch (PleaseThrottleException ex) {
        gotException = true;
        assertEquals(70, i);
        // Wait for the buffer to clear
        ex.getDeferred().join(DEFAULT_SLEEP);
        session.apply(ex.getFailedRpc());
        session.flush().join(DEFAULT_SLEEP);
      }
    }
    assertTrue(gotException);
    assertEquals(21, countInRange(50, 71));

    // Now test a more subtle issue, basically the race where we call flush from the client when
    // there's a batch already in flight. We need to finish joining only when all the data is
    // flushed.
    for (int i = 71; i < 91; i++) {
      session.apply(createInsert(i));
    }
    session.flush().join(DEFAULT_SLEEP);
    // If we only waited after the in flight batch, there would be 10 rows here.
    assertEquals(20, countInRange(71, 91));

    // Test empty scanner projection
    KuduScanner scanner = getScanner(71, 91, new Schema(new ArrayList<ColumnSchema>(0)));
    assertEquals(20, countRowsInScan(scanner));

    // Test removing the connection and then do a rapid set of inserts
    client.getTableClients().get(0).shutdown().join(DEFAULT_SLEEP);
    session.setMutationBufferSpace(1);
    for (int i = 91; i < 101; i++) {
      try {
        session.apply(createInsert(i));
      } catch (PleaseThrottleException ex) {
        // Wait for the buffer to clear
        ex.getDeferred().join(DEFAULT_SLEEP);
        session.apply(ex.getFailedRpc());
      }
    }
    session.flush().join(DEFAULT_SLEEP);
    assertEquals(10, countInRange(91, 101));

    // Test a tablet going missing or encountering a new tablet while inserting a lot
    // of data. This code used to fail in many different ways.
    client.emptyTabletsCacheForTable(TABLE_NAME);
    for (int i = 101; i < 151; i++) {
      Insert insert = createInsert(i);
      while (true) {
        try {
          session.apply(insert);
          break;
        } catch (PleaseThrottleException ex) {
          // Wait for the buffer to clear
          ex.getDeferred().join(DEFAULT_SLEEP);
        }
      }
    }
    session.flush().join(DEFAULT_SLEEP);
    assertEquals(50, countInRange(101, 151));

    // Test the low watermark.
    session.setMutationBufferSpace(10);
    session.setMutationBufferLowWatermark(0.1f);
    session.setRandomSeed(12345); // Will make us hit the exception after 6 tries
    gotException = false;
    for (int i = 151; i < 171; i++) {
      try {
        session.apply(createInsert(i));
      } catch (PleaseThrottleException ex) {
        // We're going to hit the exception after filling up the buffer a first time then trying
        // to insert 6 more rows.
        assertEquals(167, i);
        gotException = true;
        assertTrue(ex.getMessage().contains("watermark"));
        // Once we hit the exception we wait on the batch to finish flushing and then insert the
        // rest of the data.
        ex.getDeferred().join(DEFAULT_SLEEP);
        session.apply(ex.getFailedRpc());
      }
    }
    session.flush().join(DEFAULT_SLEEP);
    assertEquals(20, countInRange(151, 171));
    assertTrue(gotException);

    // Test Alter
    // Add a col
    AlterTableBuilder atb = new AlterTableBuilder();
    atb.addColumn("testaddint", Type.INT32, 4);
    submitAlterAndCheck(atb);

    // rename that col
    atb = new AlterTableBuilder();
    atb.renameColumn("testaddint", "newtestaddint");
    submitAlterAndCheck(atb);

    // delete it
    atb = new AlterTableBuilder();
    atb.dropColumn("newtestaddint");
    submitAlterAndCheck(atb);

    String newTableName = TABLE_NAME +"new";

    // rename our table
    atb = new AlterTableBuilder();
    atb.renameTable(newTableName);
    submitAlterAndCheck(atb, TABLE_NAME, newTableName);

    // rename it back
    atb = new AlterTableBuilder();
    atb.renameTable(TABLE_NAME);
    submitAlterAndCheck(atb, newTableName, TABLE_NAME);

    // try adding two columns, where one is nullable
    atb = new AlterTableBuilder();
    atb.addColumn("testaddmulticolnotnull", Type.INT32, 4);
    atb.addNullableColumn("testaddmulticolnull", Type.STRING);
    submitAlterAndCheck(atb);
  }

  /**
   * Helper method to submit an Alter and wait for it to happen, using the default table name
   */
  public static void submitAlterAndCheck(AlterTableBuilder atb) throws Exception {
    submitAlterAndCheck(atb, TABLE_NAME, TABLE_NAME);
  }

  public static void submitAlterAndCheck(AlterTableBuilder atb,
                                         String tableToAlter, String tableToCheck) throws
      Exception {
    // TODO: Right now we're no longer running this test by default, as the default is to
    // start 3 masters. Once multi-master alter table is implemented remove this.
    if (masterHostPorts.size() > 1) {
      LOG.info("Alter table is not yet supported with multiple masters. Specify " +
          "-DnumMasters=1 on the command line to start a single-master cluster to run this test.");
      return;
    }
    Deferred<AlterTableResponse> alterDeffered = client.alterTable(tableToAlter, atb);
    alterDeffered.join(DEFAULT_SLEEP);
    boolean done  = client.syncWaitOnAlterCompletion(tableToCheck, DEFAULT_SLEEP);
    assertTrue(done);
  }

  private Insert createInsert(int key) {
    return createBasicSchemaInsert(table, key);
  }

  private Insert createInsertWithNull(int key) {
    Insert insert = table.newInsert();
    insert.addInt(schema.getColumn(0).getName(), key);
    insert.addInt(schema.getColumn(1).getName(), 2);
    insert.addInt(schema.getColumn(2).getName(), 3);
    insert.setNull(schema.getColumn(3).getName());
    insert.addBoolean(schema.getColumn(4).getName(), false);
    return insert;
  }

  private Update createUpdate(int key) {
    Update update = table.newUpdate();
    update.addInt(schema.getColumn(0).getName(), key);
    return update;
  }

  private Delete createDelete(int key) {
    Delete delete = table.newDelete();
    delete.addInt(schema.getColumn(0).getName(), key);
    return delete;
  }

  public static boolean exists(final int key) throws Exception {

    KuduScanner scanner = getScanner(key, key);
    final AtomicBoolean exists = new AtomicBoolean(false);

    Callback<Object, KuduScanner.RowResultIterator> cb =
        new Callback<Object, KuduScanner.RowResultIterator>() {
      @Override
      public Object call(KuduScanner.RowResultIterator arg) throws Exception {
        if (arg == null) return null;
        for (RowResult row : arg) {
          if (row.getInt(0) == key) {
            exists.set(true);
            break;
          }
        }
        return null;
      }
    };

    while (scanner.hasMoreRows()) {
      Deferred<KuduScanner.RowResultIterator> data = scanner.nextRows();
      data.addCallbacks(cb, defaultErrorCB);
      data.join(DEFAULT_SLEEP);
      if (exists.get()) {
        break;
      }
    }

    Deferred<KuduScanner.RowResultIterator> closer = scanner.close();
    closer.join(DEFAULT_SLEEP);
    return exists.get();
  }

  public static int countNullColumns(final int startKey, final int endKey) throws Exception {

    KuduScanner scanner = getScanner(startKey, endKey);
    final AtomicInteger ai = new AtomicInteger();

    Callback<Object, KuduScanner.RowResultIterator> cb =
        new Callback<Object, KuduScanner.RowResultIterator>() {
          @Override
          public Object call(KuduScanner.RowResultIterator arg) throws Exception {
            if (arg == null) return null;
            for (RowResult row : arg) {
              if (row.isNull(3)) {
                ai.incrementAndGet();
              }
            }
            return null;
          }
        };

    while (scanner.hasMoreRows()) {
      Deferred<KuduScanner.RowResultIterator> data = scanner.nextRows();
      data.addCallbacks(cb, defaultErrorCB);
      data.join(DEFAULT_SLEEP);
    }

    Deferred<KuduScanner.RowResultIterator> closer = scanner.close();
    closer.join(DEFAULT_SLEEP);
    return ai.get();
  }

  public static int countInRange(final int startOrder, final int endOrder) throws Exception {

    KuduScanner scanner = getScanner(startOrder, endOrder);
    return countRowsInScan(scanner);
  }

  private static KuduScanner getScanner(int start, int end) {
    return getScanner(start, end, schema);
  }

  private static KuduScanner getScanner(int start, int end, Schema querySchema) {

    ColumnRangePredicate predicate = new ColumnRangePredicate(schema.getColumn(0));
    predicate.setLowerBound(start);
    predicate.setUpperBound(end);
    KuduScanner scanner = client.newScannerBuilder(table, querySchema)
        .addColumnRangePredicate(predicate)
        .build();
    return scanner;
  }
}
