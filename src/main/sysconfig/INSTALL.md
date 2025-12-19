# systemd Installation Guide

## Prerequisites

- Fedora, RHEL 8+, or any Linux with systemd v229+
- Java 25 installed at `/usr/lib/jvm/java-25-openjdk`
- Built JAR file: `nsrrsetd-2.1-jar-with-dependencies.jar`

## Installation Steps

### 1. Create dedicated user

```bash
sudo useradd -r -s /bin/false -d /var/lib/nsrrsetd -c "NS RRSet Daemon" nsrrsetd
sudo mkdir -p /var/lib/nsrrsetd
sudo chown nsrrsetd:nsrrsetd /var/lib/nsrrsetd
sudo chmod 750 /var/lib/nsrrsetd
```

### 2. Install application

```bash
# Create installation directory
sudo mkdir -p /opt/nsrrsetd

# Copy JAR file
sudo cp target/nsrrsetd-2.1-jar-with-dependencies.jar /opt/nsrrsetd/
sudo chown root:root /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar
sudo chmod 644 /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar
```

### 3. Install systemd service

```bash
# Copy service file
sudo cp systemd/nsrrsetd.service /etc/systemd/system/

# Reload systemd
sudo systemctl daemon-reload

# Enable service to start on boot
sudo systemctl enable nsrrsetd

# Start service
sudo systemctl start nsrrsetd
```

### 4. Verify installation

```bash
# Check service status
sudo systemctl status nsrrsetd

# View logs
sudo journalctl -u nsrrsetd -f

# Test API
curl http://localhost/status

# Check port binding
sudo ss -tlnp | grep :80
```

## Configuration

Edit `/etc/systemd/system/nsrrsetd.service` to customize:

### Change port (to 8080 instead of 80)
```ini
# Remove AmbientCapabilities line
# Change --http-port 80 to --http-port 8080
ExecStart=/usr/lib/jvm/java-25-openjdk/bin/java ${JAVA_OPTS} \
    -jar /opt/nsrrsetd/nsrrsetd-2.1-jar-with-dependencies.jar \
    --http-enabled \
    --http-port 8080 \
    ...
```

### Change DNS resolver
```ini
# Change --resolver 8.8.8.8 to your DNS server
    --resolver 192.168.1.1
```

### Adjust memory limits
```ini
Environment="JAVA_OPTS=-Xmx1024m -Xms256m"
```

### After any changes
```bash
sudo systemctl daemon-reload
sudo systemctl restart nsrrsetd
```

## Management Commands

```bash
# Start service
sudo systemctl start nsrrsetd

# Stop service
sudo systemctl stop nsrrsetd

# Restart service
sudo systemctl restart nsrrsetd

# Check status
sudo systemctl status nsrrsetd

# View logs (follow)
sudo journalctl -u nsrrsetd -f

# View logs (last 100 lines)
sudo journalctl -u nsrrsetd -n 100

# View logs (since today)
sudo journalctl -u nsrrsetd --since today

# Disable service
sudo systemctl disable nsrrsetd

# Re-enable service
sudo systemctl enable nsrrsetd
```

## Troubleshooting

### Service won't start - Permission denied on port 80

Check systemd version:
```bash
systemctl --version
# Should be v229 or higher for AmbientCapabilities
```

Check SELinux (RHEL/Fedora):
```bash
sudo ausearch -m avc -ts recent
# If SELinux is blocking, consider:
sudo setsebool -P nis_enabled 1
```

Alternative: Use authbind or setcap (see PORT80-SETUP.md)

### Service crashes frequently

Check memory limits:
```bash
sudo journalctl -u nsrrsetd | grep -i "OutOfMemory"
# Increase JAVA_OPTS=-Xmx if needed
```

Check file descriptor limits:
```bash
# Current service limits
sudo systemctl show nsrrsetd | grep LimitNOFILE
```

### Can't resolve DNS

Check resolver setting:
```bash
# Test DNS resolver from service user
sudo -u nsrrsetd dig @8.8.8.8 google.com

# Check firewall
sudo firewall-cmd --list-all
```

## Uninstallation

```bash
# Stop and disable service
sudo systemctl stop nsrrsetd
sudo systemctl disable nsrrsetd

# Remove service file
sudo rm /etc/systemd/system/nsrrsetd.service
sudo systemctl daemon-reload

# Remove application
sudo rm -rf /opt/nsrrsetd

# Remove data directory (optional)
sudo rm -rf /var/lib/nsrrsetd

# Remove user (optional)
sudo userdel nsrrsetd
```

## Security Notes

- Service runs as unprivileged user `nsrrsetd`
- Only capability granted: `CAP_NET_BIND_SERVICE` (bind to port 80)
- Hardened with systemd security features:
  - `NoNewPrivileges=true` - prevents privilege escalation
  - `PrivateTmp=true` - isolated /tmp directory
  - `ProtectSystem=strict` - read-only /usr, /boot, /efi
  - `ProtectHome=true` - no access to /home
  - Syscall filtering enabled
  - Address space randomization

- Logs go to systemd journal (not world-readable)
- Application files owned by root (immutable by service)
