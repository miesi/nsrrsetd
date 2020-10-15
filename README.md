# nsrrsetd

Inspired by DNS Standheizung, this is a combined daemon to
- warm up the cache of the configured resolver with all delegations found in the root-zone
- query all Nameserver IPs of the root-zone directly and record query-time (can be disabled)
- start a jetty to display contents of the Cache containing tld, IP and latency (can be disabled)
- TODO: JSON REST API to get the delegating NS RRSet of a second level zone

