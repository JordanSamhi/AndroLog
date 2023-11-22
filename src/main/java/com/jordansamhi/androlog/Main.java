package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOption;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.instrumentation.Instrumenter;
import com.jordansamhi.androspecter.printers.Writer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        CommandLineOptions options = CommandLineOptions.v();
        options.setAppName("AndroLog");
        options.addOption(new CommandLineOption("platforms", "p", "Platform file", true, false));
        options.addOption(new CommandLineOption("parse", "pa", "Parse log file", true, false));
        options.addOption(new CommandLineOption("output", "o", "Instrumented APK output", true, false));
        options.addOption(new CommandLineOption("output-summary", "os", "APK summary output", true, false));
        options.addOption(new CommandLineOption("summary", "s", "Compute App Summary", false, false));
        options.addOption(new CommandLineOption("apk", "a", "Apk file", true, false));
        options.addOption(new CommandLineOption("log-identifier", "l", "Log identifier", true, false));
        options.parseArgs(args);

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());
        String outputSummaryPrefix = Optional.ofNullable(options.getOptionValue("output-summary")).orElse(TmpFolder.v().get());

        if (CommandLineOptions.v().hasOption("a") && CommandLineOptions.v().hasOption("p")) {
            Writer.v().pinfo("Setting up environment...");
            SootUtils su = new SootUtils();
            su.setupSootWithOutput(CommandLineOptions.v().getOptionValue("platforms"), CommandLineOptions.v().getOptionValue("apk"), outputApk, true);
            Writer.v().psuccess("Done.");

            Path path = Paths.get(CommandLineOptions.v().getOptionValue("apk"));
            String fileName = path.getFileName().toString();
            if (CommandLineOptions.v().hasOption("s")) {
                String outputSummaryFolder = String.format("%s/%s", outputSummaryPrefix, "androlog/");
                File folder = new File(outputSummaryFolder);

                if (!folder.exists()) {
                    boolean created = folder.mkdirs();
                    if (created) {
                        Writer.v().psuccess("Output summary folder was created successfully.");
                    } else {
                        Writer.v().perror("Unable to create output summary folder.");
                        Writer.v().pwarning("Quitting.");
                        System.exit(1);
                    }
                }
                String outputSummary = String.format("%s/%s/%s.summary", outputSummaryPrefix, "androlog/", fileName);
                Writer.v().pinfo("Building app summary");
                SummaryBuilder sb = SummaryBuilder.v();
                sb.setOutput(outputSummary);
                sb.setSootUtils(su);
                sb.build();
                sb.writeSummary();
                Writer.v().psuccess("Summary written");
            }

            Writer.v().pinfo("Instrumentation in progress...");
            Instrumenter.v().logAllStatements(logIdentifier);
            Instrumenter.v().logAllMethods(logIdentifier);
            Instrumenter.v().logAllClasses(logIdentifier);
            Instrumenter.v().logActivities(logIdentifier);
            Instrumenter.v().logContentProviders(logIdentifier);
            Instrumenter.v().logServices(logIdentifier);
            Instrumenter.v().logBroadcastReceivers(logIdentifier);
            Instrumenter.v().instrument();
            Writer.v().psuccess("Done.");
            Writer.v().pinfo("Exporting new apk...");
            Instrumenter.v().exportNewApk(outputApk);
            Writer.v().psuccess(String.format("Apk written in: %s", outputApk));

            Writer.v().pinfo("Signing and aligning APK...");
            ApkPreparator ap = new ApkPreparator(String.format("%s/%s", outputApk, fileName));
            ap.prepareApk();
            Writer.v().psuccess("Done.");

            Writer.v().pinfo("The apk is now instrumented, install it and execute it to generate logs.");
        }

        if (CommandLineOptions.v().hasOption("pa")) {
            String logFilePath = CommandLineOptions.v().getOptionValue("parse");
            LogParser lp = new LogParser(logIdentifier);
            lp.parseLogs(logFilePath);

            SummaryBuilder summaryBuilder = SummaryBuilder.v();
            SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

            summaryBuilder.loadFromJsonFile("/var/folders/b0/hktwh1nj2kjg1b93wt4n79rm0000gn/T/androlog/app.apk.summary");

            SummaryStatistics stats = new SummaryStatistics();
            stats.compareSummaries(summaryBuilder, summaryLogBuilder);
        }
    }
}