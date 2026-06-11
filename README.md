# HID Barcode Scanner — TCP Transport Plugin

**External-output plugin** for the [HID Barcode Scanner](https://github.com/Fabi019/hid-barcode-scanner)
app. The core app stays focused on scanning + Bluetooth HID; non-Bluetooth transports live in
separate, independently installable plugin apps like this one.

## How it works

The core app broadcasts each scan; this plugin receives it and (optionally) reports the result back.

### Contract (must match the core's `ExternalProtocol` — see `Protocol.kt`, protocol version 1)

- Receive: action `dev.fabik.bluetoothhid.action.BARCODE_SCANNED`
  - extras: `scan_id`, `raw_value`, `processed_value`, `protocol_version`, `format`, `timestamp`, `source`, …
- Report back: action `dev.fabik.bluetoothhid.action.SEND_RESULT`
  - extras: `package` (sender, for multi-plugin attribution), `scan_id` (echo), `result_ok`
    (Boolean), `result_detail` (String?), `protocol_version` (Int)
- Status: action `dev.fabik.bluetoothhid.plugin.action.STATUS`
  - extras: `package`, `running` (Boolean), `state` (String, machine-readable: `IDLE`/`CONNECTING`/
    `CONNECTED`/`LISTENING`/`BLOCKED`/`NO_PERMISSION`/…), `status_detail` (String?, localized,
    display-only — the core never parses it), `protocol_version` (Int)
- No backward-compat fallbacks: core and plugin are released together; a version mismatch renders
  as "update the plugin" in the core's picker.
- The plugin must declare `<uses-permission android:name="dev.fabik.bluetoothhid.permission.RECEIVE_SCANS" />`
  and a `<meta-data android:name="dev.fabik.bluetoothhid.plugin.label" .../>` on its receiver
  (used as the display name in the core's plugin picker). An optional
  `dev.fabik.bluetoothhid.plugin.shortLabel` (e.g. `"TCP"`) is used where space is scarce —
  the core's per-scan result snackbar shows `Plugin <shortLabel>: <result_detail>`.

## Trust model & data format

This is a **plain-TCP transport for a local network**: barcode values travel unencrypted and
unauthenticated, and in server mode the socket listens on all interfaces. That is a deliberate
fit for its use case (controlled devices forwarding non-secret scan values to a POS/ERP on the
same LAN) — do not point it across untrusted networks.

- The inbound broadcast channel is gated by the core's `RECEIVE_SCANS` permission
  (`protectionLevel="normal"`): any installed app that explicitly requests that permission could
  send this plugin data. On managed/controlled devices this is acceptable; it is not a boundary
  against hostile co-installed apps.
- **The plugin is a pure transport**: it forwards `processed_value` byte-for-byte (UTF-8) and
  appends nothing. A line terminator is configured **in the core app** — either the
  *Extra keys* setting (Enter/Tab/Space) or a custom template like `{CODE}{ENTER}` (→ `\r\n`).
- Inbound TCP data is diagnostic only (shown as a toast, never forwarded). The *Event log* tab
  records transport events and a self-check (permission grant, core app present, notifications,
  network, config) on every start.
- If the **core app is reinstalled**, Android revokes this plugin's `RECEIVE_SCANS` grant and the
  core's broadcasts silently stop arriving. The plugin detects this (`NO_PERMISSION` state, shown
  in both apps) — reinstalling the plugin fixes it.

## Build

- **Android Studio:** open the project, set *Gradle JDK* to JBR 21, Run.
- **CLI:** `./gradlew assembleDebug` (needs a JDK 21; set `org.gradle.java.home` in your global
  `~/.gradle/gradle.properties` if your default JDK isn't 21).
- **CI:** GitHub Actions builds the debug APK on every push (see `.github/workflows/build.yml`).

## Use

1. Install this plugin and the core scanner app.
2. In the scanner: **Settings → Connection → mode "External"**, then enable this plugin in the list.
3. Scan something → a Toast pops here and the core shows a `sent` status.
