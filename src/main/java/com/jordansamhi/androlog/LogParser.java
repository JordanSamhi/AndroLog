package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    private final String logIdentifier;
    private final SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

    public LogParser(String logIdentifier) {
        this.logIdentifier = logIdentifier;
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
                    summaryLogBuilder.incrementStatement(extractContent(line, "STATEMENT="));
                    break;
                case "METHOD":
                    summaryLogBuilder.incrementMethod(extractContent(line, "METHOD="));
                    break;
                case "CLASS":
                    summaryLogBuilder.incrementClass(extractContent(line, "CLASS="));
                    break;
                case "ACTIVITY":
                    summaryLogBuilder.incrementActivity(extractContent(line, "ACTIVITY="));
                    break;
                case "SERVICE":
                    summaryLogBuilder.incrementService(extractContent(line, "SERVICE="));
                    break;
                case "BROADCASTRECEIVER":
                    summaryLogBuilder.incrementBroadcastReceiver(extractContent(line, "BROADCASTRECEIVER="));
                    break;
                case "CONTENTPROVIDER":
                    summaryLogBuilder.incrementContentProvider(extractContent(line, "CONTENTPROVIDER="));
                    break;
            }
        }
    }

    private String extractContent(String line, String prefix) {
        Pattern pattern = Pattern.compile("=(.*)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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