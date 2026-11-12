# Testing

## What the automated tests prove, and what they cannot

Run: `cd linux-client && .venv/bin/python -m pytest` (about 260 tests, a few seconds, no hardware, no root) and `./gradlew test assembleDebug lintDebug` (about 350 JVM tests across `:common`, `:phone-app`, `:tablet-app`; lint must report zero issues). CI (`.github/workflows/ci.yml`) runs both on every push.

| Area | Automated (mocked) | Needs a real phone / BlueZ / NetworkManager |
|---|---|---|
| Protocol, crypto, framing, chunking | RFC and `docs/test-vectors.json` known answers; in-process `FakePhone` | Interoperability with the Kotlin phone app **over the air** |
| `BleakTransport` | Our code paths against a fake bleak: scan filtering and matching, scanner stop, passive-to-active fallback, connect retries, MTU reading and fallback, notification queue, disconnect wake-up, timeouts, every D-Bus/bleak error translation | **That BlueZ really behaves as bleak 3.1 and our assumptions say**: that `ServiceData` of the phone's advertisement reaches bleak's callback under the service UUID key, that the Request characteristic's `MTU` property is populated and when, that `start_notify` works on the phone's CCCD, connect times, the exact D-Bus errors BlueZ raises for real failures, passive-scan support, adapter coexistence |
| Retries | Fake transport drops the link mid-exchange: new attempt, new counter | Real link drops (phone walking away, phone app killed) |
| NetworkManager (`nmcli`) | Our command lines and our parsing against a scripted fake `nmcli`; hint selection by message text | **The real `nmcli` message texts and exit codes** (we matched on documented and remembered strings), polkit behaviour, hidden SSID join, WPA3, DHCP, regulatory domain, profile creation and deletion, what NetworkManager does with the saved profile later |
| Connectivity check | Mapped from scripted `nmcli general` / `connectivity check` output | NetworkManager's connectivity checking enabled or disabled; captive portals |
| Daemon | Decisions with a fake phone, fake network state and fake clock: modes, cooldown, back-off, no scan while online, undo on no-internet, error handling, stop and wake-up | **Power use and timing in real life**, suspend/resume, adapter hot-plug, Bluetooth toggled while running |
| Config, install scripts | Example config parses and equals the defaults; scripts pass `bash -n`; one run of `install.sh --no-service` and `uninstall.sh --purge` in a throw-away `$HOME`; units pass `systemd-analyze --user verify` | The service starting under a real user manager at login, polkit for a service, `--tray` unit ordering |
| Tray | Menu model (entries, modes, actions) | Everything graphical: the icon appearing, the menu, GNOME extension, KDE, Wayland vs X11 |
| Notifications | The `notify-send` command line | A notification daemon actually showing it |
| Phone app side | Not covered here (see `docs/HOTSPOT_BACKENDS.md`) | all of it |

Already observed on real Linux (development machine, BlueZ 5.87, NetworkManager 1.58, Bluetooth switched off at the time): `openhotspot doctor` ran correctly against real `bluetoothctl`, `nmcli` and `timedatectl` and flagged the powered-off adapter; a real bleak scan reached BlueZ over D-Bus and returned the translated "Bluetooth is turned off" error. No advertisement was ever received and no connection was ever made.

## Core scenario checklist (phone + client, real hardware)

Do this first. It covers the scenarios that matter most and ties together the more detailed per-client sections 0 to 11 below. Setup: phone with the app, Shizuku started and authorized, **Start service** pressed (SETUP.md); one Linux laptop (and later the tablet). Record phone model, Android and OEM version, laptop BlueZ version, results and anything surprising. Tick a box only when the **expected** result happened. For each failure save: the phone's **Log** tab, `openhotspot -v` output, and `btmon` if it is a BLE problem.

Notation: **P** = on the phone, **L** = on the Linux laptop, **T** = on the tablet.

### S1. Pairing

