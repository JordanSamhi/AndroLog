# AndroLog desktop

Instrument APKs, place custom log messages, and explore runtime coverage in one window. The Electron interface runs AndroLog's Java backend in separate local processes and uses its Soot/Jimple code browser. Application source code and JADX are not required.

## Install and launch

For a source installation, install:

- **JDK 17 or newer** and **Maven** to build and run the backend.
- **Node.js 22.12 or newer** and **npm** to run the interface.
- **Android SDK Platforms**, including at least one platform with `android.jar`.
- **Android SDK Build Tools** for APK alignment and signing, and **Platform Tools** (`adb`) to install APKs and capture logs from a device or emulator.

From a new checkout:

```bash
git clone https://github.com/JordanSamhi/AndroLog.git
cd AndroLog
mvn package
cd electron-gui
npm ci
npm start
```

For an existing checkout, start at `mvn package`. On later launches, run `npm start` in `electron-gui`. Rebuild the backend from the repository root after Java changes. The same source commands apply on macOS, Windows, and Linux.

If you have a packaged AndroLog app, extract its archive and launch the app instead. Packaged apps bundle Electron and Java: Node.js, npm, Maven, and a separate JDK are not needed to run them. You still need the Android SDK. See [packaging](#package-for-your-platform) to create an archive; macOS packaging has been tested, while Windows and Linux packages still need validation on their target systems.

## Set up the Android SDK

Open **Settings** at the bottom of the sidebar. AndroLog checks `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and common SDK installation locations automatically. Confirm or change these paths:

| Field | Example within your SDK | Purpose |
| --- | --- | --- |
| **Android SDK platforms** | `sdk/platforms` | Resolves Android classes. This folder must contain an installed platform such as `android-36/android.jar`. |
| **Android Build Tools** | `sdk/build-tools/<version>` | Provides `zipalign` and `apksigner` for instrumentation. Select a version folder, not the parent `build-tools` folder. |
| **Java executable** | Leave empty, or select `bin/java` (`java.exe` on Windows) | Source runs use `JAVA_HOME` or Java on `PATH`; packaged apps use their bundled runtime. An explicit path overrides that default. |

Install missing packages using Android Studio's SDK Manager or the Android SDK command-line tools. Save your settings. APK analysis needs Platforms; instrumentation also needs Build Tools. GUI signing settings are separate from the CLI's `config.properties`.

SDK preferences are stored in the application's user-data directory, outside the repository. Coverage does not depend on a local custom-point catalog. Each instrumentation or analysis job runs in a separate Java process. **Run details** in the upper-right corner shows its diagnostics.

## Instrument an APK

1. Open **Instrument** and choose a complete, original application APK.
2. Set the **Logcat tag**, which defaults to `ANDROLOG`.
3. Choose what to log: **Classes**, **Methods**, **Statements**, **Components**, or **Dynamic call graph**. **Essentials** selects classes, methods, and components; **Everything** selects all five. Package prefix and library exclusion settings are under **Advanced options**.
4. Optionally add [your own messages](#place-your-own-messages).
5. Click **Instrument APK**. The scanning APK animation and stage label show activity while the backend adds probes, rebuilds, aligns, and signs the APK. Animations respect the operating system's reduced-motion setting.
6. Click **Save instrumented APK** and choose its destination. The original APK is protected; the output uses AndroLog's bundled test signing key. The saved APK and its Logcat tag are selected automatically in Coverage.

Save the output before choosing another APK or closing the app. The GUI warns if you have an unsaved instrumented APK. Use a test device or emulator: an existing installation signed by the original publisher may conflict with the test-signed copy.

### Add custom logs

Open **Custom logging** to inspect the APK. Expand the nested **package → class → method** tree, choose a method, and select a statement in its class's Jimple listing. Choose one of two modes:

- **Statement reached**: type a literal message such as `reached this point`. The report shows whether this point was reached.
- **Variable value**: click **Choose variable** and select a local by name and type. The report shows whether the point was reached and its latest printed value.

Click **Use log points** to apply your selection. Each statement can have one custom log; you can edit, change its mode, or remove it. **Cancel** discards changes in the code browser.

The tree loads first. Class code is read when you open it, and variable availability is analyzed only when you choose **Variable value**. Results are reused while browsing the same APK, so reopening a class does not repeat the analysis.

Only locals initialized on every incoming path are offered, using their value **before** the selected statement. To observe the result of an assignment, select the following statement. For example, choosing `i3` captures `42` if that is its value when execution reaches the point. Existing plans that combine text and variables remain supported.

Supported types are `byte`, `short`, `int`, `long`, `float`, `double`, `boolean`, strings, and object or array references. APKs may expose names such as `i3` or `r2` instead of the original source names. Braces in statement-marker messages stay literal. Direct field selection, primitive characters, and expressions are not supported.

Object choices show **uses toString()**. The injected `CustomLogRuntime` helper handles conversion, escaping, and truncation. Null references produce `null`; nonfatal conversion failures produce `<toString failed>`, and nested object conversion produces `<recursive toString>`. Arrays use their ordinary object representation, without expanding their elements. Custom `toString()` methods execute application code and may have side effects or be slow; fatal VM errors are not suppressed. Newly allocated objects, including strings, become available only after their constructor completes successfully on every incoming path.

Choose **Custom only** if you want these messages without automatic probes. Explicit custom points are independent of the automatic package and library filters.

Each visit logs the message immediately **before** the selected statement executes, plus a unique point marker used by the GUI. Repeated visits capture fresh values, including before statements that subsequently throw. Message templates are limited to 1,000 UTF-8 bytes. String values are null-safe, line breaks are escaped, and long rendered messages are truncated with an ellipsis to fit a Logcat record. Setup and monitor statements cannot be selected.

The listing is Jimple reconstructed from APK bytecode, not the original Java or Kotlin source. Selections are bound to the original APK fingerprint and checked against the method and statement. Selecting a different APK clears them.

## Run the app and collect logs

After instrumentation, click **Test on device**. Choose a connected phone or emulator, then **Start test**. For a phone, enable USB debugging and accept the computer's authorization prompt; use **Refresh** if it does not appear ready. Android SDK Platform Tools (`adb`) are detected from the configured SDK or your environment.

AndroLog installs the instrumented APK, starts capture before launching the app, and displays **Recording** with an elapsed timer. Interact with the app normally, then click **Stop & analyze**. Coverage opens automatically using that test's APK, log file, and tag.

Every test saves a separate copy of the instrumented APK and `runtime.log` in the desktop app's data folder under `captures`. The session path appears in **Run details**. These files survive closing AndroLog and later instrumentations. Cancelling a test or losing the device connection keeps any partial log for manual analysis. The app is restarted for capture; installation conflicts are reported without uninstalling an existing app. Capture excludes older buffered records without clearing the device's logs.

### Manual capture

After saving, AndroLog shows commands using the actual saved path and Logcat tag. Use **Copy** beside either command to copy it to your clipboard. For example, in a terminal with `adb` on `PATH`:

```bash
adb install -r "/path/to/app-instrumented.apk"
adb logcat -v threadtime -s ANDROLOG:V > androlog-runtime.log
```

Keep capture running, launch the app in a fresh process, and exercise the behavior you want to measure. Press **Ctrl+C** to stop capture. Use the tag you selected during instrumentation. On Windows, run the capture command in Command Prompt; the GUI provides a `cmd /c` form for other shells.

Start capture before launching the app because some automatic probes are emitted only once per process. Logcat can include previously buffered matching records; if you want to clear those before a new test session, run `adb logcat -c` first. That command clears the device's log buffers.

The CLI's [`--capture` workflow](../docs/cli.md#automatic-capture) also automates installation, launch, capture, and report generation.

## Analyze coverage

Open **Coverage**, select the **instrumented APK that was installed for the test** and its **runtime log**, enter the same Logcat tag, and click **Analyze run**. These are the only file inputs. You can copy both files to another computer and analyze them there; no original APK, saved baseline, or instrumentation history is required.

AndroLog reads the expected probes and custom points directly from the APK. Coverage uses the probes actually inserted, so you do not need to reselect automatic probes, package scope, or library settings. The original APK selected in Instrument is separate from the APK selected in Coverage. Clicking **Analyze run** again re-reads both files.

The dashboard shows observed counts, totals, and percentages for statements, methods, classes, activities, services, broadcast receivers, and content providers. Elements without probes are **not measured**. AndroLog's logging instructions and helper classes are excluded from statistics and the code display. A category with no eligible elements is unavailable, rather than reported as 0% coverage. Select a statistics card to explore its category in the report.

After analysis, **Custom logs** separates **Statement markers** from **Variable values**. Each row shows its method/statement location and a green **Reached** or red **Not reached** status. Variable rows also show the **Latest value** printed during the run. If a point marker was captured but its value is missing from the log, the report says **Reached · value not captured**. Points are listed even when none appear in the log. Click a row to jump to its inline annotation in **Explore report**. Custom-only instrumentations work the same way; point kinds, templates, and locations are recovered from the APK without the original plan.

Current GUI instrumentations include a distinct point marker, allowing identical messages at different locations to be distinguished. Older instrumentations may emit only message text: if the same message appears at several points and was observed, the individual points are **Unconfirmed** rather than guessed to be covered or missed.

**Not covered means no matching observation was found in the supplied log.** It does not prove the point is unreachable: capture may be incomplete, late, or from another instrumentation. Use the exact APK installed for the test, even if another instrumentation later overwrote the same output filename. Automatic coverage counts distinct observed elements, not execution frequency.

## Explore and export

**Explore report** embeds the interactive HTML report in the same window:

- Expand packages and select a class; its methods appear in the tree for quick navigation.
- Read the whole class in one scrollable listing, follow code references, and filter covered or unobserved code.
- See custom messages beside their statements, with green/red markers independent of automatic statement coverage. A location outside the report scope is marked unavailable rather than attached to different code.
- Inspect cumulative coverage over time when the log includes timestamps.
- Explore the **dynamic call graph**. Enable its probe before instrumentation to collect the required records. The graph starts from a manifest-declared launcher activity when recorded calls are available. Selecting a class changes the graph's scope; selecting a node emphasizes its incoming and outgoing edges and dims the others.

Use **Export HTML** to save a standalone report that opens offline in a browser. It includes available custom-point feedback and links to the exact statements, so the exported report can be shared on its own.

## Package for your platform

From the repository root, build the backend with `mvn package`. Then:

```bash
cd electron-gui
npm ci
npm run dist
```

The script uses `jlink` from `JAVA_HOME` (or `PATH`) to bundle a Java runtime. Use a **full JDK 17+**, and build on the **target operating system and architecture**. The JDK architecture must match Node and Electron. Cross-platform or mismatched architecture builds are rejected.

Archives are placed in `target/electron/`:

| Platform | Output | Launch after extraction |
| --- | --- | --- |
| macOS | `.zip` | Open `AndroLog.app`; it can be moved to Applications. |
| Windows | `.zip` | Open `AndroLog.exe`. |
| Linux | `.tar.gz` | Run the included application executable. |

On macOS, the default build uses an ad-hoc signature for local testing. It is not notarized. Public distribution with normal Gatekeeper trust requires an Apple Developer ID and notarization. Configure electron-builder's signing environment for your distribution setup. Windows and Linux packages should be built and verified on their respective systems before distribution.

For an unpacked local app image, use `npm run dist:dir`; the script prints its temporary build path. Android SDK packages remain separate from all desktop bundles.

## Troubleshooting

| Problem | Check |
| --- | --- |
| Backend JAR is missing | Run `mvn package` from the repository root before starting or packaging the GUI. |
| APK cannot be read | Select the SDK `platforms` folder in Settings and confirm it contains `android-<version>/android.jar`. Inspect **Run details** for the specific failure. |
| Signing fails | Select an installed Build Tools version containing both signing tools. |
| Installation reports a signing conflict | The installed app uses another signing key. Use a clean test device or emulator installation. |
| Coverage is empty or unexpectedly low | Check that you selected the instrumented APK used for this test and the matching tag. Capture before launching a fresh process. |
| No dynamic call graph | Instrument with **Dynamic call graph** enabled, then install and capture that APK. Selecting it only during analysis cannot create missing records. |
| Custom messages have no locations | Analyze the instrumented APK and its log. Locations are read from the APK; old logs with duplicate message text may be unconfirmed. |
| Packaging reports an architecture mismatch | Use a JDK and Node installation built for the same architecture as the target machine. |

## Development checks

From `electron-gui`:

```bash
npm test
```

From the repository root:

```bash
mvn test
node --test src/test/js/report-links.test.js src/test/js/report-graph.test.js
```

Tests cover backend arguments and cancellation, file safety, custom-point validation and matching, renderer workflows, and report navigation. Real APK instrumentation also depends on the Android SDK and should be exercised on target devices.

## Earlier Java GUI

The earlier Swing interface remains available. After `mvn package`, launch it from the repository root with `./androlog-gui` (`androlog-gui.bat` on Windows), or:

```bash
java -jar target/androlog-gui-0.1.jar
```

For command-line instrumentation and reporting, see the [CLI guide](../docs/cli.md).
