# Running Multiple nsrrsetd Instances

The nsrrsetd systemd unit uses instance notation (`nsrrsetd@.service`) to support running multiple instances on the same server, each warming the cache for a different DNS recursor.

## Instance Naming

The instance name (`%i` in systemd) is passed as the resolver IP address (`-r`) to the application.

**Syntax:**
```bash
systemctl start nsrrsetd@INSTANCE_NAME
```

Where `INSTANCE_NAME` is typically the IP address of your DNS recursor.

## Examples

### Single Instance

Warm cache for Google DNS (8.8.8.8):

```bash
# Start instance
sudo systemctl start nsrrsetd@8.8.8.8

# Enable on boot
sudo systemctl enable nsrrsetd@8.8.8.8

# Check status
sudo systemctl status nsrrsetd@8.8.8.8

# View logs
sudo journalctl -u nsrrsetd@8.8.8.8 -f

# Stop instance
sudo systemctl stop nsrrsetd@8.8.8.8
```

### Multiple Instances

Warm cache for multiple recursors on the same server:

```bash
# Start instances for different recursors
sudo systemctl start nsrrsetd@8.8.8.8
sudo systemctl start nsrrsetd@1.1.1.1
sudo systemctl start nsrrsetd@192.168.1.1

# Enable all on boot
sudo systemctl enable nsrrsetd@8.8.8.8
sudo systemctl enable nsrrsetd@1.1.1.1
sudo systemctl enable nsrrsetd@192.168.1.1

# Check status of all instances
sudo systemctl status 'nsrrsetd@*'

# View combined logs
sudo journalctl -u 'nsrrsetd@*' -f
```

### Using IPv6 Addresses

systemd instance names can contain colons, so IPv6 addresses work:

```bash
# IPv6 example
sudo systemctl start nsrrsetd@2001:4860:4860::8888

# Or use escaped format for shell safety
sudo systemctl start 'nsrrsetd@2001:4860:4860::8888'
```

## Configuration

### Global Configuration

All instances share the configuration from `/etc/sysconfig/nsrrsetd`:

```bash
# Edit global config
sudo vim /etc/sysconfig/nsrrsetd

# Restart all instances to apply changes
sudo systemctl restart 'nsrrsetd@*'
```

### Per-Instance Configuration

For different settings per instance, create instance-specific config files:

```bash
# Create per-instance config
sudo cp /etc/sysconfig/nsrrsetd /etc/sysconfig/nsrrsetd@8.8.8.8

# Edit instance config
sudo vim /etc/sysconfig/nsrrsetd@8.8.8.8
```

Example `/etc/sysconfig/nsrrsetd@8.8.8.8`:
```bash
JAVAARGS="-Xmx1g -Xms128m"
NSRRSETARGS="--http-enabled --http-port 8080 --dns-timeout 2000"
```

Example `/etc/sysconfig/nsrrsetd@1.1.1.1`:
```bash
JAVAARGS="-Xmx1g -Xms128m"
NSRRSETARGS="--http-enabled --http-port 8081 --dns-timeout 2000"
```

systemd will use the instance-specific config if it exists, otherwise falls back to the global `/etc/sysconfig/nsrrsetd`.

## HTTP API with Multiple Instances

When running multiple instances with HTTP API enabled, each must use a different port:

**Option 1: Per-instance sysconfig files (recommended)**

`/etc/sysconfig/nsrrsetd@8.8.8.8`:
```bash
NSRRSETARGS="--http-enabled --http-port 8080"
```

`/etc/sysconfig/nsrrsetd@1.1.1.1`:
```bash
NSRRSETARGS="--http-enabled --http-port 8081"
```

**Option 2: Edit global config to disable HTTP**

Keep HTTP disabled globally, enable only for specific instances via per-instance configs.

## Systemd Instance Features

The instance name (`%i`) is available in the unit file as a variable:

