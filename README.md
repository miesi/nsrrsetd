# nsrrsetd

Inspired by DNS Standheizung, this is a combined daemon to
- warm up the cache of the configured resolver with all delegations found in the root-zone
- query all Nameserver IPs of the root-zone directly and record query-time (can be disabled)
- start a jetty to display contents of the Cache containing tld, IP and latency (can be disabled)
- JSON REST API to get the delegating NS RRSet of a second level zone (only available when jetty is enabled)

## Notes
- third level domains like .co.uk are correctly handled
- third level domains like de.com are NOT correctly handled

## "API"

/getDelegatingNSSet/<String>
----------------------------
Get the NS records delegated for a zone from the parent zone.

`curl http://localhost:8989/getDelegatingNSSet/mieslinger.de`

```json
{"queryTime":17,
 "status":"NOERROR",
 "rrSet":["ns-anyslv.ui-dns.biz","ns-anyslv.ui-dns.com","ns-anyslv.ui-dns.org","ns-anyslv.ui-dns.de"],
 "queriedServer":"194.146.107.6"
}
```

/checkDelegation (POST)
-----------------------
Check if a zone is properly delegated with the expected NS records. Returns detailed status and diagnostics.

**Request:**
```bash
curl -X POST http://localhost:8989/checkDelegation \
  -H "Content-Type: application/json" \
  -d '{
    "zone": "mieslinger.de",
    "nsRrSet": [
      "ns-anyslv.ui-dns.biz",
      "ns-anyslv.ui-dns.com",
      "ns-anyslv.ui-dns.org",
      "ns-anyslv.ui-dns.de"
    ]
  }'
```

**Response (Status: OK):**
```json
{
  "queryTime": 23,
  "queriedServer": "194.146.107.6",
  "status": "OK",
  "diagnosticMessage": null,
  "actualNsRrSet": [
    "ns-anyslv.ui-dns.biz.",
    "ns-anyslv.ui-dns.com.",
    "ns-anyslv.ui-dns.org.",
    "ns-anyslv.ui-dns.de."
  ],
  "expectedNsRrSet": [
    "ns-anyslv.ui-dns.biz.",
    "ns-anyslv.ui-dns.com.",
    "ns-anyslv.ui-dns.org.",
    "ns-anyslv.ui-dns.de."
  ]
}
```

**Response (Status: NotDelegated):**
```json
{
  "queryTime": 18,
  "queriedServer": "194.146.107.6",
  "status": "NotDelegated",
  "diagnosticMessage": "Zone does not exist (NXDOMAIN)",
  "actualNsRrSet": [],
  "expectedNsRrSet": ["ns1.example.com.", "ns2.example.com."]
}
```

**Response (Status: PartlyDelegated):**
```json
{
  "queryTime": 25,
  "queriedServer": "194.146.107.6",
  "status": "PartlyDelegated",
  "diagnosticMessage": "Delegation is missing NS records: [ns4.example.com.]",
  "actualNsRrSet": ["ns1.example.com.", "ns2.example.com.", "ns3.example.com."],
  "expectedNsRrSet": ["ns1.example.com.", "ns2.example.com.", "ns3.example.com.", "ns4.example.com."]
}
```

**Response (Status: NotDelegatedToNsRrSet):**
```json
{
  "queryTime": 21,
  "queriedServer": "194.146.107.6",
  "status": "NotDelegatedToNsRrSet",
  "diagnosticMessage": "Delegation mismatch. Missing: [ns1.example.com., ns2.example.com.], Extra: [ns-old1.example.org., ns-old2.example.org.]",
  "actualNsRrSet": ["ns-old1.example.org.", "ns-old2.example.org."],
  "expectedNsRrSet": ["ns1.example.com.", "ns2.example.com."]
}
```

**Status values:**
- `OK`: Zone is delegated exactly as expected
- `NotDelegated`: Zone does not exist (NXDOMAIN) or has no NS records
- `PartlyDelegated`: Zone is delegated with some overlap - has missing and/or extra NS records
- `NotDelegatedToNsRrSet`: Zone is delegated to completely different nameservers (no overlap at all)
- `QueryFailed`: DNS query failed (timeout, network error, etc.)

