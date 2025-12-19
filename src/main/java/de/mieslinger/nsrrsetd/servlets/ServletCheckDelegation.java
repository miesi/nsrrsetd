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
package de.mieslinger.nsrrsetd.servlets;

import com.google.gson.Gson;
import com.google.common.net.InternetDomainName;
import de.mieslinger.nsrrsetd.Main;
import de.mieslinger.nsrrsetd.transfer.DelegationCheckRequest;
import de.mieslinger.nsrrsetd.transfer.DelegationCheckResponse;
import de.mieslinger.nsrrsetd.util.PublicSuffixPopulator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 * Servlet for checking zone delegation status
 * @author mieslingert
 */
public class ServletCheckDelegation extends HttpServlet {

    private final Logger logger = LoggerFactory.getLogger(ServletCheckDelegation.class);
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        long startTs = System.currentTimeMillis();

        try {
            // Parse JSON request
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String requestBody = sb.toString();
            DelegationCheckRequest req = gson.fromJson(requestBody, DelegationCheckRequest.class);

            if (req == null || req.getZone() == null || req.getNsRrSet() == null || req.getNsRrSet().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.println("{\"error\":\"Invalid request. Provide zone and nsRrSet.\"}");
                return;
            }

            // Normalize zone name and extract TLD using Public Suffix List
            String zoneStr = req.getZone();
            if (zoneStr.endsWith(".")) {
                zoneStr = zoneStr.substring(0, zoneStr.length() - 1);
            }

            Name zone = new Name(zoneStr + ".");
            String tld;

            try {
                InternetDomainName domain = InternetDomainName.from(zoneStr);
                if (domain.hasPublicSuffix()) {
                    tld = domain.publicSuffix().toString();
                } else {
                    // Fallback to old behavior if no public suffix found
                    int numLabels = zone.labels();
                    tld = zone.getLabelString(numLabels - 2);
                }
            } catch (IllegalArgumentException e) {
                // Fallback to old behavior if domain parsing fails
                int numLabels = zone.labels();
                tld = zone.getLabelString(numLabels - 2);
            }

            // Normalize expected NS names
            Set<String> expectedNsSet = new HashSet<>();
            for (String ns : req.getNsRrSet()) {
                String normalized = ns.toLowerCase();
                if (!normalized.endsWith(".")) {
                    normalized += ".";
                }
                expectedNsSet.add(normalized);
            }

            DelegationCheckResponse resp = new DelegationCheckResponse();
            resp.setExpectedNsRrSet(new ArrayList<>(expectedNsSet));

            // Find fastest server for TLD
            // For third-level public suffixes (co.uk, gov.uk), try the suffix first,
            // then fall back to parent TLD (uk) if not found in root zone
            Connection c = Main.getDbConn();
            String bestIP = null;
            String queryTld = tld;

            PreparedStatement st = c.prepareStatement(
                "select ip from serverLatency where tld = ? order by latency limit 1"
            );
            st.setString(1, queryTld);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                bestIP = rs.getString(1);
                resp.setQueriedServer(bestIP);
            }

            // If no server found for public suffix, try to populate it from parent TLD
            if (bestIP == null && queryTld.contains(".")) {
                String parentTld = queryTld.substring(queryTld.indexOf('.') + 1);
                logger.info("Public suffix {} not in cache, attempting to populate from parent TLD {}", queryTld, parentTld);

                // Try to dynamically populate the public suffix data
                if (PublicSuffixPopulator.populatePublicSuffix(queryTld, parentTld)) {
                    // Retry query after population
                    st.setString(1, queryTld);
                    rs = st.executeQuery();
                    if (rs.next()) {
                        bestIP = rs.getString(1);
                        resp.setQueriedServer(bestIP);
                        logger.info("Successfully populated and using public suffix: {}", queryTld);
                    }
                }

                // If still no server, fall back to parent TLD
                if (bestIP == null) {
                    st.setString(1, parentTld);
                    rs = st.executeQuery();
                    if (rs.next()) {
                        bestIP = rs.getString(1);
                        resp.setQueriedServer(bestIP);
                        queryTld = parentTld;
                        logger.debug("Falling back to parent TLD {} instead of {} (population failed)", parentTld, tld);
                    }
                }
            }

            rs.close();
            st.close();

