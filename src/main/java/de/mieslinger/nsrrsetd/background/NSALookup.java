/*
 * The MIT License
 *
 * Copyright 2019 mieslingert.
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
package de.mieslinger.nsrrsetd.background;

import de.mieslinger.nsrrsetd.Main;
import de.mieslinger.nsrrsetd.transfer.QueryIpForZone;
import de.mieslinger.nsrrsetd.transfer.QueryNsForIP;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 *
 * @author mieslingert
 */
public class NSALookup implements Runnable {

    private ConcurrentLinkedQueue<QueryNsForIP> queueALookup;
    private ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck;
    private String resolverToWarm;
    private boolean keepOnRunning = true;
    private final Logger logger = LoggerFactory.getLogger(NSALookup.class);
    private Cache c;
    private int timeout;
    private boolean doQueryTLDserver = true;

    private NSALookup() {

    }

    public NSALookup(ConcurrentLinkedQueue<QueryNsForIP> queueALookup,
            ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck,
            String resolverToWarm,
            int timeout) {
        this.queueALookup = queueALookup;
        this.queueDNSCheck = queueDNSCheck;
        this.resolverToWarm = resolverToWarm;
        this.c = new Cache();
        c.setMaxEntries(0);
        this.timeout = timeout;
        this.doQueryTLDserver = Main.doQueryTLDserver();
    }

    @Override
    public void run() {
        while (keepOnRunning) {
            try {
                QueryNsForIP n = queueALookup.poll();
                if (n != null) {
                    doLookup(n);
                } else {
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                logger.warn("A Lookup Exception: ", e);
            }
        }

    }

    private void doLookup(QueryNsForIP n) throws Exception {
        logger.debug("Query A for {} of tld {}", n.getServerName(), n.getTld());
        Lookup la = new Lookup(n.getServerName(), Type.A, DClass.IN);
        la.setCache(c);
        SimpleResolver r = new SimpleResolver(resolverToWarm);
        r.setTimeout(Duration.ofSeconds(timeout));
        la.setResolver(r);
        long begin = System.currentTimeMillis();
        la.run();
        long end = System.currentTimeMillis();
        long latency = end - begin;
        switch (la.getResult()) {
            case Lookup.SUCCESSFUL:
                logger.debug("Query for AAAA of {} took {}ms", n.getServerName().toString(true), latency);
                if (doQueryTLDserver) {
                    for (int i = 0; i < la.getAnswers().length; i++) {
                        ARecord a = (ARecord) la.getAnswers()[0];
                        QueryIpForZone q = new QueryIpForZone(a.getAddress(), n.getTld(), true);
                        queueDNSCheck.add(q);
                        logger.debug("queued direct query to {} for {}", a.getAddress(), n.getTld().toString(true));
                    }
                }
                break;
            case Lookup.HOST_NOT_FOUND:
                logger.debug("HOST_NOT_FOUND A record for {}", n);
                break;
            case Lookup.TYPE_NOT_FOUND:
                logger.debug("TYPE_NOT_FOUND A record for {}", n);
                break;
            default:
                logger.warn("query A for NS {} failed!", n.getTld().toString(true));
                break;
        }
    }
}
