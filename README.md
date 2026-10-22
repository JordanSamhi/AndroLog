<p align="center">
<img width="640" alt="AndroLog — Android execution analysis" src="data/androlog_logo_v2.png">
</p> 

# AndroLog

Welcome to **AndroLog**, your simple solution to insert probes into Android apps with the goal to compute code coverage at runtime.

**AndroLog** offers, so far, several levels of granularity:
- Classes
- Methods
- Statements
- Activities
- Services
- Broadcast Receivers
- Content Providers

Do not hesitate to contribute or open issues may you need additional levels of granularity.

## :rocket: Getting started

### :arrow_down: Downloading the tool

<pre>
git clone https://github.com/JordanSamhi/AndroLog.git
</pre>

### :wrench: Installing the tool

➡️ For APK instrumentation, configure your local `zipalignPath` and `apksignerPath` in `src/main/resources/config.properties` before compiling. Create this file if it does not exist; these paths depend on your Android SDK installation. Parsing logs and generating reports do not invoke the signing tools.

<pre>
cd AndroLog
mvn clean install
</pre>

### :computer: Using the tool

<pre>
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar <i>options</i>
</pre>

Options:

* ```-a``` : The path to the APK to process.
* ```-p``` : The path to the Android platforms folder.
* ```-l``` : The log identifier to use.
* ```-o``` :  The output where to write the instrumented APK.
* ```-pa``` : Parsing runtime output logs.
* ```-pam``` : Parsing runtime output logs per minute.
* ```-j``` : The output where to write the parsed logs in JSON format.
* ```-html```, ```--html <output-file>``` : Write a standalone HTML coverage report when parsing with `-pa` or `-pam`.
* ```-c``` : Logging classes.
* ```-m``` : Logging methods.
* ```-s``` : Logging statements.
* ```-cp``` : Logging Android components (Activity, Service, BroadcastReceiver, ContentProvider).
* ```-mc``` : Record executed callsites and confirm their actual runtime targets, including virtual and interface dispatch.
* ```-n``` : If set, this flag tells AndroLog to not consider libraries in the process.
* ```-pkg``` : Sets the package name of classes to be exclusively instrumented.
* ```-t``` : The number of threads used by Soot.
* ```-h```, ```--help``` : Show usage and examples.
* ```-v```, ```--verbose``` : Include exception details when a run fails.

Each run shows its mode, inputs, scope, and selected probes, followed by timed processing stages. Successful runs end with the generated file paths and total elapsed time. Coverage reports print an aligned summary table even when writing JSON or HTML; `-pam` also prints cumulative observations by minute. Categories with no probes display `n/a` in the terminal.

Errors go to standard error and return a nonzero exit status: `2` for invalid arguments and `1` for processing failures. Use `--verbose` for a stack trace when diagnosing a failure. Redirected output remains plain text.

### :information_source: Examples

#### Instrumenting

<pre>
  java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar -p ./Androidplatforms/ -l MY_SUPER_LOG -o ./output/ -a my_app.apk -c -m -cp
</pre>

#### Computing Code Coverage

<pre>
  java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar -p ./Androidplatforms/ -l MY_SUPER_LOG -a my_app.apk -c -m -cp -pa logs
</pre>

#### Generating an HTML report

The HTML report combines coverage totals with an expandable package tree and colored Jimple statements. Select a class to open one continuous code listing, with inline method declarations and a single scrollbar for the whole class. It is a single file that opens offline in a browser. Jimple is Soot's intermediate representation of the APK bytecode. Line numbers refer to this reconstructed listing, not the original Java or Kotlin source; statement tooltips identify their position within the Jimple method. Filtering covered or uncovered statements preserves the listing's line numbers.

Click a reference to a class, field, method, or constructor present in the report to navigate to its class or method. References outside the report remain plain text. Following a reference switches to the **All code** filter so the destination is visible; browser Back and Forward return to previous locations.

Click a coverage statistic to explore its entities. **Activities**, **Services**, **Broadcast receivers**, and **Content providers** filter the package tree to the corresponding components, including those not observed during testing. **Methods** offers a method selector for the selected class, and **Statements** shows classes with measured statements. **Classes** returns to class browsing; **Show all classes** also clears the search. Search works within the active category.

Run the following commands from the repository directory. First, instrument the APK with statement and method probes:

```shell
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Androidplatforms/ -a my_app.apk -o ./output/ \
  -l MY_SUPER_LOG -s -m
```

Install `output/my_app.apk`, exercise the application, and save its logcat output. For example, start this command before testing and stop it with Ctrl+C afterward:

```shell
adb logcat -v threadtime MY_SUPER_LOG:D '*:S' > logs.txt
```

Generate the report using the **original APK**, the same log identifier, and the same probe and scope options:

```shell
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Androidplatforms/ -a my_app.apk -l MY_SUPER_LOG \
  -s -m -pa logs.txt -html ./reports/coverage.html
```

