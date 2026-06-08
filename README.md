# HID Barcode Scanner — TCP Transport Plugin

**External-output plugin** for the [HID Barcode Scanner](https://github.com/Fabi019/hid-barcode-scanner)
app. The core app stays focused on scanning + Bluetooth HID; non-Bluetooth transports live in
separate, independently installable plugin apps like this one.

## How it works

The core app broadcasts each scan; this plugin receives it and (optionally) reports the result back.

### Contract (must match the core's `ExternalProtocol`)

- Receive: action `dev.fabik.bluetoothhid.action.BARCODE_SCANNED`
  - extras: `scan_id`, `raw_value`, `processed_value`, `format`, `timestamp`, `source`, …
- Report back: action `dev.fabik.bluetoothhid.action.SEND_RESULT`
  - extras: `scan_id` (echo), `result_ok` (Boolean), `result_detail` (String?)
- The plugin must declare `<uses-permission android:name="dev.fabik.bluetoothhid.permission.RECEIVE_SCANS" />`
  and a `<meta-data android:name="dev.fabik.bluetoothhid.plugin.label" .../>` on its receiver
  (used as the display name in the core's plugin picker).

## Build

- **Android Studio:** open the project, set *Gradle JDK* to JBR 21, Run.
- **CLI:** `./gradlew assembleDebug` (needs a JDK 21; set `org.gradle.java.home` in your global
  `~/.gradle/gradle.properties` if your default JDK isn't 21).
- **CI:** GitHub Actions builds the debug APK on every push (see `.github/workflows/build.yml`).

## Use

1. Install this plugin and the core scanner app.
2. In the scanner: **Settings → Connection → mode "External"**, then enable this plugin in the list.
3. Scan something → a Toast pops here and the core shows a `sent` status.
