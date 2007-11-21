/*
 * Bruce - A PostgreSQL Database Replication System
 *
 * Portions Copyright (c) 2007, Connexus Corporation
 *
 * Permission to use, copy, modify, and distribute this software and its
 * documentation for any purpose, without fee, and without a written
 * agreement is hereby granted, provided that the above copyright notice and
 * this paragraph and the following two paragraphs appear in all copies.
 *
 * IN NO EVENT SHALL CONNEXUS CORPORATION BE LIABLE TO ANY PARTY FOR DIRECT,
 * INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST
 * PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION,
 * EVEN IF CONNEXUS CORPORATION HAS BEEN ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * CONNEXUS CORPORATION SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS ON AN "AS IS"
 * BASIS, AND CONNEXUS CORPORATION HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE,
 * SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
*/
package com.netblue.bruce;


import com.netblue.bruce.cluster.*;
import org.apache.commons.dbcp.*;
import org.apache.log4j.Logger;
import org.postgresql.PGConnection;
import static java.text.MessageFormat.format;

import java.sql.*;
import javax.sql.DataSource;
import java.util.*;

/**
 * Responsible for obtaining {@link com.netblue.bruce.Snapshot}s from the <code>SnapshotCache</code>
 *
 * @author lanceball
 * @version $Id$
 */
public class SlaveRunner implements Runnable {
    public SlaveRunner(final DataSource masterDataSource, final Cluster cluster, final Node node) 
	throws SQLException, InstantiationException {
	logger.debug("SlaveRunner()");
	this.node = node;
	this.cluster = cluster;
	this.masterDataSource = masterDataSource;
	this.properties = new BruceProperties();
	this.unavailableSleepTime = properties.getIntProperty(NEXT_SNAPSHOT_UNAVAILABLE_SLEEP_KEY, 
							      NEXT_SNAPSHOT_UNAVAILABLE_SLEEP_DEFAULT);
	this.availableSleepTime = properties.getIntProperty(NEXT_SNAPSHOT_AVAILABLE_SLEEP_KEY, 
							    NEXT_SNAPSHOT_AVAILABLE_SLEEP_DEFAULT);
	// slaveDataSource
	this.slaveDataSource = new BasicDataSource();
        this.slaveDataSource.setDriverClassName(properties.getProperty("bruce.jdbcDriverName", 
								       "org.postgresql.Driver"));
	this.slaveDataSource.setValidationQuery(properties.getProperty("bruce.poolQuery", "select now()"));
	this.slaveDataSource.setUrl(node.getUri());
        this.slaveDataSource.setAccessToUnderlyingConnectionAllowed(true);

	// Obtain last processed snapshot
	Connection c = this.slaveDataSource.getConnection();
	try {
	    PreparedStatement ps = c.prepareStatement(selectLastSnapshotQuery);
	    ps.setLong(1,cluster.getId());
	    ResultSet rs = ps.executeQuery();
	    if (rs.next()) {
		logger.debug("got last snapshot");
		this.lastProcessedSnapshot = 
		    new Snapshot(new TransactionID(rs.getLong("master_current_xaction")),
				 new TransactionID(rs.getLong("master_min_xaction")),
				 new TransactionID(rs.getLong("master_max_xaction")),
				 rs.getString("master_outstanding_xactions"));
		logger.debug(lastProcessedSnapshot);
	    } else {
		logger.debug("throwing");
		throw new InstantiationException("Unable to obtain slave snapshot status. "+
						 "Please ensure that this slave on "+
						 this.node.getUri()+" has been properly initialized.");
	    }
	} finally { c.close(); }
	
	// Spit out a little info about us
	logger.info("Replicating node: "+this.node.getName()+" at "+this.node.getUri());
    }

    public void run() {
	logger.debug("run()");
	LogSwitchHelper lsh = new LogSwitchHelper(properties,slaveDataSource,cluster.getId());
	while (!shutdownRequested) {
	    try {
		lsh.doSwitch();
	    } catch (SQLException e) {
		logger.warn("SQLException during log switch, continuing",e);
	    }
	    Snapshot nextSnapshot = getNextSnapshot();
	    logger.trace("nextSnapshot: "+nextSnapshot);
	    try {
		if (nextSnapshot != null) {
		    processSnapshot(nextSnapshot);
		    Thread.sleep(availableSleepTime);
		} else {
		    Thread.sleep(unavailableSleepTime);
		}
	    } catch (InterruptedException e) {
		logger.error("Slave Interrupted",e);
		shutdownRequested=true;
	    }
	}
	try {
	    slaveDataSource.close();
	} catch (SQLException e) {} // Probably already closed.
	logger.info(node.getName()+" shutdown complete.");
    }