- `%i` - Instance name (e.g., "8.8.8.8")
- `%I` - Escaped instance name
- `%N` - Full unit name (e.g., "nsrrsetd@8.8.8.8.service")

Current unit file uses:
```ini
ExecStart=/usr/bin/java $JAVAARGS -jar /opt/nsrrsetd/nsrrsetd.jar -r %i $NSRRSETARGS
SyslogIdentifier=nsrrsetd@%i
WorkingDirectory=/var/lib/nsrrsetd
```

Each instance:
- Gets its own journal identifier
- Shares the working directory
- Uses the resolver IP from the instance name

## Useful Commands

```bash
# List all running nsrrsetd instances
sudo systemctl list-units 'nsrrsetd@*'

# Show enabled instances
sudo systemctl list-unit-files 'nsrrsetd@*'

# Restart all instances
sudo systemctl restart 'nsrrsetd@*'

# Stop all instances
sudo systemctl stop 'nsrrsetd@*'

# Disable all instances
sudo systemctl disable 'nsrrsetd@*'

# Check resource usage
sudo systemctl status 'nsrrsetd@*' | grep -E '(Active|Memory|Tasks)'

# View logs from last hour for all instances
sudo journalctl -u 'nsrrsetd@*' --since '1 hour ago'
```

## Monitoring

Check cache warming progress for each instance:

```bash
# If HTTP API enabled on port 8080 for 8.8.8.8
curl http://localhost:8080/status

# If HTTP API enabled on port 8081 for 1.1.1.1
curl http://localhost:8081/status
```

Without HTTP API, check logs:

```bash
sudo journalctl -u nsrrsetd@8.8.8.8 -n 100
```

## Troubleshooting

**Instance won't start:**
```bash
# Check systemd unit syntax
sudo systemd-analyze verify /usr/lib/systemd/system/nsrrsetd@.service

# Run manually to see errors
sudo -u nsrrsetd /usr/bin/java -jar /opt/nsrrsetd/nsrrsetd.jar -r 8.8.8.8
```

**Port conflicts:**
```bash
# Check what's listening on ports
sudo ss -tlnp | grep -E ':(8080|8081)'

# Make sure each instance uses different port
```

**High memory usage:**
```bash
# Check memory per instance
sudo systemctl status 'nsrrsetd@*' | grep Memory

# Adjust JAVAARGS in /etc/sysconfig/nsrrsetd
JAVAARGS="-Xmx512m -Xms64m"
```

## Best Practices

1. **Use meaningful instance names** - Use the recursor IP as instance name
2. **Monitor all instances** - Set up monitoring for each instance
3. **Different ports for HTTP** - Each instance needs unique port if HTTP enabled
4. **Resource limits** - Adjust memory based on number of instances
5. **Stagger starts** - When enabling multiple instances on boot, they'll start in parallel

## Example Production Setup

Server with 3 recursors (192.168.1.1, 192.168.1.2, 192.168.1.3):

```bash
# Install nsrrsetd RPM
sudo dnf install nsrrsetd-2.1-1.noarch.rpm

# Create per-instance configs
for i in 1 2 3; do
  sudo bash -c "cat > /etc/sysconfig/nsrrsetd@192.168.1.$i <<EOF
JAVAARGS=\"-Xmx1g -Xms128m\"
NSRRSETARGS=\"--http-enabled --http-port 808$i --dns-timeout 2000 --jetty-threads 100\"
EOF"
done

# Enable and start all instances
for i in 1 2 3; do
  sudo systemctl enable nsrrsetd@192.168.1.$i
  sudo systemctl start nsrrsetd@192.168.1.$i
done

# Verify all running
sudo systemctl status 'nsrrsetd@*'

# Check APIs
curl http://localhost:8081/status  # 192.168.1.1
curl http://localhost:8082/status  # 192.168.1.2
curl http://localhost:8083/status  # 192.168.1.3
```
