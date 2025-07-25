package com.jordansamhi.androlog;

import com.jordansamhi.androlog.utils.Constants;
import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOption;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.instrumentation.Logger;
import com.jordansamhi.androspecter.printers.Writer;
import soot.options.Options;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        System.out.printf("%s v%s started on %s\n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());

        CommandLineOptions options = CommandLineOptions.v();
        options.setAppName("AndroLog");
        options.addOption(new CommandLineOption("platforms", "p", "Platform file", true, true));
        options.addOption(new CommandLineOption("parse", "pa", "Parse log file", true, false));
        options.addOption(new CommandLineOption("parse-per-minute", "pam", "Parse log file per-minute", true, false));
        options.addOption(new CommandLineOption("output", "o", "Instrumented APK output", true, false));
        options.addOption(new CommandLineOption("json", "j", "Parsed logs JSON output", true, false));
        options.addOption(new CommandLineOption("apk", "a", "Apk file", true, true));
        options.addOption(new CommandLineOption("log-identifier", "l", "Log identifier", true, false));
        options.addOption(new CommandLineOption("classes", "c", "Log classes", false, false));
        options.addOption(new CommandLineOption("methods", "m", "Log methods", false, false));
        options.addOption(new CommandLineOption("statements", "s", "Log statements", false, false));
        options.addOption(new CommandLineOption("components", "cp", "Log Android components", false, false));
        options.addOption(new CommandLineOption("non-libraries", "n", "Whether to include libraries (by default: include libraries)", false, false));
        options.addOption(new CommandLineOption("package", "pkg", "Package name that will exclusively be instrumented", true, false));
        options.addOption(new CommandLineOption("method-calls", "mc", "Log method calls (e.g., a()-->b())", false, false));
        options.addOption(new CommandLineOption("threads", "t", "Number of threads to use in Soot", true, false));
        options.parseArgs(args);

        boolean includeLibraries = !CommandLineOptions.v().hasOption("n");

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());
        String outputJson = Optional.ofNullable(options.getOptionValue("json")).orElse(TmpFolder.v().get());

        Writer.v().pinfo("Setting up environment...");
        SootUtils su = new SootUtils();
        su.setupSootWithOutput(CommandLineOptions.v().getOptionValue("platforms"), CommandLineOptions.v().getOptionValue("apk"), outputApk, true);
        Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
        applyThreadOption();
        Writer.v().psuccess("Done.");

        Path path = Paths.get(CommandLineOptions.v().getOptionValue("apk"));
        String fileName = path.getFileName().toString();

        String packageName = null;
        if (CommandLineOptions.v().hasOption("pkg")) {
            packageName = CommandLineOptions.v().getOptionValue("package");
        }

        if (CommandLineOptions.v().hasOption("pa") || CommandLineOptions.v().hasOption("pam")) {
            Writer.v().pinfo("Generating Code Coverage Report...");
            String logFilePath = "";
            if (CommandLineOptions.v().hasOption("pa")) {
                logFilePath = CommandLineOptions.v().getOptionValue("parse");
            } else if (CommandLineOptions.v().hasOption("pam")) {
                logFilePath = CommandLineOptions.v().getOptionValue("parse-per-minute");
            }
            SummaryBuilder summaryBuilder = SummaryBuilder.v();
            summaryBuilder.setSootUtils(su);
            summaryBuilder.build(includeLibraries);

            LogParser lp = new LogParser(logIdentifier, summaryBuilder);
            lp.parseLogs(logFilePath);

            SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

            SummaryStatistics stats = new SummaryStatistics();
            if (CommandLineOptions.v().hasOption("j")) {
                if (CommandLineOptions.v().hasOption("pa")) {
                    stats.compareSummariesToJson(summaryBuilder, summaryLogBuilder, outputJson);
                } else if (CommandLineOptions.v().hasOption("pam")) {
                    stats.compareSummariesPerMinuteToJson(summaryBuilder, summaryLogBuilder, outputJson);
                }
                Writer.v().psuccess("Done.");

                Writer.v().pinfo("The parsed logs are now available in " + outputJson);
            } else {
                if (CommandLineOptions.v().hasOption("pa")) {
                    stats.compareSummaries(summaryBuilder, summaryLogBuilder);
                } else if (CommandLineOptions.v().hasOption("pam")) {
                    stats.compareSummariesPerMinute(summaryBuilder, summaryLogBuilder);
                }
                Writer.v().psuccess("Done.");
            }
        } else {
            Writer.v().pinfo("Instrumentation in progress...");
            Logger.v().setTargetPackage(packageName);
            if (CommandLineOptions.v().hasOption("mc")) {
                Logger.v().logAllMethodCalls(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("s")) {
                Logger.v().logAllStatements(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("m")) {
                Logger.v().logAllMethods(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("c")) {
                Logger.v().logAllClasses(logIdentifier, includeLibraries);
            }
            if (CommandLineOptions.v().hasOption("cp")) {
                Logger.v().logActivities(logIdentifier, includeLibraries);
                Logger.v().logContentProviders(logIdentifier, includeLibraries);
                Logger.v().logServices(logIdentifier, includeLibraries);
                Logger.v().logBroadcastReceivers(logIdentifier, includeLibraries);
            }
            Logger.v().instrument();
            System.out.printf("%s v%s finished Instrumentation at %s\n%n", Constants.TOOL_NAME, Constants.VERSION, new Date());
            Writer.v().psuccess("Done.");
            Writer.v().pinfo("Exporting new apk...");
            Logger.v().exportNewApk(outputApk);
            Writer.v().psuccess(String.format("Apk written in: %s", outputApk));

            Writer.v().pinfo("Signing and aligning APK...");
            ApkPreparator ap = new ApkPreparator(String.format("%s/%s", outputApk, fileName));
            ap.prepareApk();
            Writer.v().psuccess("Done.");

            Writer.v().pinfo("The apk is now instrumented, install it and execute it to generate logs.");
        }
    }

    /**
     * Configures the number of threads used by the Soot framework based on the
     * command-line option provided by the user.
     * <p>
     * If the "--threads" (or "-t") option is specified and contains a valid positive
     * integer, this value is passed to {@code Options.v().set_num_threads()}.
     * Otherwise, Soot's default thread configuration is used.
     * <p>
     * Logs appropriate messages for valid, missing, or invalid input.
     * Exits the program if the input value is non-numeric or non-positive.
     */
    private static void applyThreadOption() {
        if (CommandLineOptions.v().hasOption("threads")) {
            try {
                int numThreads = Integer.parseInt(CommandLineOptions.v().getOptionValue("threads"));
                if (numThreads > 0) {
                    Options.v().set_num_threads(numThreads);
                    Writer.v().pinfo(String.format("Using %d threads for Soot processing", numThreads));
                } else {
                    Writer.v().perror("Invalid number of threads. Must be a positive integer.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                Writer.v().perror("Invalid format for thread count. Please provide a numeric value.");
                System.exit(1);
            }
        } else {
            Writer.v().pinfo("No thread count specified. Using Soot's default thread configuration.");
        }
    }
}