    public synchronized void shutdown() {
	shutdownRequested = true;
    }

    /**
     * Gets the next snapshot from the master database. Will return null if no next snapshot
     * available.
     *
     * Public so we can test this method from junit. Otherwise, probably could be private.
     *
     * @return the next Snapshot when it becomes available
     */
    public Snapshot getNextSnapshot() {
        logger.trace("Getting next snapshot");
	Snapshot retVal = null;
	long nextNormalXID = lastProcessedSnapshot.getCurrentXid().nextNormal().getLong();
	long lastNormalXID = lastProcessedSnapshot.getCurrentXid().lastNormal().getLong();
	logger.trace("processedSnapshot:"+lastProcessedSnapshot.getCurrentXid()+
		     " nextNormalXID:"+nextNormalXID+" lastNormalXID:"+lastNormalXID);
	// We have to determine possible values for the next XID. There is a detailed 
	// discussion around the nature of PostgreSQL transaction IDs in 
	// TransactionID.java, but the short version is this:
	// TransactionIDs are 32-bit modulo-31 numbers, with 2^31st TransactionIDs 
	// greater than, and 2^31 TransactionIDs less than any TransactionID. 
	// Except: Some TransactionIDs are special, and for the purpose of this 
	// discussion, can be considered always less than our TransactionID
	try {
	    Connection c = masterDataSource.getConnection();
	    try { // Make sure the connection we just got gets closed
		PreparedStatement ps;
		if (nextNormalXID < lastNormalXID) {
		    // Two cases here. One, where the nextNormalID is less than lastNormalXID, and,
		    // thus, a simple less than or equals test can be used
		    logger.trace("simple case");
		    ps = c.prepareStatement(format(nextSnapshotSimpleQuery,cluster.getId().toString()));
		    ps.setLong(1, TransactionID.INVALID);
		    ps.setLong(2, TransactionID.BOOTSTRAP);
		    ps.setLong(3, TransactionID.FROZEN);
		    ps.setLong(4, nextNormalXID);
		    ps.setLong(5, lastNormalXID);
		} else {
		    // Second case is where nextNormalID is greater than lastNormalXID. This occurs
		    // when the lastNormalXID has wrapped around 2^32. The test here is a little more
		    // complex, we are looking for snapshots either >=nextNormalXID or <=lastNormalXID
		    logger.trace("wraparound case");
		    ps = c.prepareStatement(format(nextSnapshotWraparoundQuery,cluster.getId().toString()));
		    ps.setLong(1, TransactionID.INVALID);
		    ps.setLong(2, TransactionID.BOOTSTRAP);
		    ps.setLong(3, TransactionID.FROZEN);
		    ps.setLong(4, nextNormalXID);
		    ps.setLong(5, TransactionID.MAXNORMAL);
		    ps.setLong(6, TransactionID.FIRSTNORMAL);
		    ps.setLong(7, lastNormalXID);
		}
		ResultSet rs = ps.executeQuery();
		if (rs.next()) {
		    retVal = new Snapshot(new TransactionID(rs.getLong("current_xaction")),
					  new TransactionID(rs.getLong("min_xaction")),
					  new TransactionID(rs.getLong("max_xaction")),
					  rs.getString("outstanding_xactions"));
		}
	    } finally {
		c.close();
	    }
	} catch (SQLException e) {
	    logger.info("Can not obtain next Snapshot due to SQLException. continuing but returning null",e);
	}
	return retVal;
    }

