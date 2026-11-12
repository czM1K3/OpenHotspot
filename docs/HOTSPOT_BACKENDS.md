# Hotspot backends

How the phone app turns the hotspot on and off, what was verified, and what is a guess.

**Real-device result (Nothing A059 "Asteroids", Android 17, Nothing OS C5.0-261023-2146, Shizuku API 14 via adb, 2026-11-12):**
`ShizukuTetheringBackend` **works**: hidden-API exemption ok, `isTetheringSupported()` true as `com.android.shell`, hotspot on in ~170 ms, credentials read from the saved config (WPA2/WPA3, 2.4 GHz), `onClientsChanged` counted a joining client (1), auto-off paused and resumed correctly, `stopTethering` turned it off (3 runs, all PASS). Shell backend: first probe failed with a bug of ours (`ShizukuRemoteProcess.waitFor(timeout)` throws `process hasn't exited`), fixed. Local-only: **works** (hotspot on in ~170 ms, WPA2/WPA3, no band/client info, auto-off correctly disabled). `cmd wifi help` prints its help but **exits 255** on Android 17, and the 12.5k-character help text does **not** contain `start-softap` (AOSP lists it near the end, so the output looks truncated or trimmed on this build); the probe now confirms the command with a harmless bare call instead. Shell backend start/stop: **re-test pending**. Whether the joined client had internet was not recorded. Sections below still say UNVERIFIED for anything not listed here.

**Verification legend**
- **Source-verified**: read in the AOSP source of that release branch (`android11..17-release`, fetched from android.googlesource.com) or in the Shizuku 13.1.5 jars (`javap`).
- **Unit-tested**: covered by JVM tests with mocks (`phone-app/src/test/.../hotspot`). This proves our selection, fallback, parsing and timer logic, **not** that Android behaves as assumed.
- **Emulator-run**: executed on the Android 36 emulator (no Shizuku, no hotspot-capable Wi-Fi).
- **UNVERIFIED**: never run on a device. **Nothing in this file has been run against a real Shizuku or a real hotspot yet.**

## Architecture

```
PhoneEngine --(HotspotController)--> RealHotspotController
                                      |- BackendSelector (probe in order, record why)
                                      |- AutoOffMonitor  (client count -> idle timer)
                                      |- credentials: system config / user entry
                                      '- backends, tried in this order:
                                           1. ShizukuTetheringBackend
                                           2. ShellCommandBackend
                                           3. LocalOnlyBackend      (no internet; can be disabled in Settings)
```

- **Selection** (`refresh()`): every backend is probed (never toggles anything); the first that is available is "selected". The result, with a reason per backend, is shown on the Status and Diag tabs and written to the log. It re-runs at app start and whenever Shizuku appears, dies or is (de)authorized.
- **Fallback** (`enable()`): the available backends are tried in order. On a start failure the half-started backend is stopped and the next one is tried. A missing-credentials failure does **not** fall through (it would silently produce a no-internet hotspot). If the protocol's 20 s limit cancels the request, the backend is stopped.
- **Errors** map to protocol codes: Shizuku not running/too old -> `SHIZUKU_UNAVAILABLE`; not authorized or OS refusal -> `PERMISSION_DENIED`; everything else -> `HOTSPOT_FAILED`.

## Backend 1: `ShizukuTetheringBackend` (preferred)

Starts the hotspot like the Settings toggle: `TetheringManager.startTethering(TETHERING_WIFI)`, which makes the Tethering module call `WifiManager.startTetheredHotspot(null)` (the saved hotspot config) and set up DHCP/NAT. This is the only backend expected to give clients internet on every version.

