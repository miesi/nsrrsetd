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

import java.net.InetAddress;
import org.xbill.DNS.Name;

/**
 *
 * @author mieslingert
 */
public class QueryIpForZone {

    private InetAddress ip;
    private Name zone;
    private boolean record = false;

    private QueryIpForZone() {
    }

    public QueryIpForZone(InetAddress ip, Name zone) {
        this.ip = ip;
        this.zone = zone;
    }

    public QueryIpForZone(InetAddress ip, Name zone, boolean recordLatency) {
        this.ip = ip;
        this.zone = zone;
        this.record = recordLatency;
    }

    public InetAddress getIp() {
        return ip;
    }

    public Name getZone() {
        return zone;
    }

    public boolean getRecordLatency() {
        return record;
    }

}