- [ ] **P** Pairing tab, *Show pairing QR*: a QR and a countdown from 120 s appear; a screenshot (power+volume) shows a black/blank QR area (`FLAG_SECURE`).
- [ ] **L** `openhotspot pair "$(zbarcam --raw -1 /dev/video0)"` prints *Paired*. **P** lists the laptop under its label with *0 requests*, and the QR screen reverts to the *Show pairing QR* button (window closed after one use).
- [ ] The same QR scanned again (second device or a photo taken earlier) is refused: the client says the phone is not in pairing mode.
- [ ] **Expiry.** Show a QR, wait 125 s, scan: refused; the phone's countdown stopped and the QR disappeared at 0.
- [ ] **Three strikes** (not reachable with the normal client, which finds the phone by a token derived from the QR secret and so never connects with a wrong one). Covered by the unit test `wrongSecretIsRejectedAndThreeFailuresCloseTheWindow` only; over the air it needs a custom BLE script that connects to the phone while the QR is shown and writes three bad `PAIR_REQ` frames: afterwards the QR must disappear. Mark *not tested* if you skip it.
- [ ] **Leaving the screen.** Show a QR, press Home: on return the QR is gone (window closed).
- [ ] **T** Pair the tablet by camera, then a second time by pasting the link; both leave the phone showing separate entries. Pair clients until 8 are paired: the 9th is refused (`TOO_MANY_CLIENTS`).
- [ ] After pairing, `grep -ri -E 'ltk|passphrase' ` on `adb logcat -d` of the phone shows no key or password values.

### S2. Hotspot on / off

- [ ] **L** `openhotspot -v connect` (hotspot initially off): hotspot on at the phone within about 5 s on a good run, laptop joins, `status` shows `hotspot: ON`, the internet works. Record the total time.
- [ ] **P** Status tab shows *Hotspot is ON via ShizukuTetheringBackend*, the Android hotspot toggle shows on, and the notification/log has *ENABLE ... handled* with **no SSID or password** in the Log tab.
- [ ] Connect again with the hotspot already on: succeeds, one `openhotspot-*` profile.
- [ ] **L** `openhotspot disconnect`: laptop leaves, the hotspot turns **off** on the phone.
- [ ] **T** *Connect to phone hotspot* and *Disconnect*: same expectations. **Tile** also.
- [ ] Hotspot turned on by hand in Android's settings, then **L** `connect`: no error, credentials come from the saved config.
- [ ] Hotspot turned off by hand while a client is connected: the next client request turns it on again.
- [ ] Phone mobile data off: client reports the hotspot has no internet and (daemon/auto) undoes the join.
- [ ] **Credentials fidelity.** The SSID and password the client joined with equal those in Android's hotspot settings (including a password with spaces and non-ASCII characters, and the 63-character maximum).
- [ ] **Backend fallback.** Diag tab, run *Test hotspot toggle* for each backend chip; paste the report. On Android 17 expect Shizuku TetheringManager PASS, shell backend reported unavailable.

### S3. Auto-off

- [ ] **P** Settings: auto-off 2 minutes. **L** `connect`, then `disconnect --keep-hotspot`. With **no client joined**, the hotspot turns off after about 2 minutes (Log: auto-off).
- [ ] With a client **joined** (laptop on the hotspot), the hotspot stays on well past 2 minutes. Leave the client; it goes off about 2 minutes later (timer restarted from zero).
- [ ] `openhotspot connect --auto-off 60` with a longer phone setting: the shorter value wins; `connect --auto-off 3600` with a 2-minute setting does not extend it.
- [ ] Setting 0 disables auto-off: hotspot stays on for 15 minutes with no client.
- [ ] Android kills the OpenHotspot process while the hotspot is on (`adb shell am kill org.openhotspot.phone`): record what happens (design says the hotspot **stays on**: no timer survives). This is a known limitation, confirm the actual behaviour.

### S4. Revoke

