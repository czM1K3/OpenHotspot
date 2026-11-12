# Setup

End-to-end setup for the three parts: the **phone** (hotspot provider), a **Linux laptop** and an **Android tablet** (clients). Order: phone first, then pair each client.

**Honest status.** The phone-side hotspot path (Shizuku + `TetheringManager`) was exercised on one real device through adb-started Shizuku: a Nothing phone (A059) on Android 17, see `docs/HOTSPOT_BACKENDS.md`. Everything else here (the BLE link in both directions, pairing over the air, the Linux client, the tablet app, every OEM note) has been checked against code, unit tests and an emulator only. Statements marked *(unverified)* come from documentation or general knowledge, not from a test. `docs/TESTING.md` is the checklist for turning them into facts.

Contents: [Phone](#phone-android-no-root) · [Linux](#linux) · [Android tablet](#android-tablet) · [Troubleshooting by phone maker](#troubleshooting-by-oem)

## Phone (Android, no root)

### What you need

| Need | Detail |
|---|---|
| Android 11 or newer | minSdk 30. Developed against a Nothing phone on Android 17. Only the Shizuku **TetheringManager** backend is expected to work on every version; the `cmd wifi` backend is refused by Android 17 for adb-started Shizuku and is not selected there (HOTSPOT_BACKENDS.md) |
| BLE peripheral support | the phone must be able to advertise (`Advertising failed` or *BLE advertising unsupported* in the Status tab if not) |
| [Shizuku](https://shizuku.rikka.app/) | the app accepts server version 11 or newer (it reports *"Shizuku is too old"* below); the app is built against Shizuku API 13.1.5 and the reference test ran API 14, so install the current release. Shizuku is a separate app (Google Play, F-Droid builds and GitHub releases exist; use the source you trust) |
| A Wi-Fi network, **once per reboot** | only to start Shizuku through wireless debugging (below) |
| The OpenHotspot phone app | `./gradlew :phone-app:assembleDebug`, then `adb install phone-app/build/outputs/apk/debug/phone-app-debug.apk` or copy the APK to the phone |

No root and no Google Play Services. A computer is optional (only for the USB alternative in step 2).

### 1. Install the apps

Install Shizuku and the OpenHotspot phone app. Open OpenHotspot once.

### 2. Start Shizuku with Wireless debugging (no root, no computer)

Shizuku gets its elevated rights from Android's own debugging service (the same one `adb` talks to). Wireless debugging on Android 11+ lets the phone talk to **itself**, so no cable or PC is needed. Menu names differ per maker; on **Nothing OS** they are approximately as below *(the names are from memory of Nothing OS and AOSP, the exact wording on your build may differ)*.

1. **Developer options.** Settings, About phone, open the software information entry, and tap **Build number** seven times. Enter your PIN. A new *Developer options* entry appears under Settings, System.
2. **Connect the phone to a Wi-Fi network** (home, office, or another phone's hotspot). Wireless debugging only works while the phone is on Wi-Fi. This is the one step that needs Wi-Fi even though you use the phone as the hotspot later.
3. In Developer options turn on **Wireless debugging** and allow it for this network when Android asks.
4. Open **Shizuku**, choose **Start via Wireless debugging**, and follow its prompts: open *Developer options, Wireless debugging, Pair device with pairing code*; Android shows a 6-digit code; type the code into Shizuku's notification (this is why it asks you to use a floating/split-screen window or a notification: the pairing dialog closes if you leave it). Shizuku then shows *Shizuku is running*.
5. You can now turn Wireless debugging off again; Shizuku keeps running **until the phone reboots** *(per Shizuku's documentation; not tested here)*.

If you have a computer and a USB cable, the alternative that does not need Wi-Fi at all is: enable USB debugging, run `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` (Shizuku's own *Start via computer* instructions show the current command). This is the route the real-device test used.

*Optional.* Developer options may offer **Disable adb authorization timeout**; leave it off unless Shizuku asks to be re-paired after a week of non-use *(unverified)*.

### 3. Give OpenHotspot permission to use Shizuku

1. Open OpenHotspot, **Status** tab. The *Shizuku* card shows one of: not installed, not running, too old, **running but OpenHotspot is not authorized**, ready.
2. Tap **Authorize OpenHotspot** and choose **Allow** in the Shizuku dialog. The card turns to *Shizuku is ready* with the server UID (2000 = adb-started, 0 = root).
3. If you chose *Deny and don't ask again*: open the Shizuku app, **Authorized apps**, and switch OpenHotspot on (the card has an **Open Shizuku** button).
4. Under **Setup** tap **Grant permissions** (Nearby devices for Bluetooth, Notifications), then **Exempt from battery optimisation**, and leave **Start after reboot** on.

On Android 11 the Bluetooth permissions are install-time, so there is no Bluetooth prompt; Android 12+ asks for *Nearby devices*.

### 4. Check that the hotspot can be switched

1. In the phone's normal settings (Settings, Network, Hotspot and tethering), **set the hotspot name and a password once** and choose a band. OpenHotspot starts the hotspot **with this saved configuration** and sends it to the client, so a laptop with an old Wi-Fi card should get **2.4 GHz** and WPA2 or WPA2/WPA3. If Android does not give the app the password (unverified on some builds), type SSID and password into the app's **Settings** tab, *Hotspot credentials* (stored encrypted).
2. **Diag** tab, **Test hotspot toggle**: it switches the hotspot on, reads the credentials, and off again per backend. On the reference phone the Shizuku **TetheringManager** backend passed. Export the report if something fails.
3. **Settings** tab: *Auto-off* (minutes without a connected client, default 10) and *Allow local-only fallback*. The local-only fallback is a hotspot **without internet**; leave it off unless you know you want it.
4. **Status** tab: **Start service**. It shows a permanent notification; that is what keeps Bluetooth advertising alive.

### 5. Pair a client

1. Phone, **Pairing** tab, **Show pairing QR**. The code lasts 120 seconds, works for **one** device, is hidden from screenshots, and disappears if you leave the screen. Anyone who photographs it in that time can pair, so do it privately.
2. Linux: `openhotspot pair "$(zbarcam --raw -1 /dev/video0)"` (see below). Tablet: *Pair a phone* in the app and scan, or paste the `openhotspot://pair/...` link.
3. The phone lists the new device (name, date, request count). Up to 8 clients. To remove one use **Revoke**; if that device already knows the hotspot password, also **change the hotspot password** in Android's settings (revocation does not change the password).
4. Test: from the client run connect (`openhotspot connect`, or the tablet's button).

### After a reboot

Shizuku stops when the phone reboots. OpenHotspot restarts its own service automatically if it was running and *Start after reboot* is on, but **the hotspot cannot be switched until Shizuku is running again**, and wireless debugging needs Wi-Fi or a cable. Clients get `SHIZUKU_UNAVAILABLE` meanwhile. Plan for it: restart Shizuku (step 2) once per reboot, e.g. when you next charge at home. Shizuku's own *start on boot* option needs root or an extra permission granted over adb *(unverified here)*; the real-device behaviour after reboot is item 10 of the TESTING.md checklist.

### Phone troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Status says *Shizuku is not installed / not running* | Install it; start it (step 2). It does not survive a reboot |
| *Running but OpenHotspot is not authorized* | Tap **Authorize OpenHotspot**; or Shizuku, Authorized apps |
| Backend card shows no backend selected | Open **Diag**, **Re-run detection**; read the reason per backend. `cmd wifi` refused with *Uid 2000 does not have access* is expected on Android 17 |
| Hotspot starts but the client has no internet | The phone has no mobile data, or the *local-only* fallback was used (red *No internet sharing* card); turn the fallback off |
| Clients cannot find the phone | Service not running, Bluetooth off, or battery policy killed it (see the OEM table); check the Status tab *Advertising* line |
| *Advertising failed: too many advertisers / feature unsupported* | The phone's Bluetooth stack cannot advertise right now; toggle Bluetooth; some phones forbid it while a hotspot is on *(unverified)* |
| *Pairing screen shows a blank/black code area* | `FLAG_SECURE` hides it from screenshots and screen mirroring; look at the physical screen |
| Hotspot turns itself off | That is **auto-off** (Settings tab). Set 0 to disable. Some makers also have their own idle timeout (*Turn off hotspot automatically* in their hotspot settings) |
| Repeated *RATE_LIMITED* on a client | The phone saw 5 bad attempts in a minute and is locked out for 30 s up to 1 h. Wait; if it was not you, someone nearby is probing |

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


## Troubleshooting by OEM

Background execution and tethering rules differ per maker. **Only the Nothing entry was seen on a real device, and only for the tethering backend**; everything else is general knowledge about those Android skins and may be out of date *(unverified)*. [dontkillmyapp.com](https://dontkillmyapp.com/) keeps a current list of battery-killer behaviour. What to look for is always the same three things: (1) the app is exempt from battery optimisation or "unrestricted", (2) it may auto-start and run in the background, (3) it is not swiped away from recents or put to sleep.

| Maker / skin | What to check |
|---|---|
| **Nothing OS** (Nothing Phone, CMF) | Reference device. Hotspot via Shizuku TetheringManager worked on Android 17 (adb-started Shizuku); `cmd wifi` is refused for the shell UID. Still to check: *Settings, Battery, Battery optimisation* set to *Unrestricted* for OpenHotspot, and whether the service and advertising survive screen-off for hours and a reboot (TESTING.md section 10). Nothing OS's *Developer options* are under Settings, System |
| **Samsung One UI** | *Battery, Background usage limits*: remove OpenHotspot from *Sleeping apps* and *Deep sleeping apps*, add to *Never sleeping apps*, set battery to *Unrestricted*. Turn off *Auto Hotspot* (on a Samsung phone it may switch the hotspot by itself) and *Intelligent Wi-Fi* / *Switch to mobile data* on the tablet while testing. Details in the tablet section. Wireless debugging is under Developer options as on stock Android |
| **Google Pixel / stock Android** | *Settings, Apps, OpenHotspot, App battery usage* to *Unrestricted*. Adaptive battery may delay but should not kill a foreground service. Android 14+ needs the Nearby devices permission for the service to start |
| **Xiaomi, Redmi, POCO (HyperOS / MIUI)** | *Settings, Apps, Manage apps, OpenHotspot*: enable **Autostart**, set *Battery saver* to **No restrictions**; lock the app in the recents list. Shizuku and some permission flows may additionally want *USB debugging (Security settings)* and *Install via USB* on MIUI/HyperOS. Aggressive *Memory extension* / cleaner can stop it *(unverified)* |
| **OnePlus, Oppo, Realme (OxygenOS / ColorOS)** | *Battery, Battery optimisation, OpenHotspot, Don't optimise*; enable **Allow background activity** and **Auto launch**; lock the app in recents. *Deep optimisation* style cleaners are a common cause of stopped BLE advertising *(unverified)* |
| **Vivo, iQOO (Funtouch / OriginOS)** | *Battery, Background power consumption management*: allow high background power use; enable **Autostart**. Wireless debugging menu may be under *Developer options* with different naming *(unverified)* |
| **Motorola (near-stock)** | Same as Pixel; also check *Battery, Adaptive battery* *(unverified)* |
| **Huawei / Honor** | Not supported. Without Google Mobile Services Shizuku installation and Bluetooth behaviour are untested, and EMUI/HarmonyOS restricts background apps heavily |

If the hotspot never turns on but Shizuku is ready, the maker may have stripped or changed the tethering service; run **Diag, Test hotspot toggle** and attach the exported report to an issue.
