/*
 * The MIT License
 *
 * Copyright 2020 mieslingert.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.mieslinger.nsrrsetd;

import de.mieslinger.nsrrsetd.store.LatencyStore;
import de.mieslinger.nsrrsetd.background.DelegationNSSetLookup;
import de.mieslinger.nsrrsetd.background.LookupZone;
import de.mieslinger.nsrrsetd.background.NSAAAALookup;
import de.mieslinger.nsrrsetd.background.NSALookup;
import de.mieslinger.nsrrsetd.servlets.ServletCheckDelegation;
import de.mieslinger.nsrrsetd.servlets.ServletGetDelegatingNSSet;
import de.mieslinger.nsrrsetd.servlets.ServletStatistics;
import de.mieslinger.nsrrsetd.transfer.QueryIpForZone;
import de.mieslinger.nsrrsetd.transfer.QueryNsForIP;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import de.mieslinger.nsrrsetd.servlets.ServletRoot;
import de.mieslinger.nsrrsetd.servlets.ServletStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import org.xbill.DNS.ZoneTransferIn;

/**
 *
 * @author mieslingert
 */
@Command(name = "nsrrsetd", mixinStandardHelpOptions = true,
         description = "NS RRset Daemon - DNS cache warming and monitoring tool")
public class Main implements Runnable {

    @Option(names = {"-s", "--axfr-source"}, description = "AXFR source for '.' Zone (default: ${DEFAULT-VALUE})")
    private static String axfrSource = "ns-anyslv.ui-dns.com";

    @Option(names = {"-sr", "--axfr-retries"}, description = "AXFR retries (default: ${DEFAULT-VALUE})")
    private static int numAxfrRetries = 3;

    @Option(names = {"-r", "--resolver"}, description = "Resolver to query (default: ${DEFAULT-VALUE})")
    private static String resolverToWarm = "10.2.215.21";

    @Option(names = {"-nt", "--ns-threads"}, description = "Number of Threads for NS lookups (default: ${DEFAULT-VALUE})")
    private static int numThreadsNSLookup = 12;

    @Option(names = {"-at", "--a-threads"}, description = "Number of Threads for A lookups (default: ${DEFAULT-VALUE})")
    private static int numThreadsALookup = 50;

    @Option(names = {"-aaaat", "--aaaa-threads"}, description = "Number of Threads for AAAA lookups (0 to disable) (default: ${DEFAULT-VALUE})")
    private static int numThreadsAAAALookup = 50;

    @Option(names = {"-dnst", "--dns-check-threads"}, description = "Number of Threads for DNS Check (0 to disable) (default: ${DEFAULT-VALUE})")
    private static int numThreadsDNSCheck = 100;

    @Option(names = {"-t", "--timeout"}, description = "resolver timeout in seconds (default: ${DEFAULT-VALUE})")
    private static int numTimeout = 4;

    @Option(names = {"-a", "--root-zone-max-age"}, description = "retransfer root zone after n seconds (default: ${DEFAULT-VALUE})")
    private static int strRootZoneMaxAge = 86400;

    @Option(names = {"-bc", "--background-check"}, description = "background checking of NS/A/AAAA every n seconds (default: ${DEFAULT-VALUE})")
    private static int strBackgroundCheck = 1200;

    /* somehow set -Dorg.slf4j.simpleLogger.defaultLogLevel=debug with this
     * @Option(names = {"-d", "--debug"}, description = "enable debug")
     * private static boolean debug = false;
     */
    @Option(names = {"-he", "--http-enabled"}, description = "enable http (default: ${DEFAULT-VALUE})")
    private static boolean httpEnabled = false;

    @Option(names = {"-hp", "--http-port"}, description = "http port (default: ${DEFAULT-VALUE})")
    private static int numHttpPort = 8989;

    private static final ConcurrentLinkedQueue<Record> queueDelegation = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<QueryNsForIP> queueALookup = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<QueryNsForIP> queueAAAALookup = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck = new ConcurrentLinkedQueue<>();

