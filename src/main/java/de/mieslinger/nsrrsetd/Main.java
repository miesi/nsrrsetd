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

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Cache;
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
    private static String resolverToWarm = "212.227.123.16";

    @Argument(alias = "nt", description = "Number of Threads for NS lookups")
    private static int numThreadsNSLookup = 5;

    @Argument(alias = "at", description = "Number of Threads for AAAA lookups")
    private static int numThreadsALookup = 25;

    @Argument(alias = "aaaat", description = "Number of Threads for AAAA lookups")
    private static int numThreadsAAAALookup = 25;

    @Argument(alias = "dnst", description = "Number of Threads for DNS Check")
    private static int numThreadsDNSCheck = 25;

    @Argument(alias = "t", description = "resolver timeout (seconds)")
    private static int timeout = 4;

    @Argument(alias = "a", description = "retransfer root zone after n seconds")
    private static int rootZoneMaxAge = 86400;

    @Argument(alias = "bc", description = "background checking of NS/A/AAAA every n seconds")
    private static int backgroundCheck = 60;

    @Argument(alias = "d", description = "enable debug")
    private static boolean debug = false;

    @Argument(alias = "he", description = "http enabled (default true)")
    private static boolean httpEnabled = true;

    @Argument(alias = "hp", description = "http port (default 8989)")
    private static int httpPort = 8989;

    protected static final ConcurrentLinkedQueue<Record> queueDelegation = new ConcurrentLinkedQueue<>();
    protected static final ConcurrentLinkedQueue<QueryNsForIP> queueALookup = new ConcurrentLinkedQueue<>();
    protected static final ConcurrentLinkedQueue<QueryNsForIP> queueAAAALookup = new ConcurrentLinkedQueue<>();
    protected static final ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck = new ConcurrentLinkedQueue<>();

    private static Name lastSeenName = null;
    private static List records = null;
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String jdbcUrl = "jdbc:h2:mem:myDB;DB_CLOSE_DELAY=-1";
    protected static Connection dbConn;
    private static LatencyStore s;
    private static Cache dnsJavaCache;
    private static long lastTransfer;
    private static Server jetty;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        //if (debug) {
        //    org.slf4j.simpleLogger.defaultLogLevel = debug;
        //}
        List<String> unparsed = Args.parseOrExit(Main.class, args);

        setupDnsJava();

        setupDB();

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

            if (lastTransfer + rootZoneMaxAge < System.currentTimeMillis()) {
                logger.info("retransfering outdated root zone");
                transferRootZone();
            }

            // sleep reRun time
            try {
                logger.info("sleeping {} until next run", backgroundCheck);
                Thread.sleep(backgroundCheck * 1000);
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
            Thread tNSLookup = new Thread(new DelegationNSSetLookup(queueDelegation, queueALookup, queueAAAALookup, resolverToWarm, dnsJavaCache, timeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("DelegationNSSetLookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsALookup; i++) {
            Thread tNSLookup = new Thread(new NSALookup(queueALookup, queueDNSCheck, resolverToWarm, dnsJavaCache, timeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("NSALookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsAAAALookup; i++) {
            Thread tNSLookup = new Thread(new NSAAAALookup(queueAAAALookup, queueDNSCheck, resolverToWarm, dnsJavaCache, timeout));
            tNSLookup.setDaemon(true);
            tNSLookup.setName("NSAAAALookup-" + i);
            tNSLookup.start();
        }
        for (int i = 0; i < numThreadsDNSCheck; i++) {
            Thread tNSLookup = new Thread(new LookupZone(queueDNSCheck, s, dnsJavaCache));
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

    private static void setupDnsJava() {
        dnsJavaCache = new Cache();
        dnsJavaCache.setMaxEntries(0);
    }

    private static void startJetty() {
        try {
            // Note that if you set this to port 0 then a randomly available port
            // will be assigned that you can either look in the logs for the port,
            // or programmatically obtain it for use in test cases.
            jetty = new Server(httpPort);

            // The ServletHandler is a dead simple way to create a context handler
            // that is backed by an instance of a Servlet.
            // This handler then needs to be registered with the Server object.
            ServletHandler handler = new ServletHandler();
            jetty.setHandler(handler);

            // Passing in the class for the Servlet allows jetty to instantiate an
            // instance of that Servlet and mount it on a given context path.
            // IMPORTANT:
            // This is a raw Servlet, not a Servlet that has been configured
            // through a web.xml @WebServlet annotation, or anything similar.
            handler.addServletWithMapping(StatisticsServlet.class, "/*");
            
            jetty.start();
            
        } catch (Exception e) {
            logger.warn("Jetty not started: {}", e.toString());
        }
    }
}
