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

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Date;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Credibility;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
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

        PrintWriter out = response.getWriter();

        try {
            long startTs = System.currentTimeMillis();

            response.setContentType("text/html;charset=UTF-8");

            out.println("<html>");
            out.println("<head>");
            out.println("<title>Servlet Delegating NS RRSet");
            out.println("</title>");
            out.println("<body>");

            String zoneStr = request.getPathInfo().substring(1);
            out.println("request.getPathInfo(): " + zoneStr + "<br>");

            Name zone = new Name(zoneStr);

            String[] labels = zoneStr.split("\\.");
            String tld = labels[labels.length - 1];

            out.println("<h1>Cache content for " + tld + "</h1>");
            out.println("<table>");

            int i = 1;
            String bestIP = null;

            PreparedStatement st = de.mieslinger.nsrrsetd.Main.dbConn.prepareStatement("select tld, ip, latency"
                    + " from serverLatency"
                    + " where tld = ?"
                    + " order by tld, latency");
            st.setString(1, tld);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                if (i == 1) {
                    bestIP = rs.getString(2);
                }
                out.format("<tr><td>%s</td><td>%s</td><td>%d</td></tr>\n", rs.getString(1), rs.getString(2), rs.getInt(3));
                i++;
            }
            rs.close();
            st.close();
            out.println("</table>");

            // check bestIP not null
            out.println("<h1>Delegating NS RRSet for " + zone.toString(true) + "</h1>");
            out.format("Query NS Records for zone %s from server %s<br>", zone.toString(true), bestIP);
            logger.debug("Query NS Records for zone {} from server {}", zone.toString(true), bestIP);

            Lookup la = new Lookup(zone.toString(true), Type.NS, DClass.IN);
            la.setCache(de.mieslinger.nsrrsetd.Main.dnsJavaCache);
            la.setSearchPath(".");
            la.setCredibility(Credibility.NONAUTH_AUTHORITY);

            SimpleResolver r = new SimpleResolver(bestIP);
            r.setTimeout(Duration.ofSeconds(20));

            la.setResolver(r);

            long begin = System.currentTimeMillis();
            Record[] runResults = la.run();
            long end = System.currentTimeMillis();
            long latency = end - begin;

            out.println("DEBUG: runResults.length " + runResults.length + " <br>");
            for (i = 0; i < runResults.length; i++) {
                out.println("DEBUG: runResult " + i + " name: " + runResults[i].getName() + " rdatastring: " + runResults[i].rdataToString() + " <br>");
            }

            switch (la.getResult()) {
                case Lookup.SUCCESSFUL:
                    logger.debug("Query for NS Records of zone {} from server {} took {}ms", zone.toString(true), bestIP, latency);
                    out.println("Lookup.SUCCESSFUL -> NOERROR<br>");
                    out.println(zone.toString(true) + " is delegated to:<br>");
                    for (i = 0; i < la.getAnswers().length; i++) {
                        NSRecord rr = (NSRecord) la.getAnswers()[i];
                        out.println(rr.getTarget().toString(true) + "<br>");
                    }
                    break;
                case Lookup.HOST_NOT_FOUND:
                    out.println("Lookup.HOST_NOT_FOUND -> NXDOMAIN<br>");
                    logger.debug("HOST_NOT_FOUND NS RRSet for {}", zone.toString(true));
                    break;
                case Lookup.TYPE_NOT_FOUND:
                    out.println("Lookup.TYPE_NOT_FOUND -> NOERROR, but no NS Records at " + zone.toString(true) + "<br>");
                    logger.debug("TYPE_NOT_FOUND NS RRSet for {}", zone.toString(true));
                    break;
                default:
                    out.println("SERVFAIL " + la.getErrorString() + "<br>");
                    logger.warn("query NS RRSet for {} to IP {} failed!", zone.toString(true), bestIP);
                    break;
            }
            out.println("Query took " + latency + "ms");
            out.println("<hr>");
            out.println("Session und Connection Information:<br>");
            out.println("RemoteAddress: " + request.getRemoteAddr());
            out.println("<hr>");
            out.println("Generated at: " + new Date().toString() + "<br>");
            out.println("Total generation time: " + (System.currentTimeMillis() - startTs) + "ms<br>");
            out.println("</body>");
            out.println("</html>");
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
