# Setup

The **Linux** and **Android tablet** sections are written. The phone section (Shizuku, battery exemption, pairing screen) is still to do; until then see `phone-app/` and `docs/HOTSPOT_BACKENDS.md`. You need a phone running the OpenHotspot app with Shizuku started, and its pairing QR on screen, to follow the steps below.

Status of everything below: the Linux client's logic, the BLE transport (against a mocked bleak), the daemon, the config and the scripts are unit tested; **nothing has yet been run against a real phone**. `docs/TESTING.md` lists what to check by hand.

## Linux

### What you need

| Need | Version | Why |
|---|---|---|
| Python | 3.11 or newer | the client |
| BlueZ (`bluetoothd`) | **5.55 or newer**; 5.62+ recommended | bleak 3.x refuses older BlueZ. Below 5.62 BlueZ does not report the negotiated MTU, so every message goes in 20-byte chunks: slower, still correct. `bluetoothctl --version` shows yours |
| A Bluetooth LE adapter | any that BlueZ drives | `bluetoothctl list` should show one |
| NetworkManager with `nmcli` | any recent | joins the hotspot. iwd-only or wpa_supplicant-only setups are not supported |
| `notify-send` (libnotify) | optional | desktop notifications |
| `zbar-tools` | optional | `zbarimg` reads a QR from an image, `zbarcam` from a webcam |
| `pystray` and Pillow | optional, `pip install '.[tray]'` | tray icon |

No root is needed to run the client. Packages: Arch `bluez bluez-utils networkmanager libnotify zbar`, Debian/Ubuntu `bluez network-manager libnotify-bin zbar-tools python3-venv`, Fedora `bluez NetworkManager libnotify zbar`.

### Install

Easiest, and gives you the background service:

```
cd linux-client
packaging/install.sh            # add --tray for the tray icon, --no-service to skip systemd
openhotspot doctor              # checks Bluetooth, NetworkManager, clock, pairing
```

`install.sh` creates a private virtualenv in `~/.local/share/openhotspot/venv`, links `~/.local/bin/openhotspot`, writes `~/.config/openhotspot/config.toml` if you have none, installs a **systemd user service** and starts it. It needs no root and touches nothing outside your home. `packaging/uninstall.sh` reverses it and keeps your config and pairing keys unless you pass `--purge`.

Manual alternative (no service): `python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`.

### Pair

1. On the phone open the app's pairing screen. The QR is valid for 120 seconds and the screen blocks screenshots (`FLAG_SECURE`), so the client has to *see* it with a camera.
2. Read it with a webcam and pair:
   ```
   openhotspot pair "$(zbarcam --raw -1 /dev/video0)"
   ```
   or photograph the screen with another device and run `openhotspot pair photo.png` (needs `zbarimg`). `--label NAME` sets the name the phone shows for this laptop (default: the host name). *(`zbarcam` flags are from its documentation and untested here.)*
3. The key is stored in the system keyring if a Secret Service is running (`pip install '.[keyring]'`, which `install.sh` does), otherwise in `~/.config/openhotspot/phones.json` (mode 0600). Choose with `--keystore {auto,file,keyring}`.

### Use

```
openhotspot connect        # phone hotspot on, join it, verify the internet works
openhotspot status         # paired phones, laptop connectivity, hotspot state (--show-password to print it)
openhotspot disconnect     # leave the network, delete its NetworkManager profile, switch the phone's hotspot off
openhotspot unpair         # forget the phone (and ask it to forget this laptop)
```

`connect` checks that the internet is reachable after joining and exits with an error if not (the connection is left in place so you can look). `--no-verify` skips that. `disconnect --keep-hotspot` only leaves the network.

### Background mode (daemon)

`openhotspot daemon` watches the connection. If the laptop has **no internet** and the **paired phone is in range**, it asks the phone for its hotspot, joins, and verifies the internet. Configure it in `~/.config/openhotspot/config.toml` (a commented example is `linux-client/packaging/config.example.toml`):

| `[daemon] mode` | Behaviour |
|---|---|
| `auto` | connects by itself |
| `manual` | never connects by itself; at most one "phone nearby" notification per `manual_notify_interval_s`. Connect with the command or the tray |
| `off` | does nothing |