    /**
     * Updates the slave node with transactions from <code>snapshot</code> and sets this node's status table with the
     * latest snapshot status - atomically.
     *
     * @param snapshot the <code>Snapshot</code> to process
     */
    protected void processSnapshot(final Snapshot snapshot) {
        logger.trace("Processing next snapshot: " + snapshot);
	try {
	    Connection c = slaveDataSource.getConnection();
	    try {
		// Use server side prepated statements
		PGConnection pgc = (PGConnection) ((DelegatingConnection) c).getInnermostDelegate();
		pgc.setPrepareThreshold(1);
		//
		c.setAutoCommit(false);
		c.setSavepoint();
		if (snapshot == null) {
		    logger.trace("Latest Master snapshot is null. Can not process snapshot.");
		} else {
		    collectAllChangesForTransaction(c,snapshot);
		    applyAllChangesForTransaction(c,snapshot);
		    updateSnapshotStatus(c,snapshot);
		}
		c.commit();
		this.lastProcessedSnapshot = snapshot;
	    } catch (SQLException e) {
		logger.error("Cannot commit last processed snapshot.", e);
		try {
		    if (c != null) {
			c.rollback();
		    }
		} catch (SQLException e1) {
		    logger.error("Unable to rollback last processed snapshot transaction.", e);
		}
	    } finally {
		c.close();
	    }
	} catch (SQLException e) {
	    logger.error("Unable to obtain database connection",e);
	}
    }
    
    private void collectAllChangesForTransaction(Connection slaveC, Snapshot s) throws SQLException {
	String clusterID = cluster.getId().toString();
	Connection masterC = masterDataSource.getConnection();
	try {
	    logger.trace("collectAllChangesForTransaction("+slaveC+","+s+")");
	    masterC.setAutoCommit(false);
	    // Create temp table on slave to hold transaction we are going to apply
	    Statement slaveS = slaveC.createStatement();
	    slaveS.execute(format(createTempTable,clusterID));
	    PreparedStatement masterPS = 
		masterC.prepareStatement(format(getOutstandingTransactionsQuery,clusterID));
	    masterPS.setFetchSize(50);
	    masterPS.setLong(1,lastProcessedSnapshot.getMinXid().getLong());
	    masterPS.setLong(2,s.getMaxXid().getLong());
	    ResultSet masterRS = masterPS.executeQuery();
	    PreparedStatement insertTempPS =
		slaveC.prepareStatement(insertTempTable);
	    logger.trace("Populating temporary table with transactions to apply");
	    while (masterRS.next()) {
		TransactionID tid = new TransactionID(masterRS.getLong("xaction"));
		if (lastProcessedSnapshot.transactionIDGE(tid) &&
		    s.transactionIDLT(tid)) {
		    insertTempPS.setLong(1,masterRS.getLong("rowid"));
		    insertTempPS.setLong(2,masterRS.getLong("xaction"));
		    insertTempPS.setString(3,masterRS.getString("cmdtype"));
		    insertTempPS.setString(4,masterRS.getString("tabname"));
		    insertTempPS.setString(5,masterRS.getString("info"));
		    insertTempPS.execute();
		}
	    }
	    logger.trace("Populating slave snapshot/transaction logs with data from master");
	    ResultSet slaveRS = slaveS.executeQuery(format(determineLatestLogQuery,clusterID));
	    if (slaveRS.next()) {
		String logID = slaveRS.getString("id");
		PreparedStatement ps = slaveC.prepareStatement(format(populateSlaveSnapshotLogQuery,clusterID,logID));
		ps.setLong(1,s.getCurrentXid().getLong());
		ps.setLong(2,s.getMinXid().getLong());
		ps.setLong(3,s.getMaxXid().getLong());
		ps.setString(4,s.getInFlight());
		ps.execute();
		slaveS.execute(format(populateSlaveTransactonLogQuery,clusterID,logID));
	    } else {
		logger.error("unable to determine current log number. Continuing anyways.");
	    }
	    logger.trace("Remove from temp table any transactions for tables we dont replicate on this slave");
	    slaveS.execute(deleteUnreplicatedTransactionsQuery);
	} finally { masterC.close(); }
    }

    private void applyAllChangesForTransaction(Connection c, Snapshot s) throws SQLException {
	Statement slaveS = c.createStatement();
	slaveS.execute(daemonModeQuery);
	slaveS.execute(applyTransactionsQuery);
	slaveS.execute(normalModeQuery);
	slaveS.execute(dropTempTable);
    }

