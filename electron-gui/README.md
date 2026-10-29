# AndroLog Electron desktop

A cross-platform desktop interface for AndroLog. It runs the existing Java backend in separate local processes; it does not require JADX. The existing Swing GUI and command-line interface remain available.

## Requirements

- Node.js 22.12 or newer and npm, for building or running from source.
- JDK 17 or newer and Maven, to build AndroLog. A full JDK with `jlink` is needed to package a desktop application.
- Android SDK Platforms, with at least one installed `android.jar`. APK instrumentation additionally needs an Android SDK Build Tools version containing `zipalign` and `apksigner`.

From the repository root:

```bash
mvn package
cd electron-gui
npm ci
npm start
```

The development app uses `JAVA_HOME`, or Java on `PATH`; you can choose a Java executable in Settings. The app discovers common SDK locations and lets you choose the Platforms and Build Tools folders separately. Preferences are saved in the Electron user-data directory, not in the repository.

## Workflows

- **Instrument:** select an original APK, choose automatic probes and optional package scope, and instrument. The APK illustration follows the actual analysis, modification, rebuild, and signing stages and respects reduced-motion settings. The output is signed with AndroLog's test key. Save it under a different filename; the original APK is protected.
- **Custom messages:** choose **Your own messages**, browse the APK's packages and whole-class Jimple code, select a statement, and type a literal single-line message. Custom points are bound to the APK fingerprint and can be combined with automatic probes. They emit on every visit; they do not evaluate variables.
- **Coverage:** select the original APK and a captured runtime log. Select the same probes, Logcat tag, package scope, and library settings used for instrumentation, then choose **Analyze run**. The coverage cards show matched observations and totals. Zero total is shown as unavailable, not 0% coverage. Custom messages in the selected log are listed separately and can be refreshed without running coverage analysis.
- **Explore report:** browse the complete interactive AndroLog report inside the app, including covered/uncovered class code, package navigation, a cumulative coverage timeline when log timestamps are available, and the dynamic call graph. Instrument with **Dynamic call graph** enabled to collect call records. Export a standalone HTML copy when needed.

The app does not currently capture logs from a device; collect a Logcat file with the existing CLI or `adb` and open it in Coverage. Clicking **Analyze run** again re-reads the log. Logs, the original APK, and matching instrumentation settings are required for meaningful coverage.

## Package for your platform

First run `mvn package`, then from `electron-gui`:

```bash
npm ci
npm run dist
```

The packaging script uses `jlink` from `JAVA_HOME` (or `PATH`) to bundle a Java runtime. Build on the **target OS and architecture**; it rejects mismatched cross-builds so the runtime and Electron binary cannot silently differ. Output archives are placed in `target/electron/`. The Android SDK remains a separate requirement.

On macOS, when no signing identity is configured, the script uses an ad-hoc signature for local testing. An ad-hoc build is not notarized; distributing it without Gatekeeper warnings requires an Apple Developer ID and notarization. To use a signing identity, configure the standard electron-builder signing environment. Windows and Linux packages are likewise built on their respective platforms and should be verified there before distribution.

For an unpacked local app image, use `npm run dist:dir`; the script prints its temporary build path. Electron and its Java runtime make the package larger than the Java-only GUI.

## Tests

```bash
npm test
# From the repository root:
mvn test
node --test src/test/js/report-links.test.js src/test/js/report-graph.test.js
```

The Electron tests cover process arguments and cancellation, file safety, custom-point validation, the interface workflow, and the embedded report bridge. Real APK instrumentation also depends on the Android SDK and should be tested on the target platforms.
