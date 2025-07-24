package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    private final String logIdentifier;
    private final SummaryBuilder summaryBuilder;
    private final SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

    private final Set<String> visitedStatements = new HashSet<>();
    private final Set<String> visitedMethods = new HashSet<>();
    private final Set<String> visitedClasses = new HashSet<>();
    private final Set<String> visitedActivities = new HashSet<>();
    private final Set<String> visitedServices = new HashSet<>();
    private final Set<String> visitedBroadcastReceivers = new HashSet<>();
    private final Set<String> visitedContentProviders = new HashSet<>();

    public LogParser(String logIdentifier, SummaryBuilder summaryBuilder) {
        this.logIdentifier = logIdentifier;
        this.summaryBuilder = summaryBuilder;

        for (String component : this.summaryBuilder.getVisitedComponents()) {
            String logType = getType(component);
            if (logType != null){
                switch (logType) {
                    case "statements":
                        int firstPipe = component.indexOf('|');
                        visitedStatements.add(component.substring(logType.length(),firstPipe));
                        break;
                    case "methods":
                        visitedMethods.add(component.substring(logType.length()));
                        break;
                    case "classes":
                        visitedClasses.add(component.substring(logType.length()));
                        break;
                    case "activities":
                        visitedActivities.add(component.substring(logType.length()));
                        break;
                    case "services":
                        visitedServices.add(component.substring(logType.length()));
                        break;
                    case "broadcast-receivers":
                        visitedBroadcastReceivers.add(component.substring(logType.length()));
                        break;
                    case "content-providers":
                        visitedContentProviders.add(component.substring(logType.length()));
                        break;
                }
            }
        }
    }

    private static String getType(String component) {
        String logType = null;
        String[] logTypes = {"statements", "methods", "classes", "activities", "services", "broadcast-receivers"};
        for (String lt : logTypes) {
            if (component.startsWith(lt)) {
                logType = lt;
            }
        }
        return logType;
    }

    public void parseLogs(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(logIdentifier)) {
                    parseLine(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseLine(String line) {
        String logType = getLogType(line);

        if (logType != null) {
            switch (logType) {
                case "STATEMENT":
                    int logIndex = line.indexOf(logType + "=");
                    int firstPipe = line.indexOf('|');
                    if (visitedStatements.contains(line.substring(logIndex, firstPipe))) {
                        summaryLogBuilder.incrementStatement(line);
                    }
                    break;
                case "METHOD":
                    if (visitedMethods.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementMethod(line);
                    }
                    break;
                case "CLASS":
                    if (visitedClasses.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementClass(line);
                    }
                    break;
                case "ACTIVITY":
                    if (visitedActivities.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementActivity(line);
                    }
                    break;
                case "SERVICE":
                    if (visitedServices.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementService(line);
                    }
                    break;
                case "BROADCASTRECEIVER":
                    if (visitedBroadcastReceivers.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementBroadcastReceiver(line);
                    }
                    break;
                case "CONTENTPROVIDER":
                    if (visitedContentProviders.contains(line.split(logType + "=")[1])) {
                        summaryLogBuilder.incrementContentProvider(line);
                    }
                    break;
            }
        }
    }

    private String getLogType(String line) {
        Pattern pattern = Pattern.compile("(\\w+?)=");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}