/status
-------
```
Status: OK
queueDelegation: 0
queueALookup: 0
queueAAAALookup: 0
queueDNSCheck: 0
Cache size: 38
Oldest Cache Entry: 1604505888514
RemoteAddress: [0:0:0:0:0:0:0:1]
Generated at: Wed Nov 04 17:05:18 CET 2020
Total generation time: 18ms
```

## Getting Started

```
git clone https://github.com/miesi/nsrrsetd
```

### Prerequisites

**Java 21 or higher is required**

[Maven](https://maven.apache.org/)

[Java](http://openjdk.java.net/)

On debian based Systems
```
apt install maven openjdk-21-jre-headless
```

### Building

```
git clone https://github.com/miesi/nsrrsetd
cd nsrrsetd/
mvn clean install
```

produces in `target/` the .jar, jar with dependencies and a rpm.

## Installation

EL8+ (and alma, rocky, oracle, redhat, ...)
----------------------------------------
```
dnf install nsrrsetd-2.0-1.noarch.rpm
```

## Running

Starting with default values
----------------------------
```
java -jar nsrrsetd-2.0-jar-with-dependencies.jar
```

As a daemon
-----------
The rpm comes with a unit file. To enable and start nsrrsetd like this:
```
systemctl enable nsrrsetd@ins.example.com
systemctl start nsrrsetd@ins.example.com
```
Starts to query ins.example.com. Make sure that ins.example.com can be resolved
at unit start time. (adjust `After=` parameter and submit Pull Request)

Help
----
```
java -jar nsrrsetd-2.0-jar-with-dependencies.jar --help
```

Command Line Options
--------------------

| Short | Long | Description | Default |
|-------|------|-------------|---------|
| `-s` | `--axfr-source` | AXFR source for '.' Zone | ns-anyslv.ui-dns.com |
| `-sr` | `--axfr-retries` | AXFR retries | 3 |
| `-r` | `--resolver` | Resolver to query | 10.2.215.21 |
| `-nt` | `--ns-threads` | Number of Threads for NS lookups | 12 |
| `-at` | `--a-threads` | Number of Threads for A lookups | 50 |
| `-aaaat` | `--aaaa-threads` | Number of Threads for AAAA lookups (0 to disable) | 50 |
| `-dnst` | `--dns-check-threads` | Number of Threads for DNS Check (0 to disable) | 100 |
| `-t` | `--timeout` | Resolver timeout in seconds | 4 |
| `-a` | `--root-zone-max-age` | Retransfer root zone after n seconds | 86400 |
| `-bc` | `--background-check` | Background checking of NS/A/AAAA every n seconds | 1200 |
| `-he` | `--http-enabled` | Enable HTTP server | false |
| `-hp` | `--http-port` | HTTP server port | 8989 |
| `-h` | `--help` | Show help message and exit | - |
| `-V` | `--version` | Print version information and exit | - |

Examples
--------
```bash
# Start with custom resolver and HTTP enabled
java -jar nsrrsetd-2.0-jar-with-dependencies.jar -r 8.8.8.8 -he

# Start with custom AXFR source and thread settings
java -jar nsrrsetd-2.0-jar-with-dependencies.jar -s iad.xfr.dns.icann.org -nt 6 -at 25

# Disable AAAA lookups and DNS checks
java -jar nsrrsetd-2.0-jar-with-dependencies.jar -aaaat 0 -dnst 0
```

If you don't trust `ns-anyslv.ui-dns.com` you can use the `iad.xfr.dns.icann.org` as AXFR Source. See also icann.org website.
`iad.xfr.dns.icann.org` has a low transferlimit per IP and minute.

## "API" continued
/statistics
-----------
```
$ curl http://localhost:8989/statistics
<html>
<head>
<title>Statistics Servlet
</title>
<body>
<h1>Queues</h1>
<table>
<tr><td>queueDelegation</td><td>0</td></tr>
<tr><td>queueALookup</td><td>0</td></tr>
<tr><td>queueAAAALookup</td><td>0</td></tr>
<tr><td>queueDNSCheck</td><td>0</td></tr>
</table>
<h1>Cache content</h1>
<table>
<tr><td>com</td><td>192.43.172.30</td><td>21</td></tr>
<tr><td>com</td><td>192.12.94.30</td><td>22</td></tr>
<tr><td>com</td><td>192.26.92.30</td><td>22</td></tr>
<tr><td>com</td><td>192.54.112.30</td><td>26</td></tr>
<tr><td>com</td><td>192.35.51.30</td><td>27</td></tr>
<tr><td>com</td><td>192.52.178.30</td><td>28</td></tr>
<tr><td>com</td><td>192.31.80.30</td><td>28</td></tr>
<tr><td>com</td><td>192.48.79.30</td><td>33</td></tr>
<tr><td>com</td><td>192.5.6.30</td><td>33</td></tr>
<tr><td>com</td><td>192.42.93.30</td><td>36</td></tr>
<tr><td>com</td><td>192.41.162.30</td><td>40</td></tr>
<tr><td>com</td><td>192.55.83.30</td><td>41</td></tr>
<tr><td>com</td><td>192.33.14.30</td><td>42</td></tr>
<tr><td>com</td><td>2001:503:d414:0:0:0:0:30</td><td>47</td></tr>
<tr><td>com</td><td>2001:503:d2d:0:0:0:0:30</td><td>48</td></tr>
<tr><td>com</td><td>2001:500:d937:0:0:0:0:30</td><td>49</td></tr>
<tr><td>com</td><td>2001:503:eea3:0:0:0:0:30</td><td>49</td></tr>
<tr><td>com</td><td>2001:503:39c1:0:0:0:0:30</td><td>56</td></tr>
<tr><td>com</td><td>2001:502:8cc:0:0:0:0:30</td><td>57</td></tr>
<tr><td>com</td><td>2001:501:b1f9:0:0:0:0:30</td><td>61</td></tr>
<tr><td>com</td><td>2001:502:7094:0:0:0:0:30</td><td>71</td></tr>
<tr><td>com</td><td>2001:500:856e:0:0:0:0:30</td><td>197</td></tr>
<tr><td>com</td><td>2001:503:a83e:0:0:0:2:30</td><td>210</td></tr>
<tr><td>com</td><td>2001:503:231d:0:0:0:2:30</td><td>221</td></tr>
<tr><td>com</td><td>2001:503:83eb:0:0:0:0:30</td><td>254</td></tr>
<tr><td>com</td><td>2001:502:1ca1:0:0:0:0:30</td><td>275</td></tr>
<tr><td>de</td><td>194.146.107.6</td><td>34</td></tr>
<tr><td>de</td><td>194.246.96.1</td><td>36</td></tr>
<tr><td>de</td><td>194.0.0.53</td><td>36</td></tr>
<tr><td>de</td><td>81.91.164.5</td><td>39</td></tr>
<tr><td>de</td><td>195.243.137.26</td><td>40</td></tr>
<tr><td>de</td><td>77.67.63.105</td><td>43</td></tr>
<tr><td>de</td><td>2a02:568:fe02:0:0:0:0:de</td><td>47</td></tr>
<tr><td>de</td><td>2001:67c:1011:1:0:0:0:53</td><td>59</td></tr>
<tr><td>de</td><td>2001:668:1f:11:0:0:0:105</td><td>61</td></tr>
<tr><td>de</td><td>2a02:568:0:2:0:0:0:53</td><td>62</td></tr>
<tr><td>de</td><td>2003:8:14:0:0:0:0:53</td><td>62</td></tr>
<tr><td>de</td><td>2001:678:2:0:0:0:0:53</td><td>77</td></tr>
</table>
<hr>
Session und Connection Information:<br>
RemoteAddress: [0:0:0:0:0:0:0:1]
<hr>
Generated at: Wed Nov 04 17:05:25 CET 2020<br>
Total generation time: 25ms<br>
</body>
</html>
```

## Authors

* **Thomas Mieslinger** 

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

