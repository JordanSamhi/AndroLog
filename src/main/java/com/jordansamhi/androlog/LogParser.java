package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * LogParser is a class responsible for parsing log files and incrementing
 * counts of various components such as statements, methods, classes, activities,
 * services, broadcast receivers, and content providers in a summary log.
 */
public class LogParser {
    private final String logIdentifier;
    private final SummaryLogBuilder summaryLogBuilder = SummaryLogBuilder.v();

    /**
     * Constructs a LogParser with a specific log identifier.
     *
     * @param logIdentifier The identifier used to recognize relevant log entries.
     */
    public LogParser(String logIdentifier) {
        this.logIdentifier = logIdentifier;
    }

    /**
     * Parses a log file and updates the summary log based on the contents of the file.
     *
     * @param filePath The path of the log file to be parsed.
     */
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

    /**
     * Parses an individual line from the log file and updates the summary log
     * based on the type of log entry detected.
     *
     * @param line The line from the log file to be parsed.
     */
    private void parseLine(String line) {
        String contentAfterIdentifier = extractContentAfterIdentifier(line);

        switch (getLogType(contentAfterIdentifier)) {
            case "STATEMENT":
                summaryLogBuilder.incrementStatement(extractContent(contentAfterIdentifier, "STATEMENT="));
                break;
            case "METHOD":
                summaryLogBuilder.incrementMethod(extractContent(contentAfterIdentifier, "METHOD="));
                break;
            case "CLASS":
                summaryLogBuilder.incrementClass(extractContent(contentAfterIdentifier, "CLASS="));
                break;
            case "ACTIVITY":
                summaryLogBuilder.incrementActivity(extractContent(contentAfterIdentifier, "ACTIVITY="));
                break;
            case "SERVICE":
                summaryLogBuilder.incrementService(extractContent(contentAfterIdentifier, "SERVICE="));
                break;
            case "BROADCASTRECEIVER":
                summaryLogBuilder.incrementBroadcastReceiver(extractContent(contentAfterIdentifier, "BROADCASTRECEIVER="));
                break;
            case "CONTENTPROVIDER":
                summaryLogBuilder.incrementContentProvider(extractContent(contentAfterIdentifier, "CONTENTPROVIDER="));
                break;
        }
    }

    /**
     * Extracts the content after the log identifier in a log line.
     *
     * @param line The log line containing the log identifier.
     * @return The content after the log identifier.
     */
    private String extractContentAfterIdentifier(String line) {
        return line.substring(line.indexOf(logIdentifier) + logIdentifier.length()).trim();
    }

    /**
     * Extracts the specific content of a log entry based on a prefix.
     *
     * @param line   The line from the log file.
     * @param prefix The prefix used to identify the start of the content.
     * @return The extracted content after the prefix.
     */
    private String extractContent(String line, String prefix) {
        return line.substring(prefix.length());
    }

    /**
     * Determines the type of log entry based on its content.
     *
     * @param content The content of the log entry.
     * @return The type of the log entry.
     */
    private String getLogType(String content) {
        int endIndex = content.indexOf('=');
        return endIndex > 0 ? content.substring(0, endIndex) : content;
    }
}