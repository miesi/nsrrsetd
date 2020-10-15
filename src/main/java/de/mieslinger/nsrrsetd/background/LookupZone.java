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
package de.mieslinger.nsrrsetd.background;

import de.mieslinger.nsrrsetd.store.LatencyStore;
import de.mieslinger.nsrrsetd.transfer.QueryIpForZone;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 *
 * @author mieslingert
 */
public class LookupZone implements Runnable {

    private boolean keepOnRunning = true;
    private final Logger logger = LoggerFactory.getLogger(LookupZone.class);
    private ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck;
    private LatencyStore s;
    private Cache c;

    private LookupZone() {
        this.queueDNSCheck = queueDNSCheck;
    }

    public LookupZone(ConcurrentLinkedQueue<QueryIpForZone> queueDNSCheck,
            LatencyStore s,
            Cache dnsJavaCache) {
        this.queueDNSCheck = queueDNSCheck;
        this.s = s;
        this.c = dnsJavaCache;
    }

    @Override
    public void run() {
        while (keepOnRunning) {
            try {
                QueryIpForZone n = queueDNSCheck.poll();
                if (n != null) {
                    doLookup(n);
                } else {
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                logger.warn("Exception while querying tld nameserver: ", e);
            }
        }
    }

    private void doLookup(QueryIpForZone n) throws Exception {
        logger.debug("Query NS Records for zone {} from server {}", n.getZone(), n.getIp());
        Lookup la = new Lookup(n.getZone(), Type.NS, DClass.IN);
        la.setCache(c);
        SimpleResolver r = new SimpleResolver(n.getIp());
        r.setTimeout(Duration.ofSeconds(20));
        la.setResolver(r);
        long begin = System.currentTimeMillis();
        la.run();
        long end = System.currentTimeMillis();
        long latency = end - begin;
        logger.debug("Query for NS Records of zone {} from server {} took {}ms", n.getZone(), n.getIp(), latency);
        if (n.getRecordLatency()) {
            s.storeLatency(n.getZone(), n.getIp(), latency, end);
        }
    }
}
