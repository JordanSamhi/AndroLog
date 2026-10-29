package com.jordansamhi.androlog;

import com.jordansamhi.androlog.utils.Constants;
import com.jordansamhi.androlog.utils.PackageScope;
import com.jordansamhi.androlog.instrumentation.MethodCallInstrumenter;
import com.jordansamhi.androlog.instrumentation.CustomLogInstrumenter;
import com.jordansamhi.androlog.instrumentation.StatementInstrumenter;
import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOption;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.instrumentation.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import soot.options.Options;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<CommandLineOption> definitions = optionDefinitions();
        org.apache.commons.cli.Options cli = cliOptions(definitions);
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                printHelp(cli, out);
                return 0;
            }
        }
        if (args.length == 0) {
            printHelp(cli, out);
            err.println("Error: provide --apk and --platforms, or use --help for examples.");
            return 2;
        }
        CliOutput console = new CliOutput(out, err);
        boolean verbose = false;
        try {
            CommandLine command = new DefaultParser().parse(cli, args);
            verbose = command.hasOption("v");
            if (!command.getArgList().isEmpty()) {
                throw new UsageException("Unexpected argument: " + command.getArgList().get(0));
            }
            validate(command);
            if (command.hasOption("capture")) {
                new CaptureWorkflow(command, System.in, out, err, console).run();
                return 0;
            }
            // AndroSpecter's transforms use both cmdLine and the values stored in its definitions.
            // Seed them only after validating arguments, so its legacy System.exit error path is unused.
            CommandLineOptions options = CommandLineOptions.v();
            options.setAppName(Constants.TOOL_NAME);
            for (CommandLineOption definition : definitions) options.addOption(definition);
            options.parseArgs(args);

            boolean inspecting = command.hasOption("inspect-code");
            boolean parsing = command.hasOption("pa") || command.hasOption("pam");
            boolean perMinute = command.hasOption("pam");
            boolean includeLibraries = !command.hasOption("n");
            String logIdentifier = command.getOptionValue("l", "ANDROLOG");
            String packageName = PackageScope.normalize(command.getOptionValue("pkg"));
            Path apk = absolute(command.getOptionValue("a"));
            Path outputDirectory = absolute(command.getOptionValue("o", TmpFolder.v().get()));
            Path exportedApk = outputDirectory.resolve(apk.getFileName());
            String logFile = parsing ? command.getOptionValue(perMinute ? "pam" : "pa") : null;
            if (inspecting) {
                Path catalogFile = absolute(command.getOptionValue("inspect-code"));
                String apkHash = CustomLogPlan.sha256(apk);
                console.begin("Browse APK code", 2);
                console.context("APK", apk.toString());
                console.stage("Load APK", () -> {
                    ApkAnalysisSetup.initialize(command.getOptionValue("p"), apk, outputDirectory);
                    Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
                    if (command.hasOption("t")) Options.v().set_num_threads(Integer.parseInt(command.getOptionValue("t")));
                });
                console.stage("Read original statements", () -> {
                    CodeCatalog catalog = CodeCatalog.inspect(apkHash, includeLibraries, packageName);
                    if (!apkHash.equals(CustomLogPlan.sha256(apk))) throw new IOException("The APK changed while its code was being read. Browse it again.");
                    catalog.write(catalogFile);
                });
                console.output("Code catalog", catalogFile); console.complete();
                return 0;
            }
            CustomLogPlan customPlan = command.hasOption("custom-logs")
                    ? CustomLogPlan.read(absolute(command.getOptionValue("custom-logs"))) : null;
            if (customPlan != null) customPlan.verifyApk(apk);
            int stages = parsing ? 3 + (command.hasOption("html") ? 1 : 0) + (command.hasOption("j") ? 1 : 0) : 4;
            console.begin(parsing ? (perMinute ? "Coverage report with timeline" : "Coverage report") : "Instrument APK", stages);
            console.context("APK", apk.toString());
            console.context("Scope", (packageName == null ? "all APK packages" : "package prefix " + packageName)
                    + (includeLibraries ? "; bundled libraries included" : "; known libraries excluded"));
            console.context("Probes", selectedProbes(command));
            console.context("Log tag", logIdentifier);
            if (command.hasOption("t")) console.context("Threads", command.getOptionValue("t"));
            console.context(parsing ? "Runtime log" : "Output APK", parsing ? absolute(logFile).toString() : exportedApk.toString());

            // Detect missing local signing tools before the expensive Soot analysis.
            ApkPreparator preparator = parsing ? null : new ApkPreparator(exportedApk.toString());
            SootUtils su = new SootUtils();
            console.stage("Load APK", () -> {
                ApkAnalysisSetup.initialize(command.getOptionValue("p"), apk, outputDirectory);
                Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
                if (command.hasOption("t")) Options.v().set_num_threads(Integer.parseInt(command.getOptionValue("t")));
            });

            if (parsing) {
                SummaryBuilder baseline = SummaryBuilder.v();
                baseline.setSootUtils(su);
                baseline.setTargetPackage(packageName);
                baseline.setCollectCode(command.hasOption("html"));
                baseline.setCollectCallGraph(command.hasOption("mc"));
                console.stage("Analyze coverage baseline", () -> {
                    if (command.hasOption("html") || command.hasOption("mc")) {
                        baseline.setLauncherActivities(LauncherActivities.read(apk));
                    }
                    baseline.build(includeLibraries);
                });
                console.stage("Read runtime observations", () -> new LogParser(logIdentifier, baseline).parseLogs(logFile));
                SummaryLogBuilder observations = SummaryLogBuilder.v();
                SummaryStatistics stats = new SummaryStatistics();
                if (perMinute) stats.compareSummariesPerMinute(baseline, observations);
                else stats.compareSummaries(baseline, observations);
                if (command.hasOption("html")) {
                    Path html = absolute(command.getOptionValue("html"));
                    console.stage("Write HTML report", () -> new HtmlReportWriter().write(baseline, observations,
                            apk.getFileName().toString(), logIdentifier, html.toString(), perMinute));
                    console.output("HTML", html);
                }
                if (command.hasOption("j")) {
                    Path json = absolute(command.getOptionValue("j"));
                    console.stage("Write JSON report", () -> {
                        if (perMinute) stats.compareSummariesPerMinuteToJson(baseline, observations, json.toString());
                        else stats.compareSummariesToJson(baseline, observations, json.toString());
                    });
                    console.output("JSON", json);
                }
            } else {
                MethodCallInstrumenter calls = command.hasOption("mc")
                        ? new MethodCallInstrumenter(logIdentifier, includeLibraries, packageName) : null;
                CustomLogInstrumenter custom = customPlan == null ? null : new CustomLogInstrumenter(logIdentifier, customPlan);
                console.stage("Insert probes", () -> {
                    if (custom != null) custom.registerCapture();
                    if (calls != null) calls.registerCapture();
                    Logger.v().setTargetPackage(packageName);
                    if (command.hasOption("s")) new StatementInstrumenter(logIdentifier, includeLibraries, packageName).register();
                    if (command.hasOption("m")) Logger.v().logAllMethods(logIdentifier, includeLibraries);
                    if (command.hasOption("c")) Logger.v().logAllClasses(logIdentifier, includeLibraries);
                    if (command.hasOption("cp")) {
                        Logger.v().logActivities(logIdentifier, includeLibraries);
                        Logger.v().logContentProviders(logIdentifier, includeLibraries);
                        Logger.v().logServices(logIdentifier, includeLibraries);
                        Logger.v().logBroadcastReceivers(logIdentifier, includeLibraries);
                    }
                    if (calls != null) calls.registerInstrumentation();
                    if (custom != null) custom.registerInstrumentation();
                    Logger.v().instrument();
                    if (custom != null) { custom.verifyComplete(); customPlan.verifyApk(apk); }
                    if (calls != null) calls.includeRuntimeClasses();
                });
                console.stage("Export instrumented APK", () -> Logger.v().exportNewApk(outputDirectory.toString()));
                console.stage("Align and sign APK", preparator::prepareApk);
                console.output("APK", exportedApk);
            }
            console.complete();
            if (!parsing) out.println("Install and exercise the APK, then collect logcat output for a coverage report.");
            return 0;
        } catch (ParseException | UsageException error) {
            err.println("Error: " + error.getMessage());
            err.println("Use --help for usage and examples.");
            return 2;
        } catch (Exception | LinkageError error) {
            console.failure(error, verbose);
            return 1;
        }
    }

    private static void validate(CommandLine command) throws IOException {
        boolean parsing = command.hasOption("pa") || command.hasOption("pam");
        boolean capture = command.hasOption("capture");
        boolean inspecting = command.hasOption("inspect-code");
        if (inspecting && (parsing || capture || command.hasOption("custom-logs") || command.hasOption("html") || command.hasOption("j"))) {
            throw new UsageException("--inspect-code cannot be combined with instrumentation, capture or reporting options.");
        }
        if (inspecting) for (String probe : Arrays.asList("c", "m", "s", "cp", "mc")) {
            if (command.hasOption(probe)) throw new UsageException("--inspect-code reads code without adding probes; omit -" + probe + ".");
        }
        if (command.hasOption("custom-logs") && (parsing || capture)) {
            throw new UsageException("--custom-logs is for instrumentation only; it cannot be combined with parsing or --capture.");
        }
        if (command.hasOption("custom-logs") && !command.getOptionValue("l", "ANDROLOG").matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new UsageException("Custom log tags must contain only letters, digits, underscores, dots or hyphens.");
        }
        if (capture && parsing) throw new UsageException("--capture cannot be combined with --parse or --parse-per-minute.");
        for (String flag : Arrays.asList("device", "adb", "duration")) {
            if (command.hasOption(flag) && !capture) throw new UsageException("--" + flag + " requires --capture.");
        }
        if (command.hasOption("duration")) {
            try {
                if (Integer.parseInt(command.getOptionValue("duration")) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new UsageException("--duration must be a positive number of seconds.");
            }
        }
        if (capture && !command.getOptionValue("l", "ANDROLOG").matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new UsageException("Capture log tags must contain only letters, digits, underscores, dots or hyphens.");
        }
        if (command.hasOption("pa") && command.hasOption("pam")) {
            throw new UsageException("Choose either --parse or --parse-per-minute, not both.");
        }
        if ((command.hasOption("html") || command.hasOption("j")) && !parsing && !capture) {
            throw new UsageException("--html and --json require --parse, --parse-per-minute or --capture.");
        }
        if (command.hasOption("custom-report-only") && (!parsing || !command.hasOption("html"))) throw new UsageException("--custom-report-only requires --parse and --html.");
        if ((command.hasOption("html") || capture) && !command.hasOption("custom-report-only") && !command.hasOption("c") && !command.hasOption("m")
                && !command.hasOption("s") && !command.hasOption("cp") && !command.hasOption("mc")) {
            throw new UsageException("Select probes with -c, -m, -s, -cp or -mc, matching instrumentation.");
        }
        if (command.hasOption("t")) {
            try {
                if (Integer.parseInt(command.getOptionValue("t")) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new UsageException("--threads must be a positive integer.");
            }
        }
        Path apk = readableFile(command.getOptionValue("a"), "APK");
        if (command.hasOption("o")) absolute(command.getOptionValue("o"));
        Path platforms = absolute(command.getOptionValue("p"));
        if (!Files.exists(platforms) || !Files.isReadable(platforms)) {
            throw new UsageException("Android platforms path does not exist or is not readable: " + platforms);
        }
        if (inspecting) {
            if (sameFile(absolute(command.getOptionValue("inspect-code")), apk)) throw new UsageException("Code catalog output must be separate from the APK.");
        } else if (parsing || capture) {
            Path log = parsing ? readableFile(command.getOptionValue(command.hasOption("pa") ? "pa" : "pam"), "Runtime log") : null;
            for (String flag : Arrays.asList("html", "j")) {
                if (command.hasOption(flag)) {
                    Path output = absolute(command.getOptionValue(flag));
                    if (sameFile(output, apk) || (log != null && sameFile(output, log))) {
                        throw new UsageException(("j".equals(flag) ? "JSON" : "HTML")
                                + " output must be separate from the APK and runtime log: " + output);
                    }
                }
            }
            if (command.hasOption("html") && command.hasOption("j")
                    && sameFile(absolute(command.getOptionValue("html")), absolute(command.getOptionValue("j")))) {
                throw new UsageException("HTML and JSON outputs must be separate files.");
            }
        } else {
            Path output = absolute(command.getOptionValue("o", TmpFolder.v().get())).resolve(apk.getFileName());
            if (command.hasOption("custom-logs")) {
                Path plan = readableFile(command.getOptionValue("custom-logs"), "Custom log plan");
                if (sameFile(output, plan)) throw new UsageException("The output APK must be separate from the custom log plan.");
            }
            if (sameFile(output, apk)) {
                throw new UsageException("The output APK would overwrite the input. Choose a separate directory with --output.");
            }
        }
    }

    private static Path readableFile(String value, String label) {
        Path path = absolute(value);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new UsageException(label + " is not a readable file: " + path);
        }
        return path;
    }

    private static Path absolute(String value) {
        if (value == null || value.trim().isEmpty()) throw new UsageException("File and directory paths must not be empty.");
        return Paths.get(value).toAbsolutePath().normalize();
    }

    static boolean sameFile(Path first, Path second) throws IOException {
        return resolvedOutputPath(first).equals(resolvedOutputPath(second))
                || (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second));
    }

    /** Resolve existing ancestors even when an output file or its final directories do not exist yet. */
    private static Path resolvedOutputPath(Path path) throws IOException {
        Path ancestor = path;
        List<Path> missing = new ArrayList<>();
        while (ancestor != null && !Files.exists(ancestor)) {
            missing.add(ancestor.getFileName());
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) return path;
        Path resolved = ancestor.toRealPath();
        for (int i = missing.size() - 1; i >= 0; i--) resolved = resolved.resolve(missing.get(i));
        return resolved;
    }

    private static String selectedProbes(CommandLine command) {
        List<String> names = new ArrayList<>();
        String[][] probes = {{"c", "classes"}, {"m", "methods"}, {"s", "statements"},
                {"cp", "Android components"}, {"mc", "method calls"}};
        for (String[] probe : probes) if (command.hasOption(probe[0])) names.add(probe[1]);
        if (command.hasOption("custom-logs")) names.add("custom log points");
        return names.isEmpty() ? "none selected" : String.join(", ", names);
    }

    private static List<CommandLineOption> optionDefinitions() {
        return Arrays.asList(
                new CommandLineOption("apk", "a", "Input APK (original APK when parsing logs)", true, true),
                new CommandLineOption("platforms", "p", "Android SDK platforms directory or platform file", true, true),
                new CommandLineOption("parse", "pa", "Read runtime log and report aggregate coverage", true, false),
                new CommandLineOption("parse-per-minute", "pam", "Read runtime log and include cumulative coverage per minute", true, false),
                new CommandLineOption("capture", "capture", "Instrument, install, capture execution and generate reports", false, false),
                new CommandLineOption("device", "device", "ADB device serial (capture only; required if multiple devices are online)", true, false),
                new CommandLineOption("adb", "adb", "ADB executable (capture only; default: discover from SDK or PATH)", true, false),
                new CommandLineOption("duration", "duration", "Capture for this many seconds instead of waiting for Enter", true, false),
                new CommandLineOption("output", "o", "APK output directory, or parent for a fresh capture session (default: temporary directory)", true, false),
                new CommandLineOption("json", "j", "Write coverage JSON (requires -pa, -pam or --capture)", true, false),
                new CommandLineOption("html", "html", "Write a standalone HTML report (requires -pa, -pam or --capture)", true, false),
                new CommandLineOption("log-identifier", "l", "Logcat tag (default: ANDROLOG)", true, false),
                new CommandLineOption("inspect-code", "inspect-code", "Write original Jimple statements to a JSON catalog without modifying the APK", true, false),
                new CommandLineOption("custom-report-only", "custom-report-only", "Allow a code report without automatic coverage probes", false, false),
                new CommandLineOption("custom-logs", "custom-logs", "Insert literal messages at points selected in a JSON plan (instrumentation only)", true, false),
                new CommandLineOption("classes", "c", "Instrument or measure classes", false, false),
                new CommandLineOption("methods", "m", "Instrument or measure methods", false, false),
                new CommandLineOption("statements", "s", "Instrument or measure statements", false, false),
                new CommandLineOption("components", "cp", "Instrument or measure Android components", false, false),
                new CommandLineOption("non-libraries", "n", "Exclude known libraries (default: include bundled libraries)", false, false),
                new CommandLineOption("package", "pkg", "Select this exact package and its subpackages", true, false),
                new CommandLineOption("method-calls", "mc", "Record a dynamic call graph with confirmed runtime dispatch targets", false, false),
                new CommandLineOption("threads", "t", "Positive Soot thread count (default: Soot configuration)", true, false),
                new CommandLineOption("verbose", "v", "Include stack traces when a run fails", false, false));
    }

    private static org.apache.commons.cli.Options cliOptions(List<CommandLineOption> definitions) {
        org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();
        for (CommandLineOption definition : definitions) {
            options.addOption(Option.builder(definition.getShortOpt()).longOpt(definition.getLongOpt())
                    .desc(definition.getDescription()).hasArg(definition.hasArg()).required(definition.isRequired())
                    .argName("value").build());
        }
        options.addOption("h", "help", false, "Show usage and examples");
        return options;
    }

    private static void printHelp(org.apache.commons.cli.Options options, PrintStream out) {
        out.println(Constants.TOOL_NAME + " " + Constants.VERSION + " | Android instrumentation and coverage");
        out.println();
        HelpFormatter formatter = new HelpFormatter();
        formatter.setOptionComparator(null);
        PrintWriter writer = new PrintWriter(out);
        formatter.printHelp(writer, 100, "java -jar androlog.jar -a <apk> -p <platforms> [options]",
                "Instrument, capture an Android test run, or parse logs. --apk and --platforms are required.",
                options, 2, 2, "\nExamples:\n"
                        + "  Instrument: -a app.apk -p /sdk/platforms -o ./instrumented -c -m -s -cp\n"
                        + "  Capture:    -a app.apk -p /sdk/platforms -o ./out -c -m -s -cp -mc --capture\n"
                        + "  Report:     -a app.apk -p /sdk/platforms -pa runtime.log -c -m -s -cp -html report.html\n"
                        + "  Timeline:   -a app.apk -p /sdk/platforms -pam runtime.log -m -j coverage.json\n\n"
                        + "Reports use the original APK and the same probe/scope options as instrumentation.\n"
                        + "For instrumentation, configure apksignerPath and zipalignPath in config.properties.", false);
        writer.flush();
    }

    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) { super(message); }
    }
}