Low power: while you are online it **does not scan at all** (it only polls NetworkManager every 30 s). While offline it scans for 6 s every 60 s, the pause growing to 300 s while the phone stays away. After a connect it stays quiet for `cooldown_s` (300), after a failure for `failure_cooldown_s` (120), and after your own `openhotspot disconnect` for `disconnect_pause_s` (3600). If the hotspot comes up but the internet does not work (for example the phone fell back to its local-only mode, or has no data), the daemon **undoes** the connection and tells you, because a Wi-Fi network that goes nowhere can stop the laptop joining a real one. If several phones are paired, set `phone = "<id prefix>"`.

Service control: `systemctl --user status|restart|stop openhotspot`, logs with `journalctl --user -u openhotspot -f`. Try one pass without the service: `openhotspot -v daemon --once`.

**Tray** (`install.sh --tray`, which enables `openhotspot-tray.service` instead): a coloured icon, a status line, *Connect now*, a mode choice (auto / manual / off, kept only until the daemon restarts) and *Quit*. Plain KDE, XFCE, Cinnamon and MATE show it directly. **GNOME needs the AppIndicator / KStatusNotifierItem extension.** Tray and notifications are never required for the daemon to work.

### Troubleshooting

Start with `openhotspot doctor` and `openhotspot -v <command>`; errors name the failing layer (Bluetooth, NetworkManager, phone) and say what to try.

**BlueZ**
- *"the Bluetooth service (bluetoothd) is not running"*: `sudo systemctl enable --now bluetooth`.
- *"Bluetooth is turned off"* / *"the adapter is off"*: `rfkill list`, `rfkill unblock bluetooth`, `bluetoothctl power on`. To make it stick across reboots set `AutoEnable=true` in `/etc/bluetooth/main.conf`.
- *"no Bluetooth adapter found"*: `bluetoothctl list` is empty, so the driver or firmware is missing (`dmesg | grep -i bluetooth`). USB dongles and some laptops need `linux-firmware`.
- *"Bleak requires BlueZ >= 5.55"*: upgrade BlueZ. On 5.55 to 5.61 it works but at the slow 20-byte chunk size.
- *"D-Bus refused access to BlueZ"* (permissions): on current BlueZ any local user may talk to `org.bluez` (`/usr/share/dbus-1/system.d/bluetooth.conf` has a `context="default"` allow, as on the development machine's BlueZ 5.87). Older or distro-patched setups restrict it to a group or to the active session: look for `group="bluetooth"` (or `lp`) in that file and add yourself (`sudo usermod -aG bluetooth $USER`, log out and in). Running from plain `ssh` or `su` without a login session is a common cause. *(Other distributions' policies are not verified.)*
- *"BlueZ could not complete the operation (le-connection-abort-by-local)"* or repeated connect failures: usually a stale device cache or radio interference. `bluetoothctl remove <phone address>` (find it with `bluetoothctl devices`), then retry. The client retries transient errors twice by itself.
- *"the device does not offer the OpenHotspot service"*: BlueZ cached the GATT table of an old address; `bluetoothctl remove` it. Also check that the phone app's service is running.
- Passive scan refused (`passive_scan = true` in the config): older BlueZ needs `bluetoothd --experimental` (or `Experimental = true` in `main.conf`). The client then falls back to active scanning on its own and logs a warning.
- Combo Wi-Fi/Bluetooth chips can lose Wi-Fi throughput while scanning on 2.4 GHz. If you notice it, raise `scan_interval_s`.