- [ ] **P** Pairing tab, *Revoke* the laptop, confirm. **L** `connect` fails with *AUTH_FAILED / pairing may have been removed* and does **not** delete the local key; the phone no longer advertises that client's token (`daemon --once` reports no phone nearby for the laptop, `bluetoothctl` service data differs).
- [ ] The tablet, paired to the same phone, still connects (revoking one client leaves others working).
- [ ] Re-pair the laptop with a new QR: works, shows as a new entry.
- [ ] **Client-initiated:** **L** `openhotspot unpair` removes the laptop from the phone's list and deletes the local key. With the phone out of range it reports that the phone could not be told, and still deletes the local key.
- [ ] **Revoked client and the password.** After revoking, the old laptop can still join Wi-Fi with the **old password** if it saved it (profiles are deleted on `disconnect`; check `nmcli connection show`). Change the hotspot password in Android: the old laptop can no longer join; the tablet gets the new one on its next connect.
- [ ] **P** Remove all clients: every client fails, the phone stops advertising (no token to advertise).

### S5. Replay and other attacks (run `tools/attack_probe.py` on the laptop)

Use the paired laptop. Each prints PASS/FAIL.

- [ ] `python tools/attack_probe.py replay`: first QUERY answered, identical re-send answered with an **authenticated REPLAY**; the phone's Log shows *replayed counter* and the hotspot state is unchanged.
- [ ] `... stale`: STALE_TIMESTAMP (authenticated). Also set the laptop clock 2 min ahead and run a normal `connect`: *clocks differ*; restore the clock.
- [ ] `... tamper`: plain AUTH_FAILED, no effect on the phone. `... version`: plain UNSUPPORTED_VERSION.
- [ ] `... hold`: the phone disconnects a silent connection after about **10 s** (this checks the fix described in SECURITY.md section 7: it must not stay connected for 25 s).
- [ ] While one connection is held open and silent (`bluetoothctl connect <address>` of the phone, no further action), a real `connect` from another client **works after at most ~10 s** and does not hang indefinitely.
- [ ] `... lockout --yes`: after six junk frames the phone answers plain **RATE_LIMITED** with a retry time; a valid `connect` during the lockout gets RATE_LIMITED without the phone decrypting anything (Log shows *Lockout*); after the lockout a valid request works. Repeating immediately gives a longer lockout (doubling). **P** shows the lockout (notification/Log).
- [ ] The lockout survives restarting the app: trigger a lockout, `adb shell am force-stop org.openhotspot.phone`, restart the service within 30 s, a valid request still gets RATE_LIMITED.
- [ ] Capture one real ENABLE exchange with `btmon -w capture.snoop` (or the Android HCI snoop log) and check by eye that SSID and password do not appear in the capture in plaintext (`strings capture.snoop | grep -i <ssid>` finds nothing).
- [ ] Phone's Status characteristic is readable without pairing (`bluetoothctl` GATT read of `7e5f0004-...` gives 2 bytes); it contains no name, SSID or password.

### S6. Out-of-range behaviour

- [ ] Phone 3+ m away behind a wall (or switch to Bluetooth off on the phone): **L** `connect` ends with *phone not found nearby* within `scan_timeout` (about 10 s), exit code non-zero, no traceback.
- [ ] Walk away **during** `connect` (after the request is written, before the response): error within about 30 s, no hang. Retry once back in range: works, with **no** REPLAY or counter error (fresh counter).
- [ ] Hotspot already on and the client moves out of Wi-Fi range: the laptop's Wi-Fi drops cleanly; no stale profile blocks reconnecting (`nmcli connection show | grep openhotspot`).
- [ ] **Daemon** (auto mode) with the phone away: scans back off to the long interval; phone returns: connected within one scan interval. **T** auto-connect: same.
- [ ] Maximum practical range: record how far from the phone the laptop still finds it (walk until `daemon --once` stops seeing it), indoors and in the open.
- [ ] A different, unpaired OpenHotspot phone nearby: the laptop ignores it (token does not match), does not connect.

### S7. Reboot persistence

