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
package de.mieslinger.nsrrsetd.servlets;

import com.google.gson.Gson;
import de.mieslinger.nsrrsetd.Main;
import de.mieslinger.nsrrsetd.transfer.QueryResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/**
 *
 * @author mieslingert
 */
public class ServletGetDelegatingNSSet extends HttpServlet {

    /**
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code>
     * methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    private final Logger logger = LoggerFactory.getLogger(ServletGetDelegatingNSSet.class);

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        long startTs = System.currentTimeMillis();

        PrintWriter out = response.getWriter();
        Connection c = Main.getDbConn();
        String bestIP = null;
        QueryResult qr = new QueryResult();
        boolean debug = true;

        try {
            String strDebug = request.getParameter("debug");
            if (strDebug == null) {
                debug = false;
            }

            String zoneStr = request.getPathInfo().substring(1);
            logger.debug("zoneStr: {}", zoneStr);

            Name zone = new Name(zoneStr);
            if (!zone.isAbsolute()) {
                zone = new Name(zoneStr + ".");
            }

            logger.debug("zone: {}", zone);

            int numLabels = zone.labels();
            logger.debug("numLabels: {}", numLabels);

            /*for (int i = 0; i <= numLabels; i++) {
                logger.debug("label({}): {}", i, zone.getLabelString(i));
            }*/
            String tld = zone.getLabelString(numLabels - 2);

            logger.debug("TLD: {}", tld);

            TreeMap<Long, String[]> servers = new TreeMap<Long, String[]>();

            PreparedStatement st = c.prepareStatement("select tld, ip, latency"
                    + " from serverLatency"
                    + " where tld = ?"
                    + " order by latency");
            st.setString(1, tld);
            ResultSet rs = st.executeQuery();
            int i = 1;
            while (rs.next()) {
                if (i == 1) {
                    bestIP = rs.getString(2);
                    qr.setQueriedServer(bestIP);
                }
                String[] server = new String[3];
                server[0] = rs.getString(1);
                server[1] = rs.getString(2);
                Long l = rs.getLong(3);
                server[2] = l.toString();
                servers.put(l, server);
                i++;
            }
            rs.close();
            st.close();
            logger.debug("BestIP: {}", bestIP);

            SimpleResolver r = new SimpleResolver(bestIP);
            r.setTimeout(Duration.ofSeconds(20));

            int type = Type.NS;

            Record question = Record.newRecord(zone, type, DClass.IN);
            Message query = Message.newQuery(question);
            Message dnsResponse;
            long begin = System.currentTimeMillis();

            boolean timedout = false;
            boolean networkerror = false;
            boolean badresponse = false;
            String badresponse_error;
            SetResponse sr;

            try {
                dnsResponse = r.send(query);
            } catch (Exception e) {
                logger.debug(
                        "Lookup for {}/{}, id={} failed using server {}",
                        zone,
                        Type.string(query.getQuestion().getType()),
                        query.getHeader().getID(),
                        r,
                        e);

                // A network error occurred.  Press on.
                if (e instanceof InterruptedIOException) {
                    timedout = true;
                    qr.setStatus("Error");
                    qr.setDiagnostics("Timed Out");

                } else {
                    networkerror = true;
                    qr.setStatus("Error");
                    qr.setDiagnostics("Network Error");

                }
                return;
            }
            long end = System.currentTimeMillis();
            long latency = end - begin;
            qr.setQueryTime(latency);

            int rcode = dnsResponse.getHeader().getRcode();
            if (rcode != Rcode.NOERROR && rcode != Rcode.NXDOMAIN) {
                // The server we contacted is broken or otherwise unhelpful.
                // Press on.
                badresponse = true;
                badresponse_error = Rcode.string(rcode);

                qr.setStatus("Error");
                qr.setDiagnostics(Rcode.string(rcode));
            } else {
                qr.setStatus(Rcode.string(rcode));
            }

            if (!query.getQuestion().equals(dnsResponse.getQuestion())) {
                // The answer doesn't match the question.  That's not good.
                badresponse = true;
                badresponse_error = "response does not match query";

                qr.setStatus("Error");
                qr.setDiagnostics("response does not match query");
            }

            Cache cache = new Cache();
            sr = cache.addMessage(dnsResponse);
            if (sr.isSuccessful() || sr.isDelegation()) {
                qr.setNsServers(sr);
            }

            /*
            public class QueryResult {

             private long queryTime;
             private String status; // NXDOMAIN; NOERROR; Error
             private String[] nsServers; // NULL if NXDOMAIN or Error
             private String queriedServer;
             private String diagnostics;
             */
            if (debug) {
                // HTML output
                response.setContentType("text/html;charset=UTF-8");

                out.println("<html>");
                out.println("<head>");
                out.println("<title>Servlet Delegating NS RRSet");
                out.println("</title>");
                out.println("<body>");

                out.println("request.getPathInfo(): " + zoneStr + "<br>");

                out.println("<h1>Cache content for " + tld + "</h1>");
                out.println("<table>");
                for (Map.Entry<Long, String[]> entry : servers.entrySet()) {
                    String[] server = entry.getValue();
                    out.format("<tr><td>%s</td><td>%s</td><td>%s</td></tr>\n", server[0], server[1], server[2]);
                }
                out.println("</table>");

                // check bestIP not null
                out.println("<h1>Delegating NS RRSet for " + zone.toString(true) + "</h1>");
                out.format("Query NS Records for zone %s from server %s<br>", zone.toString(true), bestIP);
                out.format("Queried %s/%s, id=%d: %s<br>\n", zone, Type.string(type), dnsResponse.getHeader().getID(), sr);

                out.println("Query took " + latency + "ms");
                out.println("<hr>");
                out.println("Session und Connection Information:<br>");
                out.println("RemoteAddress: " + request.getRemoteAddr());
                out.println("<hr>");
                out.println("Generated at: " + new Date().toString() + "<br>");
                out.println("Total generation time: " + (System.currentTimeMillis() - startTs) + "ms<br>");
                out.println("</body>");
                out.println("</html>");
            } else {
                // return JSON
                String qrJsonString = new Gson().toJson(qr);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                out.print(qrJsonString);

            }
        } catch (Exception e) {
            out.println(e.toString());
            e.printStackTrace();
        } finally {
            out.close();
        }
    }

// <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Handles the HTTP <code>POST</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Short description";
    }// </editor-fold>
}
