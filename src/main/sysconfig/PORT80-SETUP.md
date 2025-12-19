# Running nsrrsetd on Port 80

This document describes options for running nsrrsetd on port 80 (privileged port) without running the entire Java process as root.

## Option 1: systemd with AmbientCapabilities (Recommended)

Modern systemd (v229+) supports ambient capabilities, allowing the process to bind to port 80 without running as root.

**Advantages:**
- No separate wrapper needed
- Clean privilege separation
- Process runs as dedicated user
- systemd manages lifecycle

**Setup:**

1. Create service file `/etc/systemd/system/nsrrsetd.service`:

```ini
[Unit]
Description=NS RRSet Daemon
After=network.target

[Service]
Type=simple
User=nsrrsetd
Group=nsrrsetd

# Allow binding to privileged ports
AmbientCapabilities=CAP_NET_BIND_SERVICE

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/nsrrsetd

# Environment
Environment="JAVA_HOME=/usr/lib/jvm/java-25-openjdk"

# Command
ExecStart=/usr/lib/jvm/java-25-openjdk/bin/java \
    -jar /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar \
    --http-enabled \
    --http-port 80 \
    --dns-timeout 2000 \
    --jetty-threads 200 \
    --resolver 8.8.8.8

# Restart policy
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

2. Create dedicated user:
```bash
sudo useradd -r -s /bin/false -d /var/lib/nsrrsetd nsrrsetd
sudo mkdir -p /var/lib/nsrrsetd
sudo chown nsrrsetd:nsrrsetd /var/lib/nsrrsetd
```

3. Install and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable nsrrsetd
sudo systemctl start nsrrsetd
sudo systemctl status nsrrsetd
```

---

## Option 2: systemd with setcap (Alternative)

Set capabilities directly on the Java binary (less secure, affects all Java processes).

**Advantages:**
- Simple one-time setup
- Works with older systemd

**Disadvantages:**
- Applies to ALL Java processes
- Security risk if other Java apps run on same system

**Setup:**

```bash
# Grant CAP_NET_BIND_SERVICE to Java binary
sudo setcap 'cap_net_bind_service=+ep' /usr/lib/jvm/java-25-openjdk/bin/java

# Verify
getcap /usr/lib/jvm/java-25-openjdk/bin/java

# Create systemd service without AmbientCapabilities
[Service]
User=nsrrsetd
ExecStart=/usr/lib/jvm/java-25-openjdk/bin/java -jar ...
```

**Warning:** This grants port binding capability to ALL Java processes. Prefer Option 1.

---

## Option 3: authbind

authbind allows non-root users to bind to privileged ports on a per-port basis.

**Advantages:**
- Granular port control
- No capabilities needed
- Works on older systems

**Disadvantages:**
- Extra wrapper process
- Not as clean as capabilities

**Setup:**

```bash
# Install authbind
sudo dnf install authbind  # Fedora/RHEL
sudo apt install authbind   # Debian/Ubuntu

# Allow user to bind to port 80
sudo touch /etc/authbind/byport/80
sudo chmod 500 /etc/authbind/byport/80
sudo chown nsrrsetd:nsrrsetd /etc/authbind/byport/80

# Systemd service
[Service]
User=nsrrsetd
ExecStart=/usr/bin/authbind --deep \
    /usr/lib/jvm/java-25-openjdk/bin/java \
    -jar /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar \
    --http-port 80
```

---

## Option 4: iptables Port Forwarding

Forward port 80 to unprivileged port (e.g., 8080). No privilege dropping needed.

**Advantages:**
- Simple
- No privilege management
- Works on any system

**Disadvantages:**
- Clients see port 80, server binds to 8080
- Requires iptables/firewalld management

**Setup:**

```bash
# Run service on port 8080
java -jar nsrrsetd.jar --http-port 8080

# Forward 80 → 8080
sudo iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080

# Make persistent (Fedora/RHEL with firewalld)
sudo firewall-cmd --permanent --add-forward-port=port=80:proto=tcp:toport=8080
sudo firewall-cmd --reload

# Or save iptables rules
sudo iptables-save > /etc/iptables/rules.v4
```

---

## Option 5: Java Security Manager with Wrapper (Complex)

Create a setuid wrapper that starts Java, then drops privileges.

**Not recommended** - Complex, error-prone, security risks. Use Option 1 instead.

---

## Comparison Matrix

| Option | Recommended | Complexity | Security | systemd Required |
|--------|-------------|------------|----------|------------------|
| systemd AmbientCapabilities | ✅ Yes | Low | High | v229+ |
| setcap on java binary | ⚠️ OK | Very Low | Medium | No |
| authbind | ⚠️ OK | Medium | High | No |
| iptables forwarding | ⚠️ OK | Low | High | No |
| setuid wrapper | ❌ No | Very High | Low | No |

---

## Testing

After setup, verify the service is running correctly:

```bash
# Check service status
sudo systemctl status nsrrsetd

# Check port binding
sudo ss -tlnp | grep :80

# Test API
curl http://localhost/status

# Check which user owns the process
ps aux | grep nsrrsetd

# Verify capabilities (if using Option 1)
sudo grep Cap /proc/$(pgrep -f nsrrsetd)/status
getpcaps $(pgrep -f nsrrsetd)
```

---

## Security Considerations

1. **Always run as dedicated user** - Never run as root
2. **Enable systemd hardening** - Use PrivateTmp, ProtectSystem, etc.
3. **Limit capabilities** - Only grant CAP_NET_BIND_SERVICE
4. **Monitor logs** - Check for privilege escalation attempts
5. **Keep Java updated** - Security vulnerabilities in JVM affect all processes

---

## Troubleshooting

**Permission denied on port 80:**
```bash
# Check capabilities
systemctl status nsrrsetd
journalctl -u nsrrsetd -n 50

# Verify systemd version supports AmbientCapabilities
systemctl --version

# Check SELinux (RHEL/Fedora)
sudo ausearch -m avc -ts recent
sudo setsebool -P domain_can_mmap_files 1
```

**Service fails to start:**
```bash
# Run manually to see errors
sudo -u nsrrsetd /usr/lib/jvm/java-25-openjdk/bin/java \
    -jar /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar \
    --http-port 80
```

**Java binary loses capabilities after update:**
```bash
# Reapply setcap after Java updates (Option 2)
sudo setcap 'cap_net_bind_service=+ep' /usr/lib/jvm/java-25-openjdk/bin/java
```