    private void updateSnapshotStatus(Connection c, Snapshot s) throws SQLException {
	PreparedStatement ps = c.prepareStatement(updateLastSnapshotQuery);
        ps.setLong(1, getCurrentTransactionId(c));
        ps.setLong(2, new Long(s.getCurrentXid().toString()));
        ps.setLong(3, new Long(s.getMinXid().toString()));
        ps.setLong(4, new Long(s.getMaxXid().toString()));
        ps.setString(5, s.getInFlight());
        ps.setLong(6, cluster.getId());
        ps.execute();
    }

    /**
     * Helper method to get the transaction ID of the currently executing transaction.
     *
     * @return The transaction ID of the currently executing transaction
     *
     * @throws SQLException
     */
    private long getCurrentTransactionId(Connection c) throws SQLException {
	Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(slaveTransactionIdQuery);
        if (rs.next()) {
	    long xid = rs.getLong("transaction");
	    rs.close();
	    return xid;
	} else {
	    logger.error("Unable to determine current transactionID");
	    return -1L;
	}
    }

    private static final Logger logger = Logger.getLogger(SlaveRunner.class);
    private Node node;
    private Cluster cluster;
    private DataSource masterDataSource;
    private BasicDataSource slaveDataSource;
    private BruceProperties properties;
    private int unavailableSleepTime;
    private int availableSleepTime;
    private Snapshot lastProcessedSnapshot;
    private boolean shutdownRequested = false;

    // How long to wait if a 'next' snapshot is unavailable, in miliseconds
    private static final String NEXT_SNAPSHOT_UNAVAILABLE_SLEEP_KEY = "bruce.nextSnapshotUnavailableSleep";
    // This default value may need some tuning. 100ms seemed too small, 1s might be right
    private static int NEXT_SNAPSHOT_UNAVAILABLE_SLEEP_DEFAULT = 1000;

    // How long to wait when 'next' snapshot was available, in miliseconds
    private static final String NEXT_SNAPSHOT_AVAILABLE_SLEEP_KEY = "bruce.nextSnapshotAvailableSleep";
    // This default value may need some tuning. Assuming 1s might be right
    private static int NEXT_SNAPSHOT_AVAILABLE_SLEEP_DEFAULT = 1000;

    private static final String selectLastSnapshotQuery =
	"select * from bruce.slavesnapshotstatus where clusterid = ?";
    // Input for MessageFormat.format()
    private static final String nextSnapshotSimpleQuery =
	"select * from bruce.snapshotlog_{0} "+
	" where current_xaction not in (?,?,?) "+
	"   and current_xaction >= ? "+
	"   and current_xaction <= ? "+
	" order by current_xaction desc limit 1";
    // Input for MessageFormat.format()
    private static final String nextSnapshotWraparoundQuery =
	"select * from bruce.snapshotlog_{0} "+
	" where current_xaction not in (?,?,?) "+
	"   and ((current_xaction >= ? and current_xaction <= ?) "+
	"     or (current_xaction >= ? and current_xaction <= ?)) "+
	" order by current_xaction desc limit 1";
    private static final String updateLastSnapshotQuery =
	"update bruce.slavesnapshotstatus "+
	"   set slave_xaction = ?,  master_current_xaction = ?, master_min_xaction = ?, master_max_xaction = ?, "+
	"       master_outstanding_xactions = ?, update_time = now() "+
	" where clusterid = ?";
    private static final String slaveTransactionIdQuery =
	"select * from pg_locks where pid = pg_backend_pid() and locktype = 'transactionid'";
    private static final String tempTableName = "tmpxactions";
    private static final String createTempTable =
	"create temporary table "+tempTableName+" as select * from bruce.transactionlog_{0} limit 0";
    private static final String dropTempTable = "drop table "+tempTableName;
    private static final String insertTempTable = 
	"insert into "+tempTableName+
	"(rowid,xaction,cmdtype,tabname,info) "+
	"values (?,?,?,?,?)";
    // Input for MessageFormat.format()
    private static final String getOutstandingTransactionsQuery =
	"select * from bruce.transactionlog_{0} where xaction >= ? and xaction < ?";
    private static final String determineLatestLogQuery = 
	"select max(id) as id from bruce.currentlog_{0}";
    private static final String populateSlaveSnapshotLogQuery =
	"insert into bruce.snapshotlog_{0}_{1} (current_xaction,min_xaction,max_xaction,outstanding_xactions) "+
	"values (?,?,?,?)";
    private static final String populateSlaveTransactonLogQuery =
	"insert into bruce.transactionlog_{0}_{1} (rowid,xaction,cmdtype,tabname,info) "+
	"select rowid,xaction,cmdtype,tabname,info from "+tempTableName;
    private static final String deleteUnreplicatedTransactionsQuery =
	"delete from "+tempTableName+" "+
	"where tabname not in "+
	"(select n.nspname||'.'||c.relname as tablename from pg_class c, pg_namespace n "+
	"  where c.relnamespace = n.oid "+
	"   and c.oid in (select tgrelid from pg_trigger "+
	"                  where tgfoid = (select oid from pg_proc "+
	"                                   where proname = 'denyaccesstrigger' "+
	"                                     and pronamespace = (select oid from pg_namespace "+
	"                                                          where nspname = 'bruce'))))";
    private static final String applyTransactionsQuery =
	"select bruce.applyLogTransaction(cmdtype,tabname,info) "+
	"  from "+tempTableName+" order by rowid";
    private static final String daemonModeQuery = "select bruce.daemonmode()";
    private static final String normalModeQuery = "select bruce.normalmode()";
}