- [ ] **P** Reboot the phone with *Start after reboot* on and Shizuku **not** restarted: after unlock, the OpenHotspot notification returns by itself (service restarted) and clients **find** the phone, but `connect` fails with `SHIZUKU_UNAVAILABLE` (expected, documented).
- [ ] Restart Shizuku (wireless debugging, SETUP.md step 2), **without** touching OpenHotspot: the Status card turns to *ready* by itself (`binder received`) and `connect` works, with the **same pairings** (no re-pairing) and no counter errors.
- [ ] Reboot with *Start after reboot* **off**: the service does not start; starting it by hand works.
- [ ] Reboot with the service **stopped** by the user before: it stays stopped.
- [ ] Reboot while locked (before first unlock): record whether the service starts (Keystore keys need no user authentication, but file encryption before first unlock may block it: unverified).
- [ ] **Pairings and counters survive:** after reboot `adb shell run-as org.openhotspot.phone ls no_backup` shows `clients.bin` (debug build); the request counter continues (Pairing tab shows the request count did not reset to 0).
- [ ] **L** reboot the laptop: `systemctl --user status openhotspot` active; pairings still work; the key (file or keyring) still resolves.
- [ ] **T** reboot the tablet with auto-connect on: the foreground service restarts (Samsung: only with *Never sleeping apps*); pairings still work.
- [ ] App update (`adb install -r` of a rebuilt APK): pairings survive and the service restarts (MY_PACKAGE_REPLACED).
- [ ] Wipe app data on the phone: all pairings gone, every client then fails with AUTH_FAILED and must re-pair. Uninstall and reinstall: the Keystore key is gone too, so the same.

### S8. Endurance and power

- [ ] Phone service left running for 24 h with the screen off, battery unrestricted: still advertising at the end (laptop `daemon --once` finds it); record battery use of the OpenHotspot app (Settings, Battery).
- [ ] Advertisement token rotation across several 5-minute boundaries over 30 minutes with two clients paired: both clients keep finding the phone.
- [ ] 50 consecutive connect/disconnect cycles from the laptop: no failure, no leaked Wi-Fi profiles, no growth in the phone's Log entries beyond its cap.

## Manual hardware checklist

You need: the phone app installed with Shizuku running; a Linux laptop with BlueZ 5.62+ and NetworkManager; `btmon` (part of `bluez-utils`) and `journalctl` handy. Tick a box only when the **expected** result happened; copy the relevant `-v` log lines into the issue if it did not. Run commands as the normal user. Use `openhotspot -v ...` to see the log.

### 0. Environment

- [ ] `openhotspot doctor` prints no `FAIL`. Expected: BlueZ version, adapter present and powered, NetworkManager running, Wi-Fi device available, clock synchronised.
- [ ] `openhotspot --version`, and `openhotspot status` before pairing exits with code 3 and says to pair.

### 1. Scanning and recognition

- [ ] With the phone app's service running (not paired yet) run `bluetoothctl --timeout 15 scan on` while the phone shows the pairing QR: the phone appears as an address **with no name**. `bluetoothctl devices` / `info <address>` shows `ServiceData Key: 7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20` with 9 bytes of value. *(Confirms the premise that BlueZ exposes the service data under that key.)*
- [ ] Pair (section 2), leave the pairing screen, then `openhotspot -v daemon --once --mode manual`: the log shows the phone found (`phone in range ...` or a "phone nearby" notice). If not found, run `btmon` and confirm advertisement reports with AD type `0x21` arrive.
- [ ] Token rotation: keep the phone advertising for more than 10 minutes and run the scan again at the start, in the middle and after 2+ window boundaries (every 5 minutes on the wall clock): the phone is recognised every time.
- [ ] With **two** laptops (or one laptop and one tablet) paired to the same phone, the phone rotates its tokens about once a second (PROTOCOL.md R6): each client still finds it within `scan_duration_s`.
- [ ] Passive scanning: set `[ble] passive_scan = true`. Either it works (log has no warning) or the log says "passive scan refused ... falling back" and the phone is still found.
- [ ] Wrong clock: set the laptop's clock 2 minutes ahead (`sudo timedatectl set-ntp false; sudo date -s ...`): the phone is still **recognised** (token tolerance is plus or minus 5 minutes) but `connect` fails with *STALE_TIMESTAMP, clocks differ*. Restore with `timedatectl set-ntp true`.

