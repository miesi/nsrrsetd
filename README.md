# nsrrsetd

Inspired by DNS Standheizung, this is a combined daemon to:
- Warm up the cache of configured resolver(s) with all delegations found in the root zone
- Query all nameserver IPs of the root zone directly and record query times (can be disabled)
- Start a Jetty HTTP server to display cache contents: TLD, IP, and latency (can be disabled)
- Provide JSON REST API to get the delegating NS RRSet of zones (only available when HTTP is enabled)
- **NEW in 2.2:** Automatically populate third-level public suffix nameserver data (co.uk, gov.uk, gv.at, etc.)
- **NEW in 2.2:** HikariCP connection pooling for improved database performance
- **NEW in 2.2:** Configurable DNS timeout and Jetty thread pool

## Features

### Third-Level Domain Support
- ✅ Third-level public suffixes like `.co.uk`, `.gov.uk`, `.gv.at`, `.co.nz` are **correctly handled** using the Public Suffix List
- 🔄 Automatically populates nameserver data for third-level domains on first query
- 📊 Keeps recursor cache "hot" by directly querying authoritative servers
- ⚠️ Corporate third-level domains like `de.com` are NOT handled (not in Public Suffix List)

### Performance & Scalability
- 🚀 HikariCP connection pooling for H2 in-memory database
- ⚙️ Configurable DNS query timeout (default: 2000ms)
- 🧵 Configurable Jetty thread pool (default: 200 threads)
- 📦 Supports running multiple instances on same server (one per recursor)

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

```bash
git clone https://github.com/miesi/nsrrsetd
cd nsrrsetd/
```

### Prerequisites

**Java 25 LTS is required**

