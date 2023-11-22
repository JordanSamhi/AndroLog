package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.AndroZooUtils;
import com.jordansamhi.androspecter.AndroidAppsProcessor;
import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOption;
import com.jordansamhi.androspecter.commandlineoptions.CommandLineOptions;
import com.jordansamhi.androspecter.instrumentation.Instrumenter;
import com.jordansamhi.androspecter.network.RedisManager;
import com.jordansamhi.androspecter.printers.Writer;

import java.util.Optional;

public class MainExperiments {
    public static void main(String[] args) {
        CommandLineOptions options = CommandLineOptions.v();
        options.setAppName("AndroLog");
        options.addOption(new CommandLineOption("platforms", "p", "Platform file", true, true));
        options.addOption(new CommandLineOption("output", "o", "Instrumented APK output", true, false));
        options.parseArgs(args);

        String logIdentifier = Optional.ofNullable(options.getOptionValue("log-identifier")).orElse("ANDROLOG");
        String outputApk = Optional.ofNullable(options.getOptionValue("output")).orElse(TmpFolder.v().get());


        SootUtils su = new SootUtils();

        RedisManager rm = new RedisManager("serval06.uni.lux", "6379", "AhT5Biepaix5uu8raepoh9Phoopohd");
        AndroZooUtils au = new AndroZooUtils("a0f2f8101be7b8f68398d163336c71413cb76efb3da4f62e2caaf440cc90f4e8");
        String redisRoot = "jordan:tests";
        int timeout = 60;
        AndroidAppsProcessor aap = new AndroidAppsProcessor(rm, au, redisRoot, timeout) {
            @Override
            protected void processApp(String s) {
                Writer.v().pinfo("Loading apk");
                su.setupSoot(CommandLineOptions.v().getOptionValue("platforms"), s, true);
                Writer.v().psuccess("Done.");
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
                Writer.v().psuccess("Done.");
            }

            @Override
            protected void processResults() {
                System.out.println("done");
            }
        };
        try {
            aap.run();
        }
        catch (Exception e) {
            System.out.println("*****error*****");
        }
    }
}