    private static Name lastSeenName = null;
    private static List records = null;
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String jdbcUrl = "jdbc:h2:mem:myDB;DB_CLOSE_DELAY=-1";
    private static Connection dbConn;
    private static LatencyStore s;
    private static long lastTransfer = 0;
    private static Server jetty;
    private static boolean doAAAAlookup = true;
    private static boolean doQueryTLDserver = true;
    public static boolean doStoreResults = true;
    private static boolean tldCacheComplete = false;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    @Override
    public void run() {
        int numRootZoneMaxAge = strRootZoneMaxAge * 1000;
        int numBackgroundCheck = strBackgroundCheck;

        if (numThreadsAAAALookup <= 0) {
            doAAAAlookup = false;
        }
        if (numThreadsDNSCheck <= 0) {
            doQueryTLDserver = false;
            doStoreResults = false;
        } else {
            setupDB();
        }

        setupWorkerThreads();

        transferRootZone();

        if (httpEnabled) {
            startJetty();
        }

        while (true) {
            for (int i = 0; i < records.size(); i++) {
                Record r = (Record) records.get(i);
                logger.debug("Delegation: {}", r.getName());
                if (r.getType() == Type.NS) {
                    if (!lastSeenName.equals(r.getName())) {
                        lastSeenName = r.getName();
                        queueDelegation.add(r);
                        logger.debug("delegation {} queued", r.getName());
                    }
                }
            }

            // Shortcut to do only .com and .de
            /* 
             * Record r = null;
             * try {
             *   r = new NSRecord(new Name("com."), DClass.IN, 600, new Name("localhost."));
             *   queueDelegation.add(r);
             *   r = new NSRecord(new Name("de."), DClass.IN, 600, new Name("localhost."));
             *   queueDelegation.add(r);
             * } catch (Exception e) {
             *   logger.warn("Exception while adding only debug tlds {}", e.toString());
             *  }
             */
            //END Shortcut
            while (queueALookup.size() > 5 || queueDelegation.size() > 5 || queueDNSCheck.size() > 1) {
                try {
                    logger.info("delegation queue {}, A queue {}, AAAA queue {}, Check queue {}",
                            queueDelegation.size(),
                            queueALookup.size(),
                            queueAAAALookup.size(),
                            queueDNSCheck.size());
                    Thread.sleep(5000);
                } catch (Exception e) {
                    logger.warn("sleep interrupted: {}", e.getMessage());
                }
            }

            tldCacheComplete = true;

            if (lastTransfer + numRootZoneMaxAge < System.currentTimeMillis()) {
                logger.info("retransfering outdated root zone");
                transferRootZone();
            }

            // sleep reRun time
            try {
                logger.info("sleeping {} until next run", numBackgroundCheck);
                Thread.sleep(numBackgroundCheck * 1000);
                System.gc();
            } catch (Exception e) {
                logger.warn("reRun sleep was interrupted: {}", e.getMessage());
            }
        }
    }

    private static void transferRootZone() {
        int waitTimeMillis = 10000;
        for (int i = 0; i < numAxfrRetries; i++) {
            try {
                ZoneTransferIn xfr = ZoneTransferIn.newAXFR(new Name("."), axfrSource, null);
                xfr.run();
                records = xfr.getAXFR();
                lastSeenName = new Name("abrakadabr.test");
                lastTransfer = System.currentTimeMillis();
                break;
            } catch (Exception e) {
                logger.error("AXFR failed: {}, exiting", e.getMessage());
                e.printStackTrace();
                try {
                    Thread.sleep(waitTimeMillis);
                } catch (Exception ex) {
                    logger.info("Sleep after failed transfer was interrupted: {}", e.toString());
                }
                waitTimeMillis = waitTimeMillis * 2;
            }
        }
        if (lastTransfer == 0) {
            logger.error("all AXFR attempts failed, giving up.");
            System.exit(1);
        }
    }

    private static void setupWorkerThreads() {
        for (int i = 0; i < numThreadsNSLookup; i++) {
            Thread tNSLookup = new Thread(new DelegationNSSetLookup(queueDelegation, queueALookup, queueAAAALookup, resolverToWarm, numTimeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("DelegationNSSetLookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsALookup; i++) {
            Thread tNSLookup = new Thread(new NSALookup(queueALookup, queueDNSCheck, resolverToWarm, numTimeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("NSALookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsAAAALookup; i++) {
            Thread tNSLookup = new Thread(new NSAAAALookup(queueAAAALookup, queueDNSCheck, resolverToWarm, numTimeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("NSAAAALookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsDNSCheck; i++) {
            Thread tNSLookup = new Thread(new LookupZone(queueDNSCheck, s));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("DNSCheck-" + i);
            tNSLookup.start();
        }
    }

    private static void setupDB() {
        try {
            dbConn = DriverManager.getConnection(jdbcUrl, "sa", "sa");
            s = new LatencyStore(dbConn);
        } catch (Exception e) {
            logger.error("failed to connect {} exception: {}", jdbcUrl, e.getMessage());
            System.exit(1);
        }
    }

    private static void startJetty() {
        try {
            jetty = new Server(numHttpPort);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");

            jetty.setHandler(context);

            context.addServlet(ServletRoot.class, "/");
            context.addServlet(ServletStatus.class, "/status");
            context.addServlet(ServletStatistics.class, "/statistics");
            context.addServlet(ServletGetDelegatingNSSet.class, "/getDelegatingNSSet/*");
            context.addServlet(ServletCheckDelegation.class, "/checkDelegation");

            jetty.start();

        } catch (Exception e) {
            logger.warn("Jetty not started: {}", e.toString());
        }
    }

    public static int getNumThreadsNSLookup() {
        return numThreadsNSLookup;
    }

    public static int getNumThreadsALookup() {
        return numThreadsALookup;
    }

    public static int getNumThreadsAAAALookup() {
        return numThreadsAAAALookup;
    }

    public static int getNumThreadsDNSCheck() {
        return numThreadsDNSCheck;
    }

    public static int getQDSize() {
        return queueDelegation.size();
    }

    public static int getQASize() {
        return queueALookup.size();
    }

    public static int getQAAAASize() {
        return queueAAAALookup.size();
    }

    public static int getQDNSSize() {
        return queueDNSCheck.size();
    }

    public static boolean doAAAAlookup() {
        return doAAAAlookup;
    }

    public static boolean doQueryTLDserver() {
        return doQueryTLDserver;
    }

    public static Connection getDbConn() {
        return dbConn;
    }

    public static boolean tldCacheComplete() {
        return tldCacheComplete;
    }
}