Open `reports/coverage.html` in a browser. Parent output directories are created automatically. Add `-j ./reports/coverage.json` to also write the JSON report. The terminal coverage summary is printed with or without file outputs.

Replace `-pa logs.txt` with `-pam logs.txt` to include a cumulative timeline of newly observed probes per minute. Use timestamped logcat output for this view. Tagged messages without timestamps still contribute to aggregate coverage, but these observations cannot be placed on the timeline. Each probe is counted once, in the minute of its first observed log entry.

The report distinguishes four statement states:

| State | Meaning |
| :---- | :------ |
| Covered | The matching statement probe was observed in the supplied logs. |
| Not observed | The statement has a probe in the baseline, but its log was not observed. |
| Excluded | The statement is an identity, return, or monitor statement, which statement instrumentation skips. |
| Not measured | Statement probes were not selected, or this statement has no matching probe in the baseline. |

Use `-s` during both instrumentation and reporting to obtain statement coloring, and `-m` during both stages for method coverage. Other categories remain available through `-c` and `-cp`. Keep the original APK version, log identifier, and scope (`-n` and `-pkg`, if used) consistent between both stages; changing them can prevent observations from matching the report baseline.

Statement probes are inserted **after** statements. A statement that throws or transfers control before its probe runs can therefore appear as not observed. Missing logs can have the same effect, so this state does not establish that a statement was never executed. `-mc` can be combined with `-s`: callsites are captured before coverage instrumentation, and call hooks are added afterward to preserve the original statement identities.

`-html` requires either `-pa` or `-pam`; it does not instrument an APK or collect runtime logs by itself.

#### Recording and exploring the dynamic call graph

Add `-mc` when instrumenting, then collect a **new** log from that APK. Existing coverage-only logs do not contain dispatch information.

```shell
java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Androidplatforms/ -a my_app.apk -o ./output/ \
  -l MY_SUPER_LOG -s -m -mc

# Install output/my_app.apk, start capture, then exercise the application.
adb logcat -v threadtime MY_SUPER_LOG:D '*:S' > logs.txt

java -jar target/androlog-0.1-jar-with-dependencies.jar \
  -p ./Androidplatforms/ -a my_app.apk -l MY_SUPER_LOG \
  -s -m -mc -pa logs.txt -html ./reports/coverage.html -j ./reports/coverage.json
```

The **Dynamic call graph** tab starts with the launcher activity's class graph, identified from the APK manifest's `MAIN` and `LAUNCHER` intent filter (or `LEANBACK_LAUNCHER` on TV). Activity aliases resolve to their target classes. If multiple launcher classes have recorded calls, the first in alphabetical order opens; launcher classes appear first in the sidebar. If launcher metadata or matching calls are unavailable, select a class explicitly; the report never opens the whole application graph by default.

Each class graph contains its recorded methods and their direct incoming and outgoing calls, with callers and callees from other classes as endpoints. Clicking a node highlights its incoming and outgoing edges and fades the other edges. Every node and edge stays in place, and the zoom and pan position are preserved. **Reset graph** clears the highlighting. Selecting another class switches the graph to that class. Search classes or methods, zoom or pan, and inspect individual callsites. **Open code** jumps to the exact method in the continuous class listing. `-mc` also works without coverage probes for a graph-only report. JSON exports include the full recorded `callGraph` nodes, edges, callsites, statistics, and resolved `launcherActivities`.

- **Confirmed edges** identify the method whose entry was observed. For example, a call declared as `Base.work()` can resolve to `Child.work()` or several overrides across executions.
- **Declared edges** retain the bytecode target for executed callsites with no confirmed entry. Constructors, dynamic invokes, and calls into uninstrumented code remain in this category. A declared edge can also represent a call that threw before entering its target, such as a null receiver.

The runtime matches calls and entries within the same thread, using the exact method subsignature, receiver identity for instance calls, and the direct caller stack. It handles nested calls, recursion, and exceptional returns; it does not assume that the next log line is the target. Reflection, framework callbacks, asynchronous work, and uninstrumented intermediaries are not inferred as direct edges. Stack matching deliberately leaves a target unconfirmed when it cannot establish a direct call.

Each callsite declaration and each callsite/actual-target pair is logged once per process. Counts indicate **distinct records, not invocation frequencies**. The graph only represents observations in the supplied logs; it is not a complete static call graph. Legacy `CALL=` logs can be displayed as declared-only relationships, without recovering their dispatch targets. Use the original APK and identical scope options when generating a report.

Call tracing collects stack traces and adds runtime overhead. Use `-pkg` or `-n` to limit the scope when appropriate. Complete, independently parseable `MC2` records prevent lost or interleaved log lines from being paired into spurious edges. Duplicate records are ignored when logs are merged; missing records can still leave relationships absent or unconfirmed.

## :hammer: Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## :page_with_curl: License

This project is licensed under the GNU LESSER GENERAL PUBLIC LICENSE 2.1 - see the [LICENSE](LICENSE) file for details

## :email: Contact

For any question regarding this study, please contact us at:
[Jordan Samhi](mailto:j.samhi@me.com)
