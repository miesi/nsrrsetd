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
package de.mieslinger.nsrrsetd.transfer;

import java.util.List;

/**
 * Response object for delegation check API
 * @author mieslingert
 */
public class DelegationCheckResponse {
    private long queryTime;
    private String queriedServer;
    private String status; // OK, NotDelegated, PartlyDelegated, NotDelegatedToNsRrSet, QueryFailed
    private String diagnosticMessage;
    private List<String> actualNsRrSet;
    private List<String> expectedNsRrSet;

    public long getQueryTime() {
        return queryTime;
    }

    public void setQueryTime(long queryTime) {
        this.queryTime = queryTime;
    }

    public String getQueriedServer() {
        return queriedServer;
    }

    public void setQueriedServer(String queriedServer) {
        this.queriedServer = queriedServer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDiagnosticMessage() {
        return diagnosticMessage;
    }

    public void setDiagnosticMessage(String diagnosticMessage) {
        this.diagnosticMessage = diagnosticMessage;
    }

    public List<String> getActualNsRrSet() {
        return actualNsRrSet;
    }

    public void setActualNsRrSet(List<String> actualNsRrSet) {
        this.actualNsRrSet = actualNsRrSet;
    }

    public List<String> getExpectedNsRrSet() {
        return expectedNsRrSet;
    }

    public void setExpectedNsRrSet(List<String> expectedNsRrSet) {
        this.expectedNsRrSet = expectedNsRrSet;
    }
}
