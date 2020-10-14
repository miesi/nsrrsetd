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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;

/**
 *
 * @author mieslingert
 */
public class LatencyStore {

    private Connection cn;
    private static Logger logger = LoggerFactory.getLogger(LatencyStore.class);

    private LatencyStore() {
    }

    public LatencyStore(Connection cn) {
        this.cn = cn;
        try {
            Statement st = cn.createStatement();
            st.execute("create table serverLatency("
                    + "tld varchar(64),"
                    + "ip varchar(256),"
                    + "ipversion tinyint,"
                    + "latency bigint,"
                    + "lastUpdated bigint);");
            st.execute("create unique index i1 on serverLatency(tld,ip);");
            st.execute("create index i2 on serverLatency(tld,latency);");
            st.close();
        } catch (Exception e) {
            logger.error("setup db failed: {}", e.toString());
            System.exit(1);
        }
    }

    public void storeLatency(Name tld, InetAddress ip, long latency, long lastUpdated) {
        try {
            PreparedStatement st = cn.prepareStatement("merge into serverLatency "
                    + "key(tld,ip) "
                    + "values (?,?,?,?,?);");
            st.setString(1, tld.toString(true));
            st.setString(2, ip.toString());
            if (ip instanceof Inet6Address) {
                st.setInt(3, 6);
            } else {
                st.setInt(3, 4);
            }
            st.setLong(4, latency);
            st.setLong(5, lastUpdated);
            st.execute();
            logger.debug("inserted {} {} {}ms", tld.toString(true), ip.toString(), latency);
        } catch (Exception e) {
            logger.warn("merge failed: {}", e.toString());
        }
    }

    public String dumpLatencyData() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("tld;ip;latency;\n");
            PreparedStatement st = cn.prepareStatement("select tld, ip, latency from serverLatency order by tld, latency");
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                sb.append(rs.getString(1));
                sb.append(";");
                sb.append(rs.getString(2));
                sb.append(";");
                sb.append(rs.getInt(3));
                sb.append(";\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("failed to dump table serverLatency");
        }
        return "Error";
    }
}