**NetworkManager and Wi-Fi**
- *"NetworkManager is not running"*: `sudo systemctl enable --now NetworkManager`. If your Wi-Fi is managed by iwd or `wpa_supplicant` alone, `nmcli` cannot join the hotspot.
- *"Wi-Fi is disabled"*: `nmcli radio wifi on`, `rfkill unblock wifi`.
- *"Not authorized" / "Insufficient privileges"* (polkit): NetworkManager lets users in an active local session create connections. A terminal over `ssh`, or a service started outside a login, may be refused; run from a desktop session. The systemd *user* service relies on polkit finding your graphical session. *(Believed to work, untested; see TESTING.md.)*
- **Hidden SSIDs.** The protocol has no "hidden" flag. If the SSID is not in NetworkManager's scan results the client rescans once and then retries with `hidden yes`, so a hidden hotspot works; a hotspot that is merely out of range ends in *"the hotspot is not visible to this laptop"*.
- **5 GHz versus 2.4 GHz.** Phones often put the hotspot on 5 GHz. The laptop may not see it if: the adapter is 2.4 GHz only; the **regulatory domain is unset** (`iw reg get` shows `country 00`), which marks many 5 GHz channels as no-initiate-radiation, so a hotspot on those channels can be invisible or refuse to associate (set your country: `sudo iw reg set PL`, and make it permanent with `wireless-regdb` plus `REGDOMAIN=PL` in `/etc/conf.d/wireless-regdom` or `/etc/default/crda`, depending on distribution, see your distribution's wireless regulatory documentation); or the phone picked a DFS channel. Most reliable fix: set the phone's hotspot band to **2.4 GHz** (the band the phone reports is included in the "not visible" error). 6 GHz needs a Wi-Fi 6E adapter.
- *"the Wi-Fi password was rejected"* when the passphrase is right: the hotspot may be WPA3-only and the adapter or driver lacks SAE support. Use WPA2/WPA3 transition mode on the phone.
- *"the hotspot did not hand out an address (DHCP)"*: toggle the hotspot on the phone once and retry; check the phone's data saver and VPN.
- Joined but *"the internet is not reachable"*: the phone has no mobile data, or the app fell back to its **local-only** hotspot mode (it has no internet by design, see DECISIONS.md D29), or a captive portal intercepts. You can add `probe_url` in `[connect]` for a stricter check.
- Leftover profiles: ours are named `openhotspot-<ssid>`; `nmcli connection show`. `disconnect` deletes them. The Wi-Fi passphrase is passed to `nmcli` as an argument, so other local users can see it in the process list for a moment (DECISIONS.md D16).

**Phone answers and clocks**
- *STALE_TIMESTAMP* ("clocks differ by more than a minute"): `timedatectl set-ntp true` on the laptop, and check the phone's automatic date and time. A laptop without NTP also fails to recognise the phone's rotating token (PROTOCOL.md R7).
- *SHIZUKU_UNAVAILABLE* / *PERMISSION_DENIED*: Shizuku is not running on the phone (it stops at reboot) or has not granted the app permission.
- *"phone not found nearby"*: the phone's service must be running and the phone within roughly 10 m; battery optimisation on the phone can stop BLE advertising (PROTOCOL.md R5).
- *AUTH_FAILED / "pairing may have been removed"*: the phone no longer knows this laptop; pair again. The client never deletes a pairing because of one unauthenticated error.
- *"a key file that is accessible by other users"*: `chmod 600 ~/.config/openhotspot/phones.json`.
- The Secret Service prompting at login, or being locked while the daemon starts: use `--keystore file` for the service (add it to `ExecStart` with `systemctl --user edit openhotspot`) or unlock the keyring first.

### Removing

`packaging/uninstall.sh` (add `--purge` to delete config, pairing keys file and state too). A key kept in the system keyring is not touched; `secret-tool clear service openhotspot username phones` removes it. Remove the laptop in the phone app, or run `openhotspot unpair` first.


## Android tablet

Status: the tablet app builds, its protocol/flow/auto-trigger logic is unit tested against the real phone-side engine, and it launches on an emulator. **It has not been run against a real phone, and it has not been tested on any Samsung device.** Everything below about Samsung and One UI is collected from general knowledge of Android and One UI behaviour and is marked *unverified*; `docs/TESTING.md` section 11 is the checklist for verifying it.

### Install and pair

1. `./gradlew :tablet-app:assembleDebug`, then install `tablet-app/build/outputs/apk/debug/tablet-app-debug.apk` (Android 11 or newer).
2. Open the app, **Pair a phone**. Allow Camera and Nearby devices (Android 13+ also asks for Nearby Wi-Fi devices; on Android 11 the scan needs Location, and Location must be switched on).
3. On the phone open the OpenHotspot pairing screen and scan its QR (valid 120 s, single use). No camera? Paste the `openhotspot://pair/...` link instead. The key is stored encrypted with an Android Keystore key.

### Use

- **Connect to phone hotspot** (button): finds the phone, turns its hotspot on, joins, checks the internet, shows progress and readable errors. **Disconnect** leaves the Wi-Fi and switches the phone's hotspot off (or keep it on).
- **Quick Settings tile** "Phone hotspot": tap to connect (opens the app, because Android's Wi-Fi confirmation prompt needs a visible app), tap again to disconnect. Android 13+: Settings, *Add the tile*; older: edit your Quick Settings and drag it in.
- **Auto-connect** (Settings, off by default): while the tablet has no internet and a paired phone is in range, connect by itself. It scans only while offline (10 s offline first, then 6 s windows every 60 s, backing off to 5 min), never while online or connected, and waits the chosen cooldown (1 to 60 min) between attempts, counted from the start of an attempt. It needs a foreground service, hence a permanent notification.

### Two ways to join, and why it matters

| | Quick, this app only (default) | Share with all apps |
|---|---|---|
| API | `WifiNetworkSpecifier` | `WifiNetworkSuggestion` |
| Prompt | Android shows its own *Connect* dialog every time; the app must be on screen | First suggestion needs approval in a notification, then none |
| Who uses the Wi-Fi | **Only OpenHotspot** (the app binds its process to the network and verifies the internet). Browsers and other apps keep using whatever the tablet normally uses | The whole tablet |
| Timing | Immediate | The system decides, typically seconds to a minute |

So with the default method the internet check proves the hotspot works, but other apps do not benefit. For real use pick *Share with all apps*. (This is Android's documented behaviour for specifier networks as I understand it; it is part of the on-device checklist.) Specifier needs the SSID as text, so a non-UTF-8 hotspot name only works with suggestions on Android 13+.

### Samsung One UI notes (all unverified, no Samsung device was available)

- **Battery.** One UI puts apps to "sleep" aggressively. Settings, Battery, Background usage limits: remove OpenHotspot from *Sleeping apps* and *Deep sleeping apps*, add it to *Never sleeping apps*, and set the app's battery to *Unrestricted*. The app's *Allow background use* button only covers Android's standard exemption, which One UI does not treat as enough. Without this the auto-trigger's scans can be delayed or stopped, and it may not survive a reboot (the boot receiver only runs if the app is not force-stopped).
- **Background BLE.** Android allows five scan starts per 30 s per app; the auto-trigger stays far below that. With the screen off, scans from a foreground service continue only while the notification stays; swiping the app away in recents on One UI may kill the service despite it being foreground. If auto-connect stops working after a while, check *Never sleeping apps* first. Android 14 also needs Nearby devices granted for a `connectedDevice` foreground service to start.
- **Auto Hotspot / Wi-Fi conflicts.** Samsung's own *Auto Hotspot* (Settings, Connections, Mobile Hotspot and Tethering, *Auto Hotspot*) and *Quick Share* use the same Galaxy account and Bluetooth; they work between Samsung devices only, so they should not conflict with this app, but two things can interfere: (1) if the phone is a Samsung, its Auto Hotspot may switch its hotspot on or off independently of OpenHotspot, which looks like the hotspot "turning off by itself"; turn it off on the phone while testing. (2) On the tablet, *Switch to mobile data* / *Auto switch between Wi-Fi networks* (Settings, Connections, Wi-Fi, Intelligent Wi-Fi) can drop the hotspot as a "bad" network or move the tablet off it; turn Intelligent Wi-Fi off while testing. *Wi-Fi Calling* and *Smart network switch* are other candidates.
- **Wi-Fi prompt.** One UI shows the specifier dialog as an Android system sheet; if the app is in the background (auto-trigger, tile) it may not appear, in which case the request times out after 60 s with "Could not join the hotspot". Use *Share with all apps* for unattended auto-connect.
- **Random MAC.** The tablet uses a per-network random MAC; some phones' hotspots with *Allowed devices* lists then reject it. Disable the list on the phone's hotspot.
- **Samsung DeX / Secure Folder / Work profile** installs are untested; install in the main profile.

### Troubleshooting

- *Phone not found nearby*: phone service not running, Bluetooth off, or the phone's battery policy stopped its advertising (PROTOCOL.md R5). On Android 11 the tablet also needs Location switched on to scan.
- *Clocks differ by more than a minute*: enable automatic date and time on both devices.
- *Could not join the hotspot*: Android reports "user declined" and "not found" identically. Approve the prompt; try 2.4 GHz on the phone; set *hidden* in Settings if the hotspot hides its name.
- *Joined, but the internet is not reachable*: the phone has no mobile data, or it used its local-only fallback (DECISIONS.md D29), or a captive portal intercepts. The check fetches `http://connectivitycheck.gstatic.com/generate_204` over the hotspot (the only cleartext destination the app allows).
- *The phone did not accept this tablet* (AUTH_FAILED): the phone removed this tablet. The key is kept (an unauthenticated error never deletes it); unpair and pair again.