            if (bestIP == null) {
                resp.setStatus("QueryFailed");
                resp.setDiagnosticMessage("No known authoritative server for TLD: " + tld + " or parent TLDs");
                out.println(gson.toJson(resp));
                return;
            }

            // Query authoritative server
            SimpleResolver resolver = new SimpleResolver(bestIP);
            resolver.setTimeout(Duration.ofMillis(Main.getDnsTimeoutMillis()));

            Record question = Record.newRecord(zone, Type.NS, DClass.IN);
            Message query = Message.newQuery(question);
            query.getHeader().unsetFlag(Flags.RD); // Query without recursion

            long begin = System.currentTimeMillis();
            Message dnsResponse;

            try {
                dnsResponse = resolver.send(query);
            } catch (Exception e) {
                resp.setStatus("QueryFailed");
                resp.setDiagnosticMessage("DNS query failed: " + e.getMessage());
                resp.setQueryTime(System.currentTimeMillis() - begin);
                out.println(gson.toJson(resp));
                return;
            }

            long end = System.currentTimeMillis();
            resp.setQueryTime(end - begin);

            int rcode = dnsResponse.getHeader().getRcode();

            if (rcode == Rcode.NXDOMAIN) {
                resp.setStatus("NotDelegated");
                resp.setDiagnosticMessage("Zone does not exist (NXDOMAIN)");
                out.println(gson.toJson(resp));
                return;
            }

            if (rcode != Rcode.NOERROR) {
                resp.setStatus("QueryFailed");
                resp.setDiagnosticMessage("Query returned: " + Rcode.string(rcode));
                out.println(gson.toJson(resp));
                return;
            }

            // Extract NS records from response
            Set<String> actualNsSet = new HashSet<>();
            Record[] answers = dnsResponse.getSectionArray(Section.ANSWER);
            Record[] authority = dnsResponse.getSectionArray(Section.AUTHORITY);

            // Check both ANSWER and AUTHORITY sections
            for (Record r : answers) {
                if (r.getType() == Type.NS && r.getName().equals(zone)) {
                    NSRecord nsr = (NSRecord) r;
                    String nsName = nsr.getTarget().toString().toLowerCase();
                    actualNsSet.add(nsName);
                }
            }

            for (Record r : authority) {
                if (r.getType() == Type.NS && r.getName().equals(zone)) {
                    NSRecord nsr = (NSRecord) r;
                    String nsName = nsr.getTarget().toString().toLowerCase();
                    actualNsSet.add(nsName);
                }
            }

            resp.setActualNsRrSet(new ArrayList<>(actualNsSet));

            // Determine status
            if (actualNsSet.isEmpty()) {
                resp.setStatus("NotDelegated");
                resp.setDiagnosticMessage("No NS records found for zone");
            } else if (actualNsSet.equals(expectedNsSet)) {
                resp.setStatus("OK");
            } else {
                // Check if there's any overlap (intersection)
                Set<String> intersection = new HashSet<>(actualNsSet);
                intersection.retainAll(expectedNsSet);

                if (intersection.isEmpty()) {
                    // No overlap at all - completely different NS sets
                    resp.setStatus("NotDelegatedToNsRrSet");
                    resp.setDiagnosticMessage(
                        "Zone is delegated to completely different nameservers. " +
                        "Expected: " + expectedNsSet + ", Found: " + actualNsSet
                    );
                } else {
                    // There's some overlap - partly delegated
                    resp.setStatus("PartlyDelegated");
                    Set<String> missing = new HashSet<>(expectedNsSet);
                    missing.removeAll(actualNsSet);
                    Set<String> extra = new HashSet<>(actualNsSet);
                    extra.removeAll(expectedNsSet);

                    if (!missing.isEmpty() && !extra.isEmpty()) {
                        resp.setDiagnosticMessage("Delegation mismatch. Missing: " + missing + ", Extra: " + extra);
                    } else if (!missing.isEmpty()) {
                        resp.setDiagnosticMessage("Delegation is missing NS records: " + missing);
                    } else {
                        resp.setDiagnosticMessage("Delegation contains extra NS records: " + extra);
                    }
                }
            }

            out.println(gson.toJson(resp));

        } catch (Exception e) {
            logger.error("Error processing delegation check", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.println("{\"error\":\"Internal server error: " + e.getMessage() + "\"}");
        } finally {
            out.close();
        }
    }

    @Override
    public String getServletInfo() {
        return "Check zone delegation status";
    }
}
