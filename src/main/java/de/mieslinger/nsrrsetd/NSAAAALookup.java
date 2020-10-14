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
package de.mieslinger.nsrrsetd;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 *
 * @author mieslingert
 */
public class NSAAAALookup implements Runnable {

    private ConcurrentLinkedQueue<QueryNsForIP> queueAAAALookup;
    private ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck;
    private String resolverToWarm;
    private boolean keepOnRunning = true;
    private final Logger logger = LoggerFactory.getLogger(NSAAAALookup.class);
    private Cache c;
    private int timeout;

    private NSAAAALookup() {

    }

    public NSAAAALookup(ConcurrentLinkedQueue<QueryNsForIP> queueAAAALookup,
            ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck,
            String resolverToWarm,
            Cache dnsJavaCache,
            int timeout) {
        this.queueAAAALookup = queueAAAALookup;
        this.queueDNSCheck = queueDNSCheck;
        this.resolverToWarm = resolverToWarm;
        this.c = dnsJavaCache;
        this.timeout = timeout;
    }

    @Override
    public void run() {
        while (keepOnRunning) {
            try {
                QueryNsForIP n = queueAAAALookup.poll();
                if (n != null) {
                    doLookup(n);
                } else {
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                logger.warn("AAAA Lookup Exception: ", e);
            }
        }

    }

    private void doLookup(QueryNsForIP n) throws Exception {
        logger.debug("Query AAAA for {} of tld {}", n.getServerName(), n.getTld());
        Lookup la = new Lookup(n.getServerName(), Type.AAAA, DClass.IN);
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
                // Store ips 
                for (int i = 0; i < la.getAnswers().length; i++) {
                    AAAARecord a = (AAAARecord) la.getAnswers()[0];
                    QueryIpForZone q = new QueryIpForZone(a.getAddress(), n.getTld(), true);
                    queueDNSCheck.add(q);
                    logger.debug("queued direct query to {} for {}", a.getAddress().toString(), n.getTld().toString(true));
                }
                break;
            case Lookup.HOST_NOT_FOUND:
                // NXDOMAIN
                logger.debug("HOST_NOT_FOUND AAAA record for {}", n);
                break;
            case Lookup.TYPE_NOT_FOUND:
                // empty NOERROR reply
                logger.debug("TYPE_NOT_FOUND AAAA record for {}", n);
                break;
            default:
                logger.warn("query AAAA for NS {} failed!", n);
                break;
        }
    }

}