| Step | Detail | Status |
|---|---|---|
| Binder | `SystemServiceHelper.getSystemService("tethering")` wrapped in `ShizukuBinderWrapper`; calls execute as the Shizuku server (shell UID 2000, or root) | Service name source-verified for Android 11 only (`SystemServiceRegistry`); **UNVERIFIED for 12-17** (believed unchanged: `Context.TETHERING_SERVICE`) |
| Manager | `new TetheringManager(Context, Supplier<IBinder>)`, public ctor, `@hide`, same signature in 11, 12, 13, 14, 15, 16, 17 | Source-verified |
| Identity | The ctor/methods read only `getOpPackageName()` and `getAttributionTag()` from the Context; we pass `com.android.shell` (UID 2000) or `root`. Needed because `TetheringService.checkPackageNameMatchesUid` rejects a package that does not belong to the calling UID | Source-verified (11, 16, 17); **whether the shell package passes on Nothing OS is UNVERIFIED** |
| Permission | Shell's manifest holds `TETHER_PRIVILEGED` and `NETWORK_SETTINGS` (Android 11, 14, 16) | Source-verified |
| Start | `startTethering(TetheringRequest, Executor, StartTetheringCallback)`; request = `Builder(TETHERING_WIFI)` exempt from entitlement check, no entitlement UI. Same signature 11-17. Then wait for the AP: callback, else poll `IWifiManager.getWifiApEnabledState()==13` | Signatures source-verified; behaviour **UNVERIFIED** |
| Stop | `stopTethering(int)` (exists 11-17; the `(request,...)` overload is Android 16+ and flag-gated, not used) | Source-verified |
| Probe | `isTetheringSupported()` is a blocking round trip to the tethering service as the shell identity: proves the privileged path without toggling | Exists 11-16 (verified); **result on device UNVERIFIED** |
| Clients / state | `registerTetheringEventCallback` with a `java.lang.reflect.Proxy`: `onClientsChanged(Collection<TetheredClient>)` (counts `getTetheringType()==0`) and `onTetheredInterfacesChanged` (`Set<TetheringInterface>` on 12+, `List<String>` on 11) | Signatures source-verified; **UNVERIFIED** at runtime |
| Hidden API | Reflection happens in **our** process, so `HiddenApiBypass.addHiddenApiExemptions("Landroid/net/", "Landroid/os/ServiceManager")` is called first | **UNVERIFIED, highest risk, esp. Android 17**. If blocked, the probe reports `API_UNAVAILABLE` with the exception |

Differences between versions that matter here: Android 11 has no `Set<TetheringInterface>` callback and the Tethering module lives in the framework (not Mainline); 12+ adds attribution tags; 16+ adds `TetheringRequest.setSoftApConfiguration` (flag `tethering_with_soft_ap_config`), which we do not use (the saved config is used). Android 17's `TetheringManager` surface is unchanged for everything we call.

Reported credentials: the saved config read via `IWifiManager.getSoftApConfiguration()` (see below).

## Backend 2: `ShellCommandBackend` (fallback)

Runs `cmd wifi ...` through `Shizuku.newProcess` (private static in API 13.1.5, reached by reflection; arguments are an array, no shell, so no quoting issues).

| Command | Android | Notes |
|---|---|---|
| `cmd wifi start-softap <ssid> (open\|wpa2) <pass> [-b 2\|5\|6\|any]` | 11 | Wraps the SSID in literal quotes (AOSP bug). **Not used: backend requires 12+.** |
| `cmd wifi start-softap <ssid> (open\|wpa2\|wpa3\|wpa3_transition) <pass> [-b ...\|bridged...]` | 12-15 | Waits for the SoftAp callback, prints ` SAP is enabled successfully` / ` SAP failed to start` |
| same, plus `owe`, `owe_transition`, `-x`, `-w`, `-f` | 16, 17 | |
| `cmd wifi stop-softap` | 11-17 | prints `Soft AP stopped successfully` |
| `cmd wifi help` | all | used by the probe: must list `start-softap` |
| `cmd connectivity ...` | 12-17 | **Cannot control tethering.** Commands: `airplane-mode`, `set/get-chain3-enabled`, `set/get-package-networking-enabled`, `set-background-networking-enabled-for-uid`. Not used. |
| `svc ...` | all | `svc` has `wifi`, `data`, `bluetooth`, `usb`, `nfc`, `power`; no tethering. Not used. |

All source-verified except for the command **output on the device**. Parsing is unit-tested against the strings in the source.

**Does the AP share internet?** The help text of every branch says: *"the shell command doesn't activate internet tethering"*. What the source shows:
- Android 12-15: `startTetheredHotspot` raises `WIFI_AP_STATE_CHANGED` with mode TETHERED and the Tethering module serves IP on it (`handleWifiApAction -> enableWifiIpServing`). Probably gives DHCP and NAT, **UNVERIFIED**.
- `android16-release`: `handleWifiApAction` returns early when `isTetheringWithSoftApConfigEnabled()`; the shell command alone likely gives **no** internet.
- `android17-release`: when `SdkLevel.isAtLeastB()` the command calls `TetheringManager.startTethering(Builder(TETHERING_WIFI).setSoftApConfiguration(config))`, so it should give internet.
This is why the Shell backend ranks below the TetheringManager one. Clients: **none** can be counted (no shell command lists them), so auto-off is unavailable with this backend.