### 2. Pairing

- [ ] Show the pairing QR on the phone; `openhotspot pair "$(zbarcam --raw -1 /dev/video0)"` (or a photo with `pair photo.png`). Expected: "Paired with phone ...", and the phone lists the laptop under the label.
- [ ] A second `pair` with the same QR fails (single use). An expired QR (wait over 120 s) fails with a clear error.
- [ ] The key landed where expected: `ls -l ~/.config/openhotspot/phones.json` is `-rw-------`, or `secret-tool search service openhotspot username phones` finds it.

### 3. Connect, MTU and chunking

- [ ] `openhotspot -v connect`: log shows `connected, MTU <n>`. Expected **n = 247 or more** on BlueZ 5.62+ and an Android phone. Record n. If it says 23, note the BlueZ version (below 5.62 is expected) and whether `btmon` shows an *Exchange MTU* exchange.
- [ ] End result: hotspot on at the phone, the laptop joined, `openhotspot status` shows `hotspot: ON`, and "this laptop's connectivity: full". Browse or `ping 1.1.1.1` to confirm.
- [ ] Time it: from `connect` to joined (record seconds), and `btmon` shows a single connection.
- [ ] Phone's **hotspot already on**: `connect` still succeeds and does not duplicate a Wi-Fi profile (`nmcli connection show | grep openhotspot` shows one).
- [ ] Force small chunks to exercise reassembly on real hardware: on a BlueZ older than 5.62 (or by temporarily editing `mtu_settle_s`/MTU code to return 23) a connect still succeeds, just slower.
- [ ] `openhotspot status` works and prints no passphrase; `--show-password` prints it.

### 4. Wi-Fi variants

For each of these phone hotspot settings, `connect` should join, or fail with the matching hint from SETUP.md:
- [ ] 2.4 GHz, WPA2.   - [ ] 5 GHz, WPA2.   - [ ] WPA2/WPA3 transition.   - [ ] WPA3 only (may fail on adapters without SAE; the hint should say so).
- [ ] **Hidden SSID** (hotspot setting "hide SSID"): connect succeeds via the `hidden yes` retry (look for "trying it as a hidden network" in the log).
- [ ] An SSID with spaces and non-ASCII letters; one with a colon.
- [ ] Regulatory domain `00` (`iw reg get`) with a 5 GHz hotspot: record whether it is visible; then `sudo iw reg set <your country>` and retry.
- [ ] Phone hotspot with **mobile data off**: `connect` ends with "joined ... but the internet is not reachable" and exit code 1; the Wi-Fi connection is left in place (CLI).
- [ ] Capture `nmcli` stderr for each failure above and compare with the substrings in `wifi._NM_HINTS`; fix the hints where the real text differs. *(We matched on remembered messages.)*

### 5. Failure paths (each must give a clear one-line error, no traceback)

- [ ] Phone Bluetooth off or app stopped: *phone not found nearby*.
- [ ] Laptop Bluetooth off (`rfkill block bluetooth`): *Bluetooth is turned off ... rfkill unblock bluetooth*. Then `sudo systemctl stop bluetooth`: *the Bluetooth service (bluetoothd) is not running*. Then `sudo systemctl start bluetooth`: works again without restarting the daemon.
- [ ] Shizuku stopped on the phone: *SHIZUKU_UNAVAILABLE ... Shizuku is not running*.
- [ ] Shizuku permission revoked: *PERMISSION_DENIED*.
- [ ] Walk the phone away mid-`connect` (or switch it to flight mode): error within about 30 s, never a hang; a retry afterwards works.
- [ ] Kill the phone app while connected: laptop reports a disconnect or timeout, not a hang.
- [ ] Five wrong attempts in a row (for example by unpairing on the phone, then `connect`): *AUTH_FAILED ... pairing may have been removed*; then the phone's rate limiter replies *RATE_LIMITED* and the client says to wait. The pairing key is **not** deleted by these errors.
- [ ] NetworkManager stopped: *NetworkManager is not running*; Wi-Fi radio off: the `nmcli radio wifi on` hint.
- [ ] Revoke the laptop in the phone app: `connect` fails as above, and `openhotspot unpair` removes the local key and says the phone could not be reached or acknowledged.