- [Maven](https://maven.apache.org/) (3.6+)
- [Java 25 LTS](http://openjdk.java.net/)

#### On Fedora/RHEL/Alma/Rocky:
```bash
sudo dnf install maven java-25-openjdk-devel
```

#### On Debian/Ubuntu:
```bash
sudo apt install maven openjdk-25-jdk
```

### Building

#### Standard Build (using system Java)

If your system Java is set to Java 25:
```bash
mvn clean package
```

#### Building with Java 25 when system Java is different

If your system uses a different Java version (e.g., Java 21 or 17), you must explicitly set `JAVA_HOME` to Java 25:

```bash
# On Fedora/RHEL/Alma/Rocky:
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
mvn clean package

# On Debian/Ubuntu:
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn clean package

# Or as a one-liner:
JAVA_HOME=/usr/lib/jvm/java-25-openjdk mvn clean package
```

**Check your Java version:**
```bash
java -version
mvn --version
```

**Find Java 25 installation:**
```bash
# Fedora/RHEL:
ls -la /usr/lib/jvm/java-25-openjdk

# Debian/Ubuntu:
update-java-alternatives --list
```

#### Build Output

The build produces in `target/`:
- `nsrrsetd-2.2.jar` - Base JAR
- `nsrrsetd-2.2-jar-with-dependencies.jar` - Standalone executable JAR with all dependencies
- `nsrrsetd-2.2-1.noarch.rpm` - RPM package for RHEL/Fedora/Alma/Rocky

The standalone JAR includes:
- Gson (compiled from source with Java 25)
- Guava 33.8.0-jre (Public Suffix List support)
- HikariCP 7.1.0 (connection pooling)
- dnsjava 3.7.1
- Jetty 12.1.14
- H2 2.5.252 (in-memory latency store)
- All other dependencies

## Installation

### From RPM (Recommended)

**RHEL 8+, Fedora, Alma, Rocky, Oracle Linux:**
```bash
sudo dnf install nsrrsetd-2.2-1.noarch.rpm
```

The RPM automatically:
- Creates `nsrrsetd` user (system account)
- Creates `/var/lib/nsrrsetd` working directory
- Installs systemd unit file at `/usr/lib/systemd/system/nsrrsetd@.service`
- Installs configuration at `/etc/sysconfig/nsrrsetd`
- Installs documentation at `/opt/nsrrsetd/docs/`

### From JAR

Copy the standalone JAR to your preferred location:
```bash
sudo mkdir -p /opt/nsrrsetd
sudo cp target/nsrrsetd-2.2-jar-with-dependencies.jar /opt/nsrrsetd/
```

## Running

### Standalone Mode

**With default settings:**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar
```

**With HTTP API enabled:**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --http-enabled \
  --http-port 8989 \
  --resolver 8.8.8.8
```

**Help:**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar --help
```

### As a systemd Service (Recommended)

The RPM includes a systemd **instance unit** that supports running multiple instances on the same server (one per DNS recursor).

**Start a single instance:**
```bash
# Start instance for recursor at 8.8.8.8
sudo systemctl start nsrrsetd@8.8.8.8

# Enable on boot
sudo systemctl enable nsrrsetd@8.8.8.8

# Check status
sudo systemctl status nsrrsetd@8.8.8.8

# View logs
sudo journalctl -u nsrrsetd@8.8.8.8 -f
```

**Start multiple instances (keep multiple recursors warm):**
```bash
# Start instances for different recursors
sudo systemctl start nsrrsetd@192.168.1.1
sudo systemctl start nsrrsetd@192.168.1.2
sudo systemctl start nsrrsetd@8.8.8.8

# Enable all on boot
sudo systemctl enable nsrrsetd@192.168.1.1
sudo systemctl enable nsrrsetd@192.168.1.2
sudo systemctl enable nsrrsetd@8.8.8.8

# View status of all instances
sudo systemctl status 'nsrrsetd@*'
```

**Configuration:**
- Global config: `/etc/sysconfig/nsrrsetd`
- Per-instance config: `/etc/sysconfig/nsrrsetd@INSTANCE` (optional)

See `/opt/nsrrsetd/docs/README-INSTANCES.md` for complete documentation on running multiple instances.

### Running on Port 80

The systemd unit includes `AmbientCapabilities=CAP_NET_BIND_SERVICE` to allow binding to port 80 without running as root.

**Edit `/etc/sysconfig/nsrrsetd`:**
```bash
NSRRSETARGS="--http-enabled --http-port 80 --dns-timeout 2000 --jetty-threads 300"
```

Then restart:
```bash
sudo systemctl restart nsrrsetd@8.8.8.8
```

See `/opt/nsrrsetd/docs/PORT80-SETUP.md` for alternative methods and troubleshooting.

## Command Line Options

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
| `-dt` | `--dns-timeout` | DNS query timeout in milliseconds | 2000 |
| `-jt` | `--jetty-threads` | Jetty HTTP thread pool size | 200 |
| `-a` | `--root-zone-max-age` | Retransfer root zone after n seconds | 86400 |
| `-bc` | `--background-check` | Background checking of NS/A/AAAA every n seconds | 1200 |
| `-he` | `--http-enabled` | Enable HTTP server | false |
| `-hp` | `--http-port` | HTTP server port | 8989 |
| `-h` | `--help` | Show help message and exit | - |
| `-V` | `--version` | Print version information and exit | - |

### Examples

**Basic usage with HTTP API:**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --resolver 8.8.8.8 \
  --http-enabled \
  --http-port 8080
```

**Production settings (1000+ req/s):**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --resolver 192.168.1.1 \
  --http-enabled \
  --http-port 80 \
  --dns-timeout 3000 \
  --jetty-threads 300
```

**Cache warmer only (no HTTP API, no DNS checks):**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --resolver 8.8.8.8 \
  --dns-check-threads 0
```

**Custom AXFR source and thread settings:**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --axfr-source iad.xfr.dns.icann.org \
  --ns-threads 6 \
  --a-threads 25
```

**Disable AAAA lookups (IPv4 only):**
```bash
java -jar nsrrsetd-2.2-jar-with-dependencies.jar \
  --aaaa-threads 0 \
  --resolver 8.8.8.8
```

### AXFR Source Notes

If you don't trust `ns-anyslv.ui-dns.com`, you can use ICANN's AXFR service:
- `iad.xfr.dns.icann.org` (Washington DC)
- `lax.xfr.dns.icann.org` (Los Angeles)

**Note:** ICANN AXFR servers have rate limits. See [icann.org/czds](https://www.icann.org/resources/pages/czds-2014-03-18-en) for details.

## Performance

### Capacity

With default settings, nsrrsetd can handle:
- **Jetty:** 240,000+ req/s theoretical (actual depends on query complexity)
- **Recommended for production:** 1,000 req/s sustained load
- **Bottleneck:** DNS query latency to authoritative servers

Tune for higher throughput:
```bash
--dns-timeout 1000 --jetty-threads 300
```

### Database

- **H2 in-memory database** for latency cache
- **HikariCP connection pooling** (10 connections, 2 min idle)
- **Fast queries:** TLD lookups indexed on `(tld, latency)`

### Third-Level Domain Population

When you query a domain with a third-level public suffix (e.g., `example.co.uk`):
1. First query detects "co.uk" is not in cache
2. Automatically queries .uk nameservers for co.uk NS records
3. Resolves A/AAAA for each co.uk nameserver
4. Tests latency and stores in database
5. Future queries use cached co.uk nameserver data

This keeps your recursor cache "hot" for common third-level domains.

## Additional API Endpoints

### /statistics
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

## Documentation

After RPM installation, documentation is available at:
- `/opt/nsrrsetd/docs/INSTALL.md` - Installation guide
- `/opt/nsrrsetd/docs/PORT80-SETUP.md` - Running on port 80 (privilege options)
- `/opt/nsrrsetd/docs/README-INSTANCES.md` - Running multiple instances

Or in the source repository:
- `src/main/sysconfig/INSTALL.md`
- `src/main/sysconfig/PORT80-SETUP.md`
- `src/main/sysconfig/README-INSTANCES.md`

## Troubleshooting

### DNS Timeout Errors

If you see many timeout errors in logs:
```bash
# Increase DNS timeout
--dns-timeout 5000
```

### Out of Memory

Adjust JVM memory via systemd config `/etc/sysconfig/nsrrsetd`:
```bash
JAVAARGS="-Xmx4g -Xms256m"
```

### Too Many Open Files

The systemd unit sets `LimitNOFILE=65536`. If you need more:
```bash
# Edit unit file
sudo systemctl edit nsrrsetd@8.8.8.8

# Add override
[Service]
LimitNOFILE=131072
```

### Check Service Status

```bash
# View status
sudo systemctl status nsrrsetd@8.8.8.8

# View logs
sudo journalctl -u nsrrsetd@8.8.8.8 -f

# Check resource usage
sudo systemctl status 'nsrrsetd@*' | grep -E '(Memory|Tasks)'
```

## Authors

* **Thomas Mieslinger** - IONOS SE

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test with Java 25
5. Submit a pull request

## Support

For issues and questions:
- GitHub Issues: [https://github.com/miesi/nsrrsetd/issues](https://github.com/miesi/nsrrsetd/issues)
- Documentation: `/opt/nsrrsetd/docs/` (after RPM install)