Credentials policy: user entry first (they are passed on the command line, so visible in the process list for the call's duration), else the saved config.

## Backend 3: `LocalOnlyBackend` (last resort, NO internet)

`WifiManager.startLocalOnlyHotspot` (public API). SSID and passphrase are chosen by Android. Needs `NEARBY_WIFI_DEVICES` (13+, declared `neverForLocation`) or `ACCESS_FINE_LOCATION` + Location on (12 and older); the Settings tab has a grant button. The UI shows a red "No internet sharing" card whenever it is selected or active, and Settings can disable it. A client that joins gets a Wi-Fi link only. **Protocol gap:** the wire format has no "no internet" flag, so a client cannot tell (see Next steps).
Emulator-run: probe, permission gating and failure reporting work; the emulator's virtual Wi-Fi returns `ERROR_GENERIC`, so a successful start is **UNVERIFIED**.

## SSID and passphrase

1. **Read** from the phone (`IWifiManager.getSoftApConfiguration()` over the wrapped `wifi` binder, reflection). Signature has no arguments in 11-17 (source-verified). The service requires `NETWORK_SETTINGS` (source-verified in `WifiServiceImpl`, Android 14), which shell holds. Android 13+ SSID is read as raw bytes (`WifiSsid.getBytes`). OPEN/OWE configs are refused (no passphrase; protocol section 9). Runtime result **UNVERIFIED**; whether the passphrase is returned unredacted on Nothing OS is unknown.
2. **User entry** (Settings tab): SSID + passphrase + security, stored Keystore-encrypted (`HotspotPrefs`). There is also a button that reads from the phone and fills the form.
3. Precedence: tethering backend = system first (the OS starts from its saved config, so we must report that), user entry as a warned fallback; shell backend = user first. Local-only = its own.

## Auto-off

Settings: minutes with no connected client (default 10, 0 = never). A client's `auto_off_s` is honoured but the shorter of request and setting wins (a client can shorten, never extend); clamped to 60 s..24 h. The timer pauses while clients are connected and restarts from zero when the last one leaves. If the backend cannot count clients (`null`), no timer runs and the UI says so; turning the hotspot off under connected clients is considered worse. Timer lives in the app process: if Android kills the process, the hotspot stays on.

## Shizuku status in the UI

Not installed / not running / too old / running but not authorized (with "authorize" button, or "open Shizuku" when the user chose "don't ask again") / ready (with server UID). Uses `Shizuku.pingBinder`, `checkSelfPermission`, `requestPermission`, `shouldShowRequestPermissionRationale`, and sticky binder-received/dead listeners (`ShizukuProvider` in the manifest). Emulator-run for the "not installed" state only.

## Tested and untested

| Area | Evidence |
|---|---|
| Selection, probe timeouts, fallback chain, cancellation cleanup, credential policy, error mapping, auto-off timer (virtual time), diagnostics sequence and report | Unit-tested (77 tests in `:phone-app`) |
| Shell command construction (no injection through SSID/passphrase), output parsing, redaction | Unit-tested |
| Tethering backend logic with a mocked `TetheringApi` | Unit-tested |
| `ReflectiveTetheringApi`, `ReflectiveWifiBinderApi`, `ShizukuShellRunner`, `ShizukuGatewayImpl`, `LocalOnlyBackend` success path | **No automated test, never run against real Shizuku** |
| App launches, tabs render, Shizuku-missing and local-only-failure paths | Emulator (Android 36) |
| Everything on Nothing Phone (3a) / Nothing OS 5.0 / Android 17 | **UNVERIFIED** |

## Questions only a real device can answer

1. Does `ShizukuBinders.ensureHiddenApiAccess()` succeed on Android 17, and do `TetheringManager`/`IWifiManager` reflection calls then work?
2. Is the binder service name `tethering` right on 12-17, and does `isTetheringSupported()` return true with the `com.android.shell` identity?
3. Does `startTethering` start the AP and do clients get internet? How long does it take (budget: 9 s per backend, 20 s total)?
4. Does `getSoftApConfiguration()` return the real passphrase?
5. Does `cmd wifi start-softap` give clients internet on Android 17 (source says yes) and on 16?
6. Do the tethering callbacks report client counts and external on/off changes?

Run **Diag tab -> Test hotspot toggle** once per backend (use the chips to test one alone) and paste the exported report.
