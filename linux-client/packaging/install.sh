#!/usr/bin/env bash
# Install OpenHotspot for the current user: a private virtualenv, the `openhotspot` command in
# ~/.local/bin, a default config, and a systemd *user* service. Needs no root.
#
#   packaging/install.sh [--tray] [--no-start] [--no-service]
#
#   --tray        also install the tray extra (pystray, Pillow) and enable the tray variant of the service
#   --no-start    install and enable the service but do not start it now
#   --no-service  only install the command and the config
set -euo pipefail

tray=0; start=1; service=1
for arg in "$@"; do
  case "$arg" in
    --tray) tray=1 ;;
    --no-start) start=0 ;;
    --no-service) service=0 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$(dirname "$here")"                      # linux-client/
data="${XDG_DATA_HOME:-$HOME/.local/share}/openhotspot"
venv="$data/venv"
bin="$HOME/.local/bin"
config_dir="${XDG_CONFIG_HOME:-$HOME/.config}/openhotspot"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

die() { echo "error: $*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 not found"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' || die "Python 3.11 or newer is required"
python3 -c 'import venv, ensurepip' 2>/dev/null || die "the venv module is missing (Debian/Ubuntu: sudo apt install python3-venv)"

extras="keyring"
venv_args=()
if [ "$tray" = 1 ]; then
  extras="keyring,tray"
  # The AppIndicator backend of pystray uses PyGObject from the system packages.
  venv_args+=(--system-site-packages)
fi

echo "==> Creating the virtualenv in $venv"
mkdir -p "$data" "$bin"
python3 -m venv "${venv_args[@]}" "$venv"
"$venv/bin/pip" install --quiet --upgrade pip
echo "==> Installing openhotspot[$extras] (this downloads bleak, cryptography, ...)"
"$venv/bin/pip" install --quiet "$src[$extras]"

ln -sf "$venv/bin/openhotspot" "$bin/openhotspot"
case ":$PATH:" in *":$bin:"*) ;; *) echo "note: $bin is not on your PATH; add it to run \`openhotspot\` directly" ;; esac

mkdir -p "$config_dir"; chmod 700 "$config_dir"
if [ ! -e "$config_dir/config.toml" ]; then
  cp "$here/config.example.toml" "$config_dir/config.toml"
  echo "==> Wrote a default config: $config_dir/config.toml"
else
  echo "==> Kept your existing config: $config_dir/config.toml"
fi

if [ "$service" = 1 ]; then
  command -v systemctl >/dev/null || die "systemctl not found; re-run with --no-service"
  systemctl --user show-environment >/dev/null 2>&1 \
    || die "cannot reach your systemd user manager (are you logged in through a normal session?). Re-run with --no-service, or run from a desktop terminal"
  mkdir -p "$unit_dir"
  if [ "$tray" = 1 ]; then unit=openhotspot-tray.service; other=openhotspot.service
  else unit=openhotspot.service; other=openhotspot-tray.service; fi
  cp "$here/systemd/openhotspot.service" "$here/systemd/openhotspot-tray.service" "$unit_dir/"
  systemctl --user daemon-reload
  systemctl --user disable --now "$other" >/dev/null 2>&1 || true
  if [ "$start" = 1 ]; then systemctl --user enable --now "$unit"; else systemctl --user enable "$unit"; fi
  echo "==> Service $unit enabled$([ "$start" = 1 ] && echo ' and started')"
  echo "    logs:    journalctl --user -u $unit -f"
  echo "    status:  systemctl --user status $unit"
fi

cat <<MSG

Done. Next steps:
  1. Check the system:     openhotspot doctor
  2. Pair with the phone:  openhotspot pair <the openhotspot://pair/... text or a QR screenshot>
     (the phone app shows the QR for 2 minutes; the daemon ignores everything until a phone is paired)
  3. Try it by hand:       openhotspot connect
MSG
