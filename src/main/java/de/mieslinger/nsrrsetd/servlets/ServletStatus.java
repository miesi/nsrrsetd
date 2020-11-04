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

import de.mieslinger.nsrrsetd.Main;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *
 * @author mieslingert
 */
public class ServletStatus extends HttpServlet {

    /**
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code>
     * methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        PrintWriter out = response.getWriter();
        Connection c = Main.getDbConn();
        try {
            long startTs = System.currentTimeMillis();

            response.setContentType("text/plain;charset=UTF-8");

            if (Main.tldCacheComplete()) {
                out.println("Status: OK");
            } else {
                out.println("Status: Warming Up");
            }

            out.println("queueDelegation: " + Main.getQDSize());
            out.println("queueALookup: " + Main.getQASize());
            out.println("queueAAAALookup: " + Main.getQAAAASize());
            out.println("queueDNSCheck: " + Main.getQDNSSize());
            PreparedStatement st = c.prepareStatement("select count(*) from serverLatency");
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                out.format("%s: %d\n", "Cache size", rs.getInt(1));
            }
            rs.close();
            st.close();
            st = c.prepareStatement("select min(lastUpdated) from serverLatency");
            rs = st.executeQuery();
            while (rs.next()) {
                out.format("%s: %d\n", "Oldest Cache Entry", rs.getLong(1));
            }
            rs.close();
            st.close();
            out.println("RemoteAddress: " + request.getRemoteAddr());
            out.println("Generated at: " + new Date().toString());
            out.println("Total generation time: " + (System.currentTimeMillis() - startTs) + "ms");
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
