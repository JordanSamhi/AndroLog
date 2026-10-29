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

- **Instrument:** select an original APK, choose automatic probes and optional package scope, and instrument. A compact Anime.js scanner and stage label show instrumentation activity and respect reduced-motion settings. The output is signed with AndroLog's test key. Save it under a different filename; the original APK is protected.
- **Custom messages:** choose **Your own messages**, expand the nested package → class → method tree and browse whole-class Jimple code, select a statement, and type a literal single-line message. Custom points are bound to the APK fingerprint and can be combined with automatic probes. They emit on every visit; they do not evaluate variables. The GUI also adds a unique point marker and stores build metadata locally, so points with identical messages remain distinguishable.
- **Coverage:** select the original APK and a captured runtime log. Select the same probes, Logcat tag, package scope, and library settings used for instrumentation, then choose **Analyze run**. An animated analysis indicator follows the backend stages. The coverage cards show matched observations and totals. Zero total is shown as unavailable, not 0% coverage. Custom logs show the message, its method and statement location, and a green **Covered** or red **Not covered** status. Matching point markers select the instrumented build automatically; a chooser appears only when the build is ambiguous. The metadata is saved locally and is matched to the original APK fingerprint and tag. Older APKs and builds instrumented elsewhere lack this metadata, so only their recorded messages can be listed. Not covered means no marker was found in the log, which may be incomplete or from a different build. Click a custom log to open its exact statement in Explore; AndroLog analyzes the run first when a report is needed.
- **Explore report:** browse the complete interactive AndroLog report inside the app, including covered/uncovered class code, nested package navigation with methods beneath the selected class, a cumulative coverage timeline when log timestamps are available, and the dynamic call graph. Instrument with **Dynamic call graph** enabled to collect call records. Custom messages appear beside their original statements with green/red markers, independently of automatic statement coverage. The custom-log list links to those exact locations in both the embedded report and exported HTML. A point outside the chosen report scope is reported as unavailable rather than attached to different code. Export a standalone HTML copy when needed.

The app does not currently capture logs from a device. After saving an instrumented APK, it shows example `adb install` and Logcat capture commands using the actual saved path and instrumentation tag. Run capture before starting the app in a fresh process, then open the resulting log in Coverage. Clicking **Analyze run** again re-reads the log. Logs, the original APK, and matching instrumentation settings are required for meaningful coverage.

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
