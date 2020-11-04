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
package de.mieslinger.nsrrsetd.transfer;

import java.util.LinkedList;
import java.util.List;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.Type;

/**
 *
 * @author mieslingert
 */
public class QueryResult {

    private long queryTime;
    private String status; // NXDomain; Delegated; Error
    private List<RRset> listRRset; // NULL if NXDomain or Error
    private List<String> rrSet = new LinkedList<String>();
    private String queriedServer;
    private String diagnostics;
    private String SetResponse;

    public QueryResult() {
    }

    public void setQueryTime(long queryTime) {
        this.queryTime = queryTime;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setNsServers(SetResponse sr) {

        for (Record r : sr.getNS().rrs()) {
            System.out.format("\n\nRecord: %s\n\n", r.toString());
            if (r.getType() == Type.NS) {
                NSRecord nsr = (NSRecord) r;
                System.out.format("\n\nAddtionalName: %s\n\n", nsr.getAdditionalName().toString(true));
                System.out.format("\n\nTarget: %s\n\n", nsr.getTarget().toString(true));
                System.out.format("\n\nrdataToString: %s\n\n", nsr.rdataToString());
                System.out.format("\n\ngetName: %s\n\n", nsr.getName().toString(true));
                rrSet.add(nsr.getTarget().toString(true));
            }
        }

        for (String s : rrSet) {
            System.out.format("\n\nrrSet Record: %s\n\n", s);
        }
    }

    public void setQueriedServer(String queriedServer) {
        this.queriedServer = queriedServer;
    }

    public void setDiagnostics(String diagnostics) {
        this.diagnostics = diagnostics;
    }

    public String getStatus() {
        return status;
    }

}
