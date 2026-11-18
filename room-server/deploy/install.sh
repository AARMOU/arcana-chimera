#!/usr/bin/env bash
# First-boot setup for an Ubuntu 24.04 Hetzner server (run by cloud-init as root).
# Needs: REPO_URL, BRANCH, TUNNEL_TOKEN in the environment.
set -euo pipefail
: "${REPO_URL:?}" "${BRANCH:?}" "${TUNNEL_TOKEN:?}"
export DEBIAN_FRONTEND=noninteractive
BASE=/opt/arcana-rooms

apt-get update -q
apt-get install -yq ca-certificates curl git gnupg unattended-upgrades
# Security updates install themselves; reboot at 09:00 UTC (2-3 AM US) only when required.
printf 'Unattended-Upgrade::Automatic-Reboot "true";\nUnattended-Upgrade::Automatic-Reboot-Time "09:00";\n' > /etc/apt/apt.conf.d/52arcana-reboot
printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' > /etc/apt/apt.conf.d/20auto-upgrades

# Node.js 22 LTS
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -yq nodejs

# Cloudflare Tunnel: the only way in. The server opens no inbound ports.
mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg
echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' > /etc/apt/sources.list.d/cloudflared.list
apt-get update -q && apt-get install -yq cloudflared
cloudflared service install "$TUNNEL_TOKEN"

# Many simultaneous phones
cat > /etc/sysctl.d/90-arcana-rooms.conf <<'SYSCTL'
fs.file-max = 500000
net.core.somaxconn = 4096
net.ipv4.tcp_keepalive_time = 120
SYSCTL
sysctl --system > /dev/null

id arcana >/dev/null 2>&1 || useradd --system --home /var/lib/arcana-rooms --shell /usr/sbin/nologin arcana
install -d -o arcana -g arcana -m 0750 /var/lib/arcana-rooms
install -d -m 0755 "$BASE" "$BASE/releases"
printf 'REPO_URL=%q\nBRANCH=%q\n' "$REPO_URL" "$BRANCH" > "$BASE/config.env"

# First release: same build-and-test path as every later update.
TMP="$(mktemp -d)"
git clone --quiet --depth 1 --filter=blob:none --sparse --branch "$BRANCH" "$REPO_URL" "$TMP/repo"
git -C "$TMP/repo" sparse-checkout set room-server
install -m 0755 "$TMP/repo/room-server/deploy/update.sh" "$BASE/update.sh"
install -m 0644 "$TMP/repo/room-server/deploy/arcana-rooms-update.service" /etc/systemd/system/
install -m 0644 "$TMP/repo/room-server/deploy/arcana-rooms-update.timer" /etc/systemd/system/
rm -rf "$TMP"
"$BASE/update.sh"
systemctl daemon-reload
systemctl enable --now arcana-rooms.service arcana-rooms-update.timer
echo "ARCANA_ROOMS_INSTALLED"