//     /**
//      * Gets a DB connection, and ensures that all {@link java.sql.PreparedStatement}s we have are valid.
//      *
//      * @return
//      *
//      * @throws SQLException
//      */
//     private Connection getConnection() throws SQLException
//     {
//         if (!hasValidConnection())
//         {
//             initializeDatabaseResources();
//         }
//         return theOneConnection;
//     }

//     /**
//      * Checks the state of our connection
//      *
//      * @return true if we have a valid, open connection
//      */
//     private boolean hasValidConnection()
//     {
//         try
//         {
//             return (theOneConnection != null && !theOneConnection.isClosed());
//         }
//         catch (SQLException e)
//         {
//             LOGGER.error(e);
//         }
//         return false;
//     }

//     /**
//      * Opens a connection to the database, sets our internal instance to that connection, and initializes all
//      * PreparedStatments we will use.
//      *
//      * @throws SQLException
//      */
//     private void initializeDatabaseResources() throws SQLException
//     {
//         theOneConnection = dataSource.getConnection();
//         theOneConnection.setAutoCommit(false);
//         theOneConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
//         try
//         {
//             PGConnection theOneConnectionPg =
//                     (PGConnection) ((DelegatingConnection) theOneConnection).getInnermostDelegate();
//             theOneConnectionPg.setPrepareThreshold(1);
//         }
//         catch (Throwable t)
//         {
//             LOGGER.debug("Throwable when setting Pg JDBC Prepare Threshold. Proceding anyways.", t);
//         }
//         prepareStatements();
//     }

//     /**
//      * Releases all database resources used by this slave.  Used during shutdown to cleanup after ourselves.
//      */
//     private void releaseDatabaseResources()
//     {
//         try
//         {
//             closeStatements();
//             if (hasValidConnection())
//             {
//                 getConnection().close();
//             }
//         }
//         catch (SQLException e)
//         {
//             LOGGER.error("Unable to close database resources.", e);
//         }
//     }

//     private void closeStatements() throws SQLException
//     {
//         selectLastSnapshotStatement.close();
//         updateLastSnapshotStatement.close();
//         slaveTransactionIdStatement.close();
//         applyTransactionsStatement.close();
//         daemonModeStatement.close();
//         normalModeStatement.close();
// 	slaveTableIDStatement.close();
//     }

//     /**
//      * Prepares all of the {@link java.sql.PreparedStatement}s we need for this class.  Assumes a valid and open {@link
//      * #theOneConnection} with auto commit off.
//      *
//      * @throws SQLException
//      */
//     private void prepareStatements() throws SQLException
//     {
//         Connection connection = getConnection();
//         selectLastSnapshotStatement = connection.prepareStatement(selectLastSnapshotQuery);
//         updateLastSnapshotStatement = connection.prepareStatement(updateLastSnapshotQuery);
//         slaveTransactionIdStatement = connection.prepareStatement(slaveTransactionIdQuery);
//         applyTransactionsStatement = connection.prepareStatement(applyTransactionsQuery);
//         daemonModeStatement = connection.prepareStatement(daemonModeQuery);
//         normalModeStatement = connection.prepareStatement(normalModeQuery);
// 	slaveTableIDStatement = connection.prepareStatement(slaveTableIDQuery);
// 	createTempTableStatement = connection.prepareStatement(format(tempTableQuery,cluster.getId().toString()));
//         connection.commit();
//     }

