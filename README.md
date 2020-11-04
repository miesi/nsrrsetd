# nsrrsetd

Inspired by DNS Standheizung, this is a combined daemon to
- warm up the cache of the configured resolver with all delegations found in the root-zone
- query all Nameserver IPs of the root-zone directly and record query-time (can be disabled)
- start a jetty to display contents of the Cache containing tld, IP and latency (can be disabled)
- JSON REST API to get the delegating NS RRSet of a second level zone

## Notes
- third level domains like .co.uk are correctly handled
- third level domains like de.com are NOT correctly handled

## "API"

/getDelegatingNSSet/<String>
----------------------------
`curl http://localhost:8989/getDelegatingNSSet/mieslinger.de`

```
{"queryTime":17,
 "status":"NOERROR",
 "rrSet":["ns-anyslv.ui-dns.biz","ns-anyslv.ui-dns.com","ns-anyslv.ui-dns.org","ns-anyslv.ui-dns.de"],
 "queriedServer":"194.146.107.6"
}
```

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

[Maven](https://maven.apache.org/)

[Java](http://openjdk.java.net/)

On debian based Systems
```
apt install maven openjdk-11-jre-headless
```

### Building

```
cd nsrrsetd/
mvn package
```

## Running

Starting with default values
----------------------------
```
java -jar nsrrsetd-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Help
----
```
java -jar nsrrsetd-1.0-SNAPSHOT-jar-with-dependencies.jar --help

    @Argument(alias = "s", description = "AXFR source for '.' Zone")
    private static String axfrSource = "iad.xfr.dns.icann.org";

    @Argument(alias = "r", description = "Resolver to query")
    private static String resolverToWarm = "10.2.215.21";

    @Argument(alias = "nt", description = "Number of Threads for NS lookups (6 by default)")
    private static int numThreadsNSLookup = 12;

    @Argument(alias = "at", description = "Number of Threads for A lookups (12 by default)")
    private static int numThreadsALookup = 50;

    @Argument(alias = "aaaat", description = "Number of Threads for AAAA lookups (12 by default, 0 for disable)")
    private static int numThreadsAAAALookup = 50;

    @Argument(alias = "dnst", description = "Number of Threads for DNS Check (50 default, 0 for disable))")
    private static int numThreadsDNSCheck = 100;

    @Argument(alias = "t", description = "resolver timeout (4 seconds default)")
    private static int timeout = 4;

    @Argument(alias = "a", description = "retransfer root zone after n seconds (86400 default)")
    private static int rootZoneMaxAge = 86400;

    @Argument(alias = "bc", description = "background checking of NS/A/AAAA every n seconds (1200s default)")
    private static int backgroundCheck = 1200;

    @Argument(alias = "he", description = "http enabled (default true)")
    private static boolean httpEnabled = true;

    @Argument(alias = "hp", description = "http port (default 8989)")
    private static int httpPort = 8989;
```

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

