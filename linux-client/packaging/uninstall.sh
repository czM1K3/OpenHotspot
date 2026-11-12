#!/usr/bin/env bash
# Remove what packaging/install.sh installed. Your pairing keys and config are KEPT unless you pass --purge.
#
#   packaging/uninstall.sh [--purge]
#
#   --purge  also delete ~/.config/openhotspot (config AND the pairing keys file) and the daemon state.
#            You will have to pair again. A key stored in the system keyring is not touched; remove it
#            with: secret-tool clear service openhotspot username phones
set -euo pipefail

purge=0
for arg in "$@"; do
  case "$arg" in
    --purge) purge=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

data="${XDG_DATA_HOME:-$HOME/.local/share}/openhotspot"
bin="$HOME/.local/bin/openhotspot"
config_dir="${XDG_CONFIG_HOME:-$HOME/.config}/openhotspot"
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/openhotspot"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  for unit in openhotspot.service openhotspot-tray.service; do
    systemctl --user disable --now "$unit" >/dev/null 2>&1 || true
  done
fi
rm -f "$unit_dir/openhotspot.service" "$unit_dir/openhotspot-tray.service"
if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  systemctl --user daemon-reload || true
fi

# Remove the command only if it is our symlink into the venv, never a file someone else put there.
if [ -L "$bin" ] && [ "$(readlink "$bin")" = "$data/venv/bin/openhotspot" ]; then rm -f "$bin"; fi
rm -rf "$data/venv"
rmdir "$data" 2>/dev/null || true

# Leave no Wi-Fi profile behind (ours are named openhotspot-<ssid>).
if command -v nmcli >/dev/null; then
  nmcli -t -f UUID,NAME connection show 2>/dev/null | while IFS=: read -r uuid name; do
    case "$name" in openhotspot-*) nmcli connection delete uuid "$uuid" >/dev/null 2>&1 || true ;; esac
  done
fi

if [ "$purge" = 1 ]; then
  rm -rf "$config_dir" "$state_dir"
  echo "Removed $config_dir and $state_dir (config, pairing keys file, state)."
else
  echo "Kept $config_dir (config and pairing keys). Use --purge to delete them too."
fi
echo "Uninstalled. (The phone still lists this laptop until you remove it in the phone app, or ran \`openhotspot unpair\` first.)"