//     /**
//      * Gets the last <code>Snapshot</code> object successfully processed by this node or null if this node has no
//      * replicated data (that it is aware of).  Does not go to the database.  <code>SlaveRunner</code>s maintain last
//      * processed status in memory.
//      *
//      * @return the last {@link com.netblue.bruce.Snapshot} or null
//      */
//     public Snapshot getLastProcessedSnapshot()
//     {
//         LOGGER.trace("Last processed snapshot: " + lastProcessedSnapshot);
//         return lastProcessedSnapshot;
//     }


//     /**
//      * Queries for the latest processed <code>Snapshot</code> from the slavesnapshotstatus table
//      *
//      * @return the last known <code>Snapshot</code> to have been processed by this node or null if this node has not
//      *         processed any <code>Snapshot</code>s.  Not private simply for testing puposes.
//      */
//     protected Snapshot queryForLastProcessedSnapshot() throws SQLException
//     {
//         Snapshot snapshot = null;
//         Connection connection = getConnection();
//         selectLastSnapshotStatement.setLong(1, this.cluster.getId());
//         // If nothing is in the result set, then our lastProcessedSnapshot is null
//         final ResultSet resultSet = selectLastSnapshotStatement.executeQuery();
//         if (resultSet.next())
//         {
//             snapshot = new Snapshot(new TransactionID(resultSet.getLong("master_current_xaction")),
//                                     new TransactionID(resultSet.getLong("master_min_xaction")),
//                                     new TransactionID(resultSet.getLong("master_max_xaction")),
//                                     resultSet.getString("master_outstanding_xactions"));
//         }
//         resultSet.close();
//         connection.rollback();
//         return snapshot;
//     }

//     private void collectAllChangesForTransaction(Snapshot snapshot) throws SQLException {
// 	try {
// 	    masterC.setAutoCommit(false);
// 	    // Create temp table
// 	    createTempTable.execute();
// 	    // Get transactions, populate temp table with them.
// 	    PreparedStatement masterPS = 
// 		masterC.prepareStatement(format(getOutstandingTransactionsQuery,cluster.getId().toString()));
// 	    masterPS.setFetchSize(50);
// 	    masterPS.setLong(1,lastProcessedSnapshot.getMinXid().getLong());
// 	    masterPS.setLong(2,snapshot.getMaxXid().getLong());
	    
	    
// 	    // Collect transactions, store in slave temp table
// 	} finally { masterC.close(); }
//     }

