package io.jenkins.plugins.bytebase;

import hudson.model.Action;

public final class BytebaseResultAction implements Action {
    private final java.util.List<String> reports = new java.util.ArrayList<>();

    synchronized void addReport(String command, String report) {
        reports.add(command + "\n" + report);
    }

    public synchronized String getReport() {
        return String.join("\n\n", reports);
    }

    @Override
    public String getIconFileName() {
        return "symbol-database";
    }

    @Override
    public String getDisplayName() {
        return "Bytebase results";
    }

    @Override
    public String getUrlName() {
        return "bytebase";
    }
}
