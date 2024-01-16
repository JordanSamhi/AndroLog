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
        System.out.println(String.format("%s v%s started on %s\n", Constants.TOOL_NAME, Constants.VERSION, new Date()));

        CommandLineOptions options = CommandLineOptions.v();
        options.setAppName("AndroLog");
        options.addOption(new CommandLineOption("platforms", "p", "Platform file", true, true));
        options.addOption(new CommandLineOption("parse", "pa", "Parse log file", true, false));
        options.addOption(new CommandLineOption("output", "o", "Instrumented APK output", true, false));
        options.addOption(new CommandLineOption("apk", "a", "Apk file", true, true));
        options.addOption(new CommandLineOption("log-identifier", "l", "Log identifier", true, false));
        options.addOption(new CommandLineOption("classes", "c", "Log classes", false, false));
        options.addOption(new CommandLineOption("methods", "m", "Log methods", false, false));
        options.addOption(new CommandLineOption("statements", "s", "Log statements", false, false));
        options.addOption(new CommandLineOption("components", "cp", "Log Android components", false, false));
        options.parseArgs(args);

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());

        Writer.v().pinfo("Setting up environment...");
        SootUtils su = new SootUtils();
        su.setupSootWithOutput(CommandLineOptions.v().getOptionValue("platforms"), CommandLineOptions.v().getOptionValue("apk"), outputApk, true);
        Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
        Writer.v().psuccess("Done.");

        Path path = Paths.get(CommandLineOptions.v().getOptionValue("apk"));
        String fileName = path.getFileName().toString();

        if (CommandLineOptions.v().hasOption("pa")) {
            Writer.v().pinfo("Generating Code Coverage Report...");
            String logFilePath = CommandLineOptions.v().getOptionValue("parse");
            LogParser lp = new LogParser(logIdentifier);
            lp.parseLogs(logFilePath);

            SummaryBuilder summaryBuilder = SummaryBuilder.v();
            summaryBuilder.setSootUtils(su);
            summaryBuilder.build();

            SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

            SummaryStatistics stats = new SummaryStatistics();
            stats.compareSummaries(summaryBuilder, summaryLogBuilder);
        } else {
            Writer.v().pinfo("Instrumentation in progress...");
            if (CommandLineOptions.v().hasOption("s")) {
                Logger.v().logAllStatements(logIdentifier);
            }
            if (CommandLineOptions.v().hasOption("m")) {
                Logger.v().logAllMethods(logIdentifier);
            }
            if (CommandLineOptions.v().hasOption("c")) {
                Logger.v().logAllClasses(logIdentifier);
            }
            if (CommandLineOptions.v().hasOption("cp")) {
                Logger.v().logActivities(logIdentifier);
                Logger.v().logContentProviders(logIdentifier);
                Logger.v().logServices(logIdentifier);
                Logger.v().logBroadcastReceivers(logIdentifier);
            }
            Logger.v().instrument();
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
}