//     /**
//      * Applies all outstanding {@link com.netblue.bruce.Change}s to this slave
//      *
//      * @param snapshot A {@link com.netblue.bruce.Snapshot} containing the latest master snapshot.
//      */
//     private void applyAllChangesForTransaction(final Snapshot snapshot) throws SQLException
//     {
//         // This method is part of a larger transaction.  We don't validate/get the connection here,
//         // because if the connection becomes invalid as a part of that larger transaction, we're screwed
//         // anyway and we don't want to create a new connection for just part of the transaction
// 	if (snapshot == null) {
//             LOGGER.trace("Latest Master snapshot is null");
// 	} else {
//             LOGGER.trace("Applying transactions: ");
// 	    LOGGER.trace("Getting master database connection");
// 	    Connection masterC = masterDataSource.getConnection();
// 	    try { // prevent connection pool leakage
// 		masterC.setAutoCommit(false);
// 		PreparedStatement masterPS = 
// 		    masterC.prepareStatement(format(getOutstandingTransactionsQuery,cluster.getId().toString()));
// 		masterPS.setFetchSize(50);
// 		masterPS.setLong(1,lastProcessedSnapshot.getMinXid().getLong());
// 		masterPS.setLong(2,snapshot.getMaxXid().getLong());
// 		ResultSet masterRS = masterPS.executeQuery();
// 		LOGGER.trace("Entering daemon mode for slave");
// 		daemonModeStatement.execute();
// 		HashSet<String> slaveTables = getSlaveTables();
// 		LOGGER.trace("Processing changes");
// 		while (masterRS.next()) {
// 		    TransactionID tid = new TransactionID(masterRS.getLong("xaction"));
// 		    // Skip transactions not between snapshots
// 		    if (lastProcessedSnapshot.transactionIDGE(tid) &&
// 			snapshot.transactionIDLT(tid)) {
// 			if (slaveTables.contains(masterRS.getString("tabname"))) {
// 			    LOGGER.trace("Applying change."+
// 					 " xid:"+masterRS.getLong("rowid")+
// 					 " tid:"+tid+
// 					 " tabname:"+masterRS.getString("tabname")+
// 					 " cmdtype:"+masterRS.getString("cmdtype")+
// 					 " info:"+masterRS.getString("info"));
// 			    applyTransactionsStatement.setString(1, masterRS.getString("cmdtype"));
// 			    applyTransactionsStatement.setString(2, masterRS.getString("tabname"));
// 			    applyTransactionsStatement.setString(3, masterRS.getString("info"));
// 			    applyTransactionsStatement.execute();
// 			    LOGGER.trace("Change applied");
// 			} else {
// 			    LOGGER.trace("NOT applying change. Table not replicated on slave."+
// 					 " xid:"+masterRS.getLong("rowid")+
// 					 " tid:"+tid+
// 					 " tabname:"+masterRS.getString("tabname")+
// 					 " cmdtype:"+masterRS.getString("cmdtype")+
// 					 " info:"+masterRS.getString("info"));
// 			}
// 		    } else {
// 			LOGGER.trace("Transaction not between snapshots. tid:"+tid+
// 				     " lastslaveS:"+lastProcessedSnapshot+" masterS:"+snapshot);
// 		    }
// 		}
// 	    } finally {
// 		masterC.close();
// 	    }
//             normalModeStatement.execute();
//         }
//     }


//     public synchronized void shutdown()
//     {
//         LOGGER.info("Shutting down slave: " + node.getName());
//         shutdownRequested = true;
//     }

//     private HashSet<String> getSlaveTables() throws SQLException {
// 	HashSet<String> retVal = new HashSet<String>();
// 	ResultSet rs = slaveTableIDStatement.executeQuery();
// 	while (rs.next()) {
// 	    retVal.add(rs.getString("tablename"));
// 	}
// 	rs.close();
// 	return retVal;
//     }
    
//     // --------- Class fields ---------------- //
//     private Connection theOneConnection;
//     private PreparedStatement selectLastSnapshotStatement;
//     private PreparedStatement updateLastSnapshotStatement;
//     private PreparedStatement slaveTransactionIdStatement;
//     private PreparedStatement applyTransactionsStatement;
//     private PreparedStatement daemonModeStatement;
//     private PreparedStatement normalModeStatement;
//     private PreparedStatement slaveTableIDStatement;
//     private PrepatedStatement createTempTableStatement;

//     // --------- Constants ------------------- //
//     private final int sleepTime;
//     private final BasicDataSource dataSource = new BasicDataSource();

//     // --------- Static Constants ------------ //
//     private static final Logger LOGGER = Logger.getLogger(SlaveRunner.class);
//     private static final String selectLastSnapshotQuery =
// 	"select * from bruce.slavesnapshotstatus where clusterid = ?";
//     private static final String applyTransactionsQuery = 
// 	"select bruce.applyLogTransaction(?, ?, ?)";
//     private static final String slaveTableIDQuery = 
//        "select n.nspname||'.'||c.relname as tablename from pg_class c, pg_namespace n "+
//        " where c.relnamespace = n.oid "+
//        "   and c.oid in (select tgrelid from pg_trigger "+
//        "                  where tgfoid = (select oid from pg_proc "+
//        "                                   where proname = 'denyaccesstrigger' "+
//        "                                     and pronamespace = (select oid from pg_namespace "+
//        "                                                          where nspname = 'bruce')))";
//     private static final String tempTableName = "tmpxactions";
//     private static final String createTempTable =
// 	"create temporary table "+tempTableName+" as select * from bruce.transactionlog_{0} limit 1";
//     private static final String insertTempTable = 
// 	"insert into "+tempTableName+"(rowid,xaction,cmdtype,tabname,info) "+
// 	"values (?,?,?,?,?)";


