/*
 * The MIT License
 *
 * Copyright 2025 mieslingert.
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
package de.mieslinger.nsrrsetd.util;

import de.mieslinger.nsrrsetd.Main;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 * Dynamically populates nameserver data for third-level public suffixes
 * (e.g., co.uk, gov.uk, gv.at) by querying parent TLD servers.
 *
 * @author mieslingert
 */
public class PublicSuffixPopulator {

    private static final Logger logger = LoggerFactory.getLogger(PublicSuffixPopulator.class);

    /**
     * Populate nameserver data for a public suffix by querying parent TLD.
     *
     * @param publicSuffix The public suffix to populate (e.g., "co.uk")
     * @param parentTld The parent TLD (e.g., "uk")
     * @return true if successfully populated, false otherwise
     */
    public static boolean populatePublicSuffix(String publicSuffix, String parentTld) {
        logger.info("Populating nameserver data for public suffix: {} via parent TLD: {}", publicSuffix, parentTld);

        try {
            // Get a nameserver for the parent TLD
            Connection conn = Main.getDbConn();
            String parentNS = null;

            PreparedStatement st = conn.prepareStatement(
                "select ip from serverLatency where tld = ? order by latency limit 1"
            );
            st.setString(1, parentTld);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                parentNS = rs.getString(1);
            }
            rs.close();
            st.close();

            if (parentNS == null) {
                logger.warn("No nameserver found for parent TLD: {}", parentTld);
                return false;
            }

            logger.debug("Using parent TLD nameserver: {}", parentNS);

            // Query parent TLD for public suffix NS records
            Name suffixName = new Name(publicSuffix + ".");
            SimpleResolver resolver = new SimpleResolver(parentNS);
            resolver.setTimeout(Duration.ofMillis(Main.getDnsTimeoutMillis()));

            Record question = Record.newRecord(suffixName, Type.NS, DClass.IN);
            Message query = Message.newQuery(question);
            query.getHeader().unsetFlag(Flags.RD);

            Message response = resolver.send(query);

            if (response.getHeader().getRcode() != Rcode.NOERROR) {
                logger.warn("Query for {} NS records failed: {}", publicSuffix, Rcode.string(response.getHeader().getRcode()));
                return false;
            }

            // Extract NS records
            List<Name> nameservers = new ArrayList<>();
            Record[] answers = response.getSectionArray(Section.ANSWER);
            Record[] authority = response.getSectionArray(Section.AUTHORITY);

            for (Record r : answers) {
                if (r.getType() == Type.NS && r.getName().equals(suffixName)) {
                    NSRecord nsr = (NSRecord) r;
                    nameservers.add(nsr.getTarget());
                    logger.debug("Found NS for {}: {}", publicSuffix, nsr.getTarget());
                }
            }

            for (Record r : authority) {
                if (r.getType() == Type.NS && r.getName().equals(suffixName)) {
                    NSRecord nsr = (NSRecord) r;
                    if (!nameservers.contains(nsr.getTarget())) {
                        nameservers.add(nsr.getTarget());
                        logger.debug("Found NS for {}: {}", publicSuffix, nsr.getTarget());
                    }
                }
            }

            if (nameservers.isEmpty()) {
                logger.warn("No NS records found for {}", publicSuffix);
                return false;
            }

            // Resolve A/AAAA for each nameserver and test latency
            int populated = 0;
            for (Name ns : nameservers) {
                // Resolve A records
                Lookup aLookup = new Lookup(ns, Type.A, DClass.IN);
                aLookup.run();
                if (aLookup.getResult() == Lookup.SUCCESSFUL) {
                    Record[] aRecords = aLookup.getAnswers();
                    for (Record aRec : aRecords) {
                        ARecord a = (ARecord) aRec;
                        InetAddress ip = a.getAddress();
                        if (testAndStore(suffixName, ip)) {
                            populated++;
                        }
                    }
                }

                // Resolve AAAA records if enabled
                if (Main.doAAAAlookup()) {
                    Lookup aaaaLookup = new Lookup(ns, Type.AAAA, DClass.IN);
                    aaaaLookup.run();
                    if (aaaaLookup.getResult() == Lookup.SUCCESSFUL) {
                        Record[] aaaaRecords = aaaaLookup.getAnswers();
                        for (Record aaaaRec : aaaaRecords) {
                            AAAARecord aaaa = (AAAARecord) aaaaRec;
                            InetAddress ip = aaaa.getAddress();
                            if (testAndStore(suffixName, ip)) {
                                populated++;
                            }
                        }
                    }
                }
            }

            logger.info("Successfully populated {} nameserver(s) for public suffix: {}", populated, publicSuffix);
            return populated > 0;

        } catch (Exception e) {
            logger.error("Failed to populate public suffix {}: {}", publicSuffix, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Test latency to a nameserver and store in database
     */
    private static boolean testAndStore(Name tld, InetAddress ip) {
        try {
            SimpleResolver resolver = new SimpleResolver(ip.getHostAddress());
            resolver.setTimeout(Duration.ofMillis(Main.getDnsTimeoutMillis()));

            Record question = Record.newRecord(tld, Type.NS, DClass.IN);
            Message query = Message.newQuery(question);
            query.getHeader().unsetFlag(Flags.RD);

            long begin = System.currentTimeMillis();
            Message response = resolver.send(query);
            long end = System.currentTimeMillis();
            long latency = end - begin;

            if (response.getHeader().getRcode() == Rcode.NOERROR) {
                // Store in database
                Connection conn = Main.getDbConn();
                PreparedStatement st = conn.prepareStatement(
                    "merge into serverLatency key(tld,ip) values (?,?,?,?,?)"
                );
                st.setString(1, tld.toString(true));
                st.setString(2, ip.getHostAddress());
                st.setInt(3, (ip.getAddress().length == 16) ? 6 : 4);
                st.setLong(4, latency);
                st.setLong(5, System.currentTimeMillis());
                st.execute();
                st.close();

                logger.debug("Stored latency for {}/{}: {}ms", tld.toString(true), ip.getHostAddress(), latency);
                return true;
            } else {
                logger.debug("Query to {}/{} returned: {}", tld.toString(true), ip.getHostAddress(),
                    Rcode.string(response.getHeader().getRcode()));
            }
        } catch (Exception e) {
            logger.debug("Failed to test {}/{}: {}", tld.toString(true), ip.getHostAddress(), e.getMessage());
        }
        return false;
    }
}