### 6. Disconnect

- [ ] `openhotspot disconnect`: the laptop leaves the hotspot, `nmcli connection show | grep openhotspot` is empty (profile deleted), the phone's hotspot turns off.
- [ ] With the phone out of range: the Wi-Fi part is still done, and the command reports the BLE failure clearly.
- [ ] `disconnect --keep-hotspot` leaves the phone's hotspot on.
- [ ] Auto-off: `connect --auto-off 120`; the phone turns the hotspot off after about 2 minutes (or clamps it).

### 7. Daemon

Set `[daemon]` as needed in `~/.config/openhotspot/config.toml`. Watch with `journalctl --user -u openhotspot -f`.

- [ ] **Auto**: disconnect the laptop from all networks with the phone nearby: within about `scan_interval_s` the daemon connects, a notification appears (*Connected to your phone's hotspot*), internet works.
- [ ] **Not while online**: with working Wi-Fi, `btmon` shows **no** LE scanning started by the daemon over 5 minutes. (Check the adapter is not in discovery: `bluetoothctl show | grep Discovering`.)
- [ ] **Cooldown**: right after a connect, switch the phone's hotspot off by hand; the daemon does not retrigger until `cooldown_s` has passed, then it does.
- [ ] **Failure cooldown**: stop Shizuku on the phone: one notification *Could not use the phone's hotspot*, no retry before `failure_cooldown_s`.
- [ ] **No internet on the hotspot**: with phone data off, the daemon joins, finds no internet within `verify_timeout_s`, **removes** the connection, asks the phone to turn off, and notifies. The laptop is not left on a dead network.
- [ ] **Manual** mode: only a *Phone nearby* notification, at most one per `manual_notify_interval_s`; nothing is switched on. **Off**: no scanning at all.
- [ ] `openhotspot disconnect` while the daemon runs in auto mode: it does **not** reconnect for `disconnect_pause_s`.
- [ ] Back-off: with the phone away, scans are `scan_interval_s` apart at first, then longer; bring the phone back: found on the next scan, interval resets.
- [ ] Suspend and resume the laptop; toggle Bluetooth off and on; unplug and replug a USB adapter: the daemon recovers by itself (it may log a warning once; it must not exit or spam).
- [ ] Service at login: reboot, log in; `systemctl --user status openhotspot` is active. **Wi-Fi join from the service**: confirm the join works from the user service (polkit finds your session) and not just from a terminal. If the daemon logs *Not authorized*, record the polkit message.
- [ ] If the keyring is locked at login the service reports a key store error rather than crashing in a loop (see SETUP.md: `--keystore file`).
- [ ] No secret in the journal: `journalctl --user -u openhotspot | grep -i -E 'passphrase|password'` shows no passphrase value (messages may mention the word only).
- [ ] **Power**: with `powertop` or `upower --dump`, compare battery drain over 30 minutes offline with the daemon in `auto` and `off`, with the phone out of range. Record the difference and the radio duty cycle seen in `btmon`.

### 8. Tray and notifications

- [ ] `systemctl --user disable --now openhotspot` then `openhotspot daemon --tray` in a terminal: an icon appears (KDE/XFCE directly; GNOME with the AppIndicator extension). Its colour changes with state: grey searching, amber phone nearby, blue connecting, green connected or online, red error.
- [ ] Menu: *Connect now* connects even in manual mode; the mode choices change behaviour; *Quit* ends the process cleanly (no zombie thread, exit code 0).
- [ ] `install.sh --tray` and a re-login: the tray service starts after the desktop is up (`systemctl --user status openhotspot-tray`) and `openhotspot.service` is disabled.
- [ ] Wayland-only session without XWayland: record whether pystray picks a backend (the doctor shows the tray extra as installed even if no backend works; a failing tray prints a clear *tray unavailable* error, stops the daemon again and exits with code 1, so use the plain service there).
- [ ] Notifications show with the OpenHotspot name and the network-wireless icon; with `notify-send` removed from `PATH` the daemon still works.

### 9. Install and uninstall (real systemd)

- [ ] `packaging/install.sh` on a clean account: service enabled and started, `openhotspot` on `PATH`, config written. Re-running it is harmless and keeps your config.
- [ ] `packaging/uninstall.sh`: service gone, `~/.local/bin/openhotspot` gone, `openhotspot-*` Wi-Fi profiles gone, config and keys kept; `--purge` removes them.

### 10. Interoperability with the Kotlin phone app

- [ ] Replay the three exchanges against the real app and compare with `docs/test-vectors.json`: pairing (`PAIR_REQ`/`PAIR_RESP`), `ENABLE`/`ENABLE_RESP`, an unauthenticated error. A mismatch is a protocol bug: fix PROTOCOL.md and the vectors first (CLAUDE.md rule).
- [ ] `QUERY` while the hotspot is `STARTING` returns the state and, once ON, the credentials.
- [ ] A request sent twice fast: the second is `BUSY` or processed in order; never two enables.


### 11. Android tablet (nothing here has been done yet)

Automated (`./gradlew :tablet-app:testDebugUnitTest`): the BLE client against the **real** `PhoneEngine` through an in-memory transport (pairing, ENABLE/QUERY, MTU 23 chunking, replay refusal, counters stored before sending), the connect flow (retry with a new counter, STARTING polling, no-internet handling), the controller (busy, cancel, disconnect, network lost), the auto-trigger (cooldown, back-off, never scans online) and the encrypted store. Observed on an emulator (Android 36, Google APIs): the app launches, Settings and Home render, no crash. **Not covered anywhere:** `GattTransport`, both Wi-Fi joiners, the camera and ML Kit decoding, the Quick Settings tile, the foreground service, permissions flow.

On a real tablet and phone, tick only when the expected result happened:

- [ ] Pair by camera QR; the phone lists the tablet; kill and reopen the app: the phone is still listed (Keystore store works).
- [ ] `adb logcat -s OpenHotspot`: connect log shows no SSID or passphrase.
- [ ] MTU: record the negotiated value (expected 247 or more on Android 14+).
- [ ] Specifier: the system prompt appears, approving joins, the internet check passes, `adb shell dumpsys connectivity` shows the bound network; **a browser on the tablet does not use the hotspot** (documents the limitation). Declining the prompt gives the "Could not join" error, not a hang.
- [ ] Suggestion: approval notification, connection within a minute, a browser uses the hotspot; Disconnect removes the suggestion and the tablet leaves.
- [ ] Hidden SSID, WPA3-only, 5 GHz, non-ASCII SSID (specifier refuses non-UTF-8, suggestion on 13+ works).
- [ ] Tile: connect, disconnect, behaviour with no paired phone, on the lock screen.
- [ ] Auto-connect: turn Wi-Fi off, phone nearby: connects within about 90 s; cooldown respected after you disconnect; no scanning while online (`adb shell dumpsys bluetooth_manager` scan stats); screen off for 30 min; reboot (service restarts); force-stop (does not, expected).
- [ ] Auto-connect with the specifier method from the background: record whether the prompt shows (SETUP.md says it may not).
- [ ] Phone without data: the auto attempt undoes the join and switches the phone's hotspot off.
- [ ] Android 11 (API 30) device: scan works with Location on and fails clearly with it off.
- [ ] **Samsung One UI**: each statement in the SETUP.md Samsung notes: *Sleeping apps* effect on auto-connect, Intelligent Wi-Fi interference, Auto Hotspot on a Samsung phone, prompt in the background, scan survival after swiping the app away. Record One UI and Android versions.
- [ ] A device without Google Play services (for example a de-Googled ROM): QR scanning still works (the bundled ML Kit model is in the APK; this is unverified on such a device).
