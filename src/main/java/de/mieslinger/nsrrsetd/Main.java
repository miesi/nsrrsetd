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
import de.mieslinger.nsrrsetd.servlets.ServletGetDelegatingNSSet;
import de.mieslinger.nsrrsetd.servlets.ServletStatistics;
import de.mieslinger.nsrrsetd.transfer.QueryIpForZone;
import de.mieslinger.nsrrsetd.transfer.QueryNsForIP;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import de.mieslinger.nsrrsetd.servlets.ServletRoot;
import de.mieslinger.nsrrsetd.servlets.ServletStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
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
public class Main {

    @Argument(alias = "s", description = "AXFR source for '.' Zone")
    private static String axfrSource = "iad.xfr.dns.icann.org";

    @Argument(alias = "r", description = "Resolver to query")
    private static String resolverToWarm = "10.2.215.21";

    @Argument(alias = "nt", description = "Number of Threads for NS lookups (6 by default)")
    private static String strThreadsNSLookup = "12";
    private static int numThreadsNSLookup;

    @Argument(alias = "at", description = "Number of Threads for A lookups (12 by default)")
    private static String strThreadsALookup = "50";
    private static int numThreadsALookup;

    @Argument(alias = "aaaat", description = "Number of Threads for AAAA lookups (12 by default, 0 for disable)")
    private static String strThreadsAAAALookup = "50";
    private static int numThreadsAAAALookup;

    @Argument(alias = "dnst", description = "Number of Threads for DNS Check (50 default, 0 for disable))")
    private static String strThreadsDNSCheck = "100";
    private static int numThreadsDNSCheck;

    @Argument(alias = "t", description = "resolver timeout (4 seconds default)")
    private static String strTimeout = "4";
    private static int numTimeout;

    @Argument(alias = "a", description = "retransfer root zone after n seconds (86400 default)")
    private static String strRootZoneMaxAge = "86400";

    @Argument(alias = "bc", description = "background checking of NS/A/AAAA every n seconds (1200s default)")
    private static String strBackgroundCheck = "1200";

    /* somehow set -Dorg.slf4j.simpleLogger.defaultLogLevel=debug with this
     * @Argument(alias = "d", description = "enable debug")
     * private static boolean debug = false;
     */
    @Argument(alias = "he", description = "enable http (default disabled)")
    private static boolean httpEnabled = false;

    @Argument(alias = "hp", description = "http port (default 8989)")
    private static String strHttpPort = "8989";
    private static int numHttpPort;

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
    private static long lastTransfer;
    private static Server jetty;
    private static boolean doAAAAlookup = true;
    private static boolean doQueryTLDserver = true;
    public static boolean doStoreResults = true;
    private static boolean tldCacheComplete = false;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

        List<String> unparsed = Args.parseOrExit(Main.class, args);

        //private static String strThreadsNSLookup = 12;
        numThreadsNSLookup = Integer.parseInt(strThreadsNSLookup);

        //private static String strThreadsALookup = 50;
        numThreadsALookup = Integer.parseInt(strThreadsALookup);

        //private static String strThreadsAAAALookup = 50;
        numThreadsAAAALookup = Integer.parseInt(strThreadsAAAALookup);

        //private static String strThreadsDNSCheck = 100;
        numThreadsDNSCheck = Integer.parseInt(strThreadsDNSCheck);

        //private static String strTimeout = 4;
        numTimeout = Integer.parseInt(strTimeout);

        //private static String strRootZoneMaxAge = 86400;
        int numRootZoneMaxAge = Integer.parseInt(strRootZoneMaxAge) * 1000;

        //private static String strBackgroundCheck = 1200;
        int numBackgroundCheck = Integer.parseInt(strBackgroundCheck);

        //String strHttpPort = 8989;
        numHttpPort = Integer.parseInt(strHttpPort);

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
            } catch (Exception e) {
                logger.warn("reRun sleep was interrupted: {}", e.getMessage());
            }
        }
    }

    private static void transferRootZone() {
        try {
            ZoneTransferIn xfr = ZoneTransferIn.newAXFR(new Name("."), axfrSource, null);
            xfr.run();
            records = xfr.getAXFR();
            lastSeenName = new Name("abrakadabr.test");
            lastTransfer = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("AXFR failed: {}, exiting", e.toString());
            // FIXME: only die if first transfer after startup fails
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
