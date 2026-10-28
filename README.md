<p align="center">
  <img width="640" alt="AndroLog — Android execution analysis" src="data/androlog_logo_v2.png">
</p>

# AndroLog

**Instrument Android APKs, measure runtime code coverage, and explore dynamic call graphs.**

AndroLog inserts logging probes into an APK, then compares the original application with logs collected while the instrumented app runs. It works from APK bytecode; application source code is not required.

- **Coverage:** classes, methods, statements, activities, services, broadcast receivers, and content providers.
- **Dynamic call graphs:** executed callsites and confirmed runtime dispatch targets, including virtual and interface calls.
- **Interactive HTML reports:** package navigation, continuous class listings, coverage filters, clickable code references, and class-level graph exploration.
- **Terminal and JSON reports:** aggregate coverage and cumulative coverage by minute.
- **Integrated capture:** install and launch the instrumented app, collect logs, and generate reports with one command.
- **Desktop GUI:** choose an APK, select logging probes, follow instrumentation, and save a signed copy without composing a command.

[Wiki](https://github.com/JordanSamhi/AndroLog/wiki) · [Desktop GUI](#desktop-gui) · [Previews](#report-previews) · [Setup](#build-and-configure) · [Quick start](#instrument-run-and-report) · [Reports](#read-the-reports) · [Dynamic call graph](#dynamic-call-graph) · [Options](#command-line-reference) · [Troubleshooting](#troubleshooting)

The [AndroLog wiki](https://github.com/JordanSamhi/AndroLog/wiki) provides complete user and developer guides, including capture workflows, report navigation, coverage semantics, dynamic dispatch, JSON/log formats, and troubleshooting.

## Report previews

These screenshots show a report generated from an instrumented Android demo app running on an emulator. Click either image to view it at full resolution.

**Coverage explorer:** browse packages, open a whole class, and see covered statements in green and unobserved statements in red.

[![HTML coverage report with coverage totals, a package tree, and covered and unobserved Jimple statements](data/screenshots/coverage-report.png)](data/screenshots/coverage-report.png)

**Dynamic call graph:** explore the launcher activity's recorded calls. Solid edges show confirmed runtime targets; dashed edges retain declared targets when dispatch was not confirmed.

[![Dynamic call graph for MainActivity, including the confirmed interface dispatch to DoubleScorePolicy.apply](data/screenshots/dynamic-call-graph.png)](data/screenshots/dynamic-call-graph.png)

## Requirements

| Dependency | Used for |
| --- | --- |
| JDK 17 or newer and Apache Maven | Building and running AndroLog, including its AndroSpecter 2.0.0 dependency. The helper inserted into APKs retains Java 8 bytecode for Android compatibility. |
| Android SDK Platforms | Resolving Android classes during instrumentation and report generation. Install a platform containing `android.jar`. |
| Android SDK Build Tools | Aligning and signing instrumented APKs with `zipalign` and `apksigner`. |
| Android SDK Platform Tools (`adb`) and a device or emulator | Installing the instrumented APK and collecting runtime logs. |
| A browser | Opening the standalone HTML report offline. |

Node.js is needed only to run the JavaScript regression tests.

AndroLog uses AndroSpecter 2.0.0 with Soot 4.6.0 pinned for APK compatibility. The transitive Soot 4.7.1 backend caused constructor and register-verification failures in Word Snake runtime checks. Keep the Soot pin when rebuilding; upgrading it requires testing the resulting APKs on Android, as compilation alone does not catch these failures.

## Build and configure

### Build from source

```bash
git clone https://github.com/JordanSamhi/AndroLog.git
cd AndroLog
mvn package
```

The executable JAR, including dependencies, is written to:

```text
target/androlog-0.1-jar-with-dependencies.jar
```

Check the available options:

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar --help
```

## Desktop GUI

[![AndroLog desktop GUI with an APK drop area, setup steps, and SDK settings](data/screenshots/androlog-gui.png)](data/screenshots/androlog-gui.png)

Build with `mvn package`, then launch from the repository:

```bash
./androlog-gui
# Optionally open an APK immediately:
./androlog-gui /path/to/application.apk
```

On Windows, use `androlog-gui.bat`. The portable desktop JAR can also be launched directly:

```bash
java -jar target/androlog-gui-0.1.jar
```

1. **Choose an APK.** Browse for a file or drop one onto the window. Use a complete application APK, rather than a resource-only split.
2. **Choose your logs.** Toggle classes, methods, statements, Android components, and dynamic call tracing. **Essentials** selects classes, methods, and components; **Everything** selects all five. A package prefix, custom log tag, and exclusion of known libraries are also available.
3. **Instrument.** A floating bytecode-stack animation accompanies the actual loading, probe insertion, export, and signing stages. The display shows elapsed time, without estimating a completion percentage. You can cancel, or open **Run details…** to inspect and save the full run log.
4. **Save your APK.** Choose a filename and destination in the save dialog. AndroLog protects the original APK and asks before replacing another file. You can open the destination folder or instrument another app.

The GUI discovers the Android SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and common installation locations. Use **SDK settings** to choose the SDK, or the Platforms and Build Tools folders separately. The **?** button beside SDK settings and **What do I need?** inside the dialog explain which packages to install, which folders to select, and link to Android's setup guide. Animations can also be disabled in SDK settings. Preferences are stored in `~/.config/androlog/gui.properties`; signing paths are passed to the worker through temporary configuration, outside the repository. The GUI does not require the CLI configuration described below.

Each APK is processed in a separate JVM, keeping the interface responsive and isolating Soot state between runs. The signed APK and diagnostics stay in a temporary session until you start another run or close the app; save the files you want to keep. APKs use the same bundled test signing key as the CLI. Installation, capture, and report generation remain available through the [command line workflow](#instrument-run-and-report).

### Package a desktop application

With JDK 17 or newer providing `jpackage`, build a self-contained app for the current operating system:

```bash
mvn package
./scripts/package-gui.sh
# Or choose a destination folder:
./scripts/package-gui.sh /path/to/desktop-build
```

The script runs on macOS and Linux. On macOS it creates `target/desktop/AndroLog-macos.zip`: extract the archive into a local folder, such as Applications, and open `AndroLog.app`. Keeping the build archived prevents cloud-synced checkouts from attaching metadata that invalidates the app signature. On Linux the output is `target/desktop/AndroLog/`. Both bundle Java, including the executable used by instrumentation workers and SDK signing tools. Android SDK Platforms and Build Tools must still be installed separately. Existing packages are not overwritten. macOS packaging uses native bundle build number `1`, while the AndroLog engine remains version `0.1`.

This creates a local application image; it is not a Developer ID signed or notarized public installer. The macOS flow has been tested; the Windows launcher and Linux packaging still need validation on those systems.

## Configure command-line APK signing

APK instrumentation requires the paths to your own Android SDK signing tools. Report generation does not invoke these tools.

Keep machine-specific configuration outside the checkout. Create the directory:

```bash
mkdir -p "$HOME/.config/androlog"
```

Create `$HOME/.config/androlog/config.properties` with the following content, replacing both paths with executables from an installed Build Tools version:

```properties
zipalignPath=/absolute/path/to/Android/sdk/build-tools/VERSION/zipalign
apksignerPath=/absolute/path/to/Android/sdk/build-tools/VERSION/apksigner
```

Use absolute paths without surrounding quotes. Java properties do not expand `~` or shell variables such as `$HOME`.

Define this helper in your terminal, from the repository root. The configuration directory comes first on the classpath, so it overrides the bundled configuration:

```bash
androlog() {
  java -cp "$HOME/.config/androlog:target/androlog-0.1-jar-with-dependencies.jar" \
    com.jordansamhi.androlog.Main "$@"
}
```

The examples below use this helper in the same terminal. The shell examples use Bash/Zsh syntax and the Unix classpath separator (`:`).

Alternatively, configure `src/main/resources/config.properties`, rebuild, and run with `java -jar`. That file is tracked: do not commit local SDK paths. A JAR built with those paths also contains them; the external configuration above avoids embedding them in the JAR.

## Instrument, run, and report

The workflow is: **instrument an APK → exercise it on a device → collect logs → report against the original APK**.

Run these commands from the repository root. Set the two input paths for your environment:

```bash
androlog_sdk="/absolute/path/to/Android/sdk"
androlog_apk="/absolute/path/to/app.apk"
```

### Automatic capture

With a device or emulator connected, run the complete workflow with `--capture`:

```bash
androlog \
  -a "$androlog_apk" -p "$androlog_sdk/platforms" \
  -o ./out -l ANDROLOG \
  -c -m -s -cp -mc --capture
```

AndroLog instruments and signs the APK, installs it, starts log capture, and launches its manifest-declared launcher activity. Exercise the app on the device, then **press Enter in the terminal** to stop capture and generate the HTML and JSON reports, including a cumulative timeline.

Each run creates a fresh `out/capture-…/` session directory containing the instrumented APK under `instrumented/`, `runtime.log`, `coverage.html`, and `coverage.json`. Full build and report diagnostics are saved as `instrumentation.log` and `report-generation.log`. Use `-html` and `-j` to override the report destinations. Probe and scope options are reused automatically for reporting.

- Add `--device <serial>` when more than one device is connected. List devices with `adb devices`.
- Add `--duration 60` for a timed capture instead of waiting for Enter. The timer starts after launch; launch-time observations are included. Use this option when running without interactive input.
- Add `--adb /absolute/path/to/adb` if needed. Otherwise, AndroLog looks in the SDK associated with `-p`, then `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `PATH`.

Capture restarts the target app so its initial probes are recorded. It waits for logcat readiness before launch and excludes older buffered records without clearing the device's logs. Launcher aliases are supported. If the APK declares several launchers, the first sorted by target class and component name is used.

Installation uses `adb install -r`; a signing mismatch is reported without uninstalling the app. **Ctrl+C aborts the workflow and keeps the raw log**; it does not generate reports. A device disconnection or capture failure also keeps the partial log, which can be parsed later with the original APK, log tag, and probe/scope options. `--capture` cannot be combined with `-pa` or `-pam`.

To manage installation and log collection yourself, follow the three manual steps below.

### 1. Instrument the APK

This example enables all coverage categories and dynamic call tracing:

```bash
androlog \
  -a "$androlog_apk" -p "$androlog_sdk/platforms" \
  -o ./out/instrumented -l ANDROLOG \
  -c -m -s -cp -mc
```

AndroLog writes an aligned, signed APK to `out/instrumented/`, using the input APK's filename. The original APK stays unchanged. The output uses the bundled test signing key, so use a test device or emulator where it will not conflict with an installation signed by the original publisher.

Select only the probes you need. For example, use `-m` for method coverage or `-mc` alone for a dynamic-call-graph-only run. Adding `-pkg com.example.app` selects that exact package and its subpackages, excluding similarly named packages such as `com.example.application`; a trailing dot is accepted and normalized. This scope applies consistently to all probes and reports. `-n` excludes known libraries.

### 2. Install the APK and capture a test run

```bash
adb install -r "./out/instrumented/$(basename "$androlog_apk")"
adb logcat -c
adb logcat -v threadtime ANDROLOG:D '*:S' > ./out/runtime.log
```

`adb logcat -c` clears the device's existing log buffers for this capture. Leave the last command running, then launch the app and exercise the behavior you want to measure. Stop capture with **Ctrl+C** when finished.

Start capture before launching a fresh app process. Some probes and call records are emitted only once per process, so starting capture after the app has already run can miss them. Use the same tag in the logcat filter and AndroLog's `-l` option.

### 3. Generate the reports

Use the **original APK**, the same log tag, and the same probe and scope options used for instrumentation:

```bash
androlog \
  -a "$androlog_apk" -p "$androlog_sdk/platforms" \
  -l ANDROLOG -c -m -s -cp -mc \
  -pa ./out/runtime.log \
  -html ./out/reports/coverage.html \
  -j ./out/reports/coverage.json
```

| Output | Contents |
| --- | --- |
| Terminal | Coverage totals and dynamic call graph statistics, with or without file outputs. |
| `out/reports/coverage.html` | A standalone report with embedded data and scripts. Open it locally in a browser; no server or network connection is needed. |
| `out/reports/coverage.json` | Structured coverage totals and the full recorded dynamic call graph. |

Report directories are created automatically. Keep the APK version, tag, probes, and scope (`-pkg` and `-n`, if used) consistent between instrumentation and reporting.

To include a cumulative timeline, replace `-pa ./out/runtime.log` with `-pam ./out/runtime.log`. Use one parsing mode at a time. HTML and JSON output options require `-pa`, `-pam`, or `--capture`.

## Read the reports

### Explore coverage

The HTML report provides:

- An expandable **package tree** for choosing classes.
- One continuous **class listing**, with inline method declarations and a single scrollbar.
- Filters for **all, covered, or not-observed statements**.
- Links to classes, fields, methods, and constructors present in the report. Browser Back and Forward follow navigation history.
- Clickable statistics: component cards filter the class list; **Methods** adds an exact method selector; **Statements** selects classes with measured statements. Search works within the selected category.

**Classes** returns to class browsing. **Show all classes** also clears the search. Components that were not observed remain selectable.

The listing uses **Jimple**, Soot's intermediate representation of APK bytecode. Its line numbers identify the reconstructed listing, not the original Java or Kotlin source. Statement tooltips show their position within each method. Following a code reference switches to **All code** so the destination is visible.

### Interpret coverage states

| Statement state | Meaning |
| --- | --- |
| Covered | The matching probe was observed in the supplied logs. |
| Not observed | The baseline contains a probe, but its log was not observed. |
| Excluded | Identity, return, and monitor statements are excluded from statement instrumentation. |
| Not measured | Statement coverage was not selected, or the statement has no matching baseline probe. |

Use `-s` in both stages for statement coverage and `-m` for method coverage. Class and Android component coverage are measured by their own probes; one does not automatically imply the other. Terminal percentages display `n/a` when the denominator is zero.

Statement probes run **before** eligible statements, including branches, jumps, and operations that may throw. Covered means execution reached the statement; it does not imply that the statement completed successfully. Incoming branches pass through the destination's probe, and exception handlers keep their required caught-exception identity first. Missing logs can still leave executed statements marked as not observed.

APKs instrumented with older versions used after-statement probes. Reinstrument the original APK and collect fresh logs to benefit from the improved coverage. Existing logs remain readable, with their original measurement limitations.

### Interpret the timeline and JSON

With `-pam`, each distinct probe contributes to the timeline in the minute of its first matching log entry. The timeline accumulates newly observed probes, rather than counting repeated executions. Matching observations without usable timestamps still contribute to aggregate totals but cannot appear on the timeline.

AndroSpecter 2.0.0 includes the callback signature in component messages, for example `ACTIVITY=com.example.MainActivity|void onResume()`. AndroLog counts each component class once, regardless of how many callbacks were observed, and continues to accept older class-only component messages. After upgrading the analysis dependencies, reinstrument the original APK and collect fresh logs: statement identities can change with the Soot version.

JSON reports contain `coverageSummary`; `-pam` also adds `perMinuteSummaries`. When call tracing is requested or call records are available, `callGraph` contains the recorded nodes, edges, callsite metadata, statistics, and manifest-derived `launcherActivities`.

## Dynamic call graph

Enable **`-mc` when instrumenting**, install that APK, and collect a new log. Coverage-only logs cannot provide dynamic dispatch targets. Keep `-mc` enabled when generating the report.

### Navigate the graph

The graph opens on the **launcher activity's class graph**, identified from the APK manifest's `MAIN` and `LAUNCHER` intent filter, or `LEANBACK_LAUNCHER` for TV. Activity aliases resolve to their target classes. If several launcher classes have recorded calls, the first in alphabetical order opens. Launcher classes appear first in the sidebar.

- **Select a class** to see its recorded methods and their direct incoming and outgoing relationships. Callers and callees from other classes appear as endpoints; unrelated relationships are excluded.
- **Click a node** to highlight its incoming and outgoing edges and fade the others. All nodes, edges, positions, zoom, and pan remain in place.
- **Reset graph** clears the highlighting; **Fit** fits the current class graph to the canvas.
- **Select an edge** to inspect its callsites. **Open code** jumps to a method's listing when its code is available.

Search covers all recorded classes and methods. If launcher metadata or matching calls are unavailable, the report asks you to select a class; it never falls back to showing the entire application graph by default.

### Understand the edges

| Edge | Meaning |
| --- | --- |
| Solid: confirmed runtime target | A matching instrumented method entry established the actual target. A call declared as `Base.work()` may resolve to `Child.work()`, or to different overrides across runs. |
| Dashed: declared target | The callsite was reached, but no target entry was confirmed. The edge retains the bytecode target. |

Runtime matching uses the same thread, exact method subsignature, receiver identity for instance calls, and direct caller stack. It supports nested calls, recursion, and exceptional returns. It does not pair a call with the next log line.

Constructors, dynamic invokes, and calls into uninstrumented code remain declared-only. A declared edge can also represent a call that failed before entering its target, such as a null receiver. Reflection, framework callbacks, asynchronous work, and uninstrumented intermediaries are not inferred as direct edges. Legacy `CALL=` records can be displayed as declared-only edges but cannot recover actual dispatch targets.

Each callsite declaration and callsite/actual-target pair is logged once per process. **Counts are distinct records, not invocation frequencies.** The graph describes observations in the supplied logs; it is not a complete static call graph.

Call tracing captures stack traces and adds runtime overhead. Limit scope with `-pkg` or `-n` where appropriate. Complete `MC2` records avoid pairing unrelated log lines, and duplicate records are ignored when logs are merged. Missing logs can still leave relationships absent or unconfirmed. `-mc` can be combined with statement coverage: callsites are captured before coverage instrumentation to preserve the original statement identities.

## Command-line reference

```bash
java -jar target/androlog-0.1-jar-with-dependencies.jar --help
```

`-a` and `-p` are required except when displaying help. Without `-pa`, `-pam`, or `--capture`, AndroLog instruments the APK and exits.

### Inputs and outputs

| Option | Description |
| --- | --- |
| `-a`, `--apk <file>` | Input APK. Use the original APK when reporting. |
| `-p`, `--platforms <path>` | Android SDK platforms directory or Android platform file (`android.jar`). |
| `-o`, `--output <directory>` | Destination for the instrumented APK, or parent directory for a fresh capture session. Defaults to a temporary directory. |
| `-l`, `--log-identifier <tag>` | Logcat tag. Defaults to `ANDROLOG`. |
| `-pa`, `--parse <file>` | Parse a runtime log and report aggregate coverage. |
| `-pam`, `--parse-per-minute <file>` | Parse a runtime log and include cumulative coverage by minute. |
| `-html`, `--html <file>` | Write a standalone HTML report. Requires `-pa`, `-pam`, or `--capture`. |
| `-j`, `--json <file>` | Write a JSON report. Requires `-pa`, `-pam`, or `--capture`. |

### Capture

| Option | Description |
| --- | --- |
| `-capture`, `--capture` | Instrument, install, launch, capture logs, and generate HTML/JSON reports. Select at least one probe option. |
| `-device`, `--device <serial>` | Device or emulator to use. Required when multiple devices are online. Capture only. |
| `-adb`, `--adb <path>` | ADB executable. Discovered from the SDK or `PATH` by default. Capture only. |
| `-duration`, `--duration <seconds>` | Positive capture duration after launch. Otherwise, press Enter to finish. Capture only. |

Capture log tags may contain letters, digits, underscores, dots, and hyphens, and must start with a letter, digit, or underscore.

### Probes

| Option | Measures |
| --- | --- |
| `-c`, `--classes` | Classes. |
| `-m`, `--methods` | Methods. |
| `-s`, `--statements` | Eligible Jimple statements. |
| `-cp`, `--components` | Activities, services, broadcast receivers, and content providers. |
| `-mc`, `--method-calls` | Executed callsites and confirmed runtime targets. |

Select the same probes for instrumentation and reporting. `-html` and `--capture` require at least one probe option; `-mc` alone supports a graph-only report.

### Scope and diagnostics

| Option | Description |
| --- | --- |
| `-pkg`, `--package <prefix>` | Select the exact package and its subpackages; a trailing dot is normalized. |
| `-n`, `--non-libraries` | Exclude known libraries. Bundled libraries are included by default. |
| `-t`, `--threads <count>` | Positive Soot thread count. Defaults to Soot's configuration. |
| `-v`, `--verbose` | Include exception details when a run fails. |
| `-h`, `--help` | Show usage and examples. |

The CLI prints timed stages and generated file paths. Errors go to standard error. Exit codes are `0` for success, `1` for processing failures, and `2` for invalid arguments.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| `Signing tool not configured` or tool not executable | Check both paths in your external `config.properties` and use the classpath helper. The files must exist and be executable. |
| Installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app and instrumented APK have different signing certificates. Use a clean test device or emulator installation. |
| Coverage is unexpectedly empty or incomplete | Check the original APK version, log tag, probe flags, and scope. Capture before starting a fresh app process and exercise the relevant code. |
| No dynamic call graph is available | Instrument with `-mc`, install that APK, and capture a new log. Adding `-mc` only when parsing cannot create missing runtime observations. |
| The graph asks you to choose a starting class | The launcher activity has no matching recorded calls, the app has no launcher declaration, or an older report lacks manifest metadata. Select a class or regenerate the report from the APK. |
| No timeline entries appear | Use `-pam` with timestamped output such as `adb logcat -v threadtime`. Untimestamped observations count only toward aggregate totals. |
| Capture cannot select a device | Check `adb devices`, authorize the connection, and use `--device <serial>` if several devices are online. |
| Capture reports that standard input closed | Use `--duration <seconds>` in non-interactive runs. The raw log is retained in the session directory. |
| Logcat stops or the device disconnects | Reconnect the device and start a new capture. The partial log remains available for manual reporting. |

For other failures, repeat the command with `-v` to include exception details.

## Development and contributions

AndroLog uses [AndroSpecter](https://github.com/JordanSamhi/AndroSpecter) for instrumentation support and Soot for bytecode analysis. Maven manages the Java build and dependencies.

Run the Java tests:

```bash
mvn test
```

Run the report's JavaScript regression tests with Node.js:

```bash
node --test src/test/js/report-links.test.js src/test/js/report-graph.test.js
```

Issues and pull requests are welcome. For a bug report, include the command, Java and Android SDK versions, the relevant error output, and a minimal reproducible example when possible. Use the [issue tracker](https://github.com/JordanSamhi/AndroLog/issues).

## License and contact

AndroLog is licensed under the **GNU Lesser General Public License v2.1**. See [LICENSE](LICENSE).

For questions, contact [Jordan Samhi](mailto:j.samhi@me.com).
