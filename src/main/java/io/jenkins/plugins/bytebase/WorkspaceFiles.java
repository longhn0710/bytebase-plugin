package io.jenkins.plugins.bytebase;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

final class WorkspaceFiles {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+(\\.\\d+)*)");

    private WorkspaceFiles() {}

    static void validateRelative(String path) throws AbortException {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.contains(":")
                || Arrays.asList(path.split("/")).contains("..")) {
            throw new AbortException("SQL patterns and output paths must be relative to the workspace without '..'");
        }
    }

    static FilePath child(FilePath workspace, String path) throws IOException, InterruptedException {
        validateRelative(path);
        if (!workspace.isDescendant(path)) {
            throw new AbortException("File path or symlink escapes the Jenkins workspace");
        }
        return workspace.child(path);
    }

    static JSONArray collect(FilePath workspace, BytebaseBuilder options, TaskListener listener)
            throws IOException, InterruptedException {
        FilePath[] files = workspace.list(options.getFilePattern());
        Arrays.sort(files, Comparator.comparing(FilePath::getRemote));
        if (files.length == 0 || files.length > 1000) {
            throw new AbortException("SQL pattern must match between 1 and 1000 files");
        }
        JSONArray releaseFiles = new JSONArray();
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        String version = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss"));
        long total = 0;
        for (FilePath file : files) {
            String relative = file.getRemote()
                    .substring(workspace.getRemote().length() + 1)
                    .replace('\\', '/');
            FilePath checked = child(workspace, relative);
            byte[] contents;
            try (InputStream input = checked.read()) {
                contents = input.readNBytes(MAX_BYTES + 1);
            }
            total += contents.length;
            if (total > MAX_BYTES) {
                throw new AbortException("SQL files exceed 16 MiB in total");
            }
            if (options.isDeclarative()) {
                if (options.getCommand().equals("rollout")) {
                    if (merged.size() > 0) {
                        merged.write('\n');
                    }
                    merged.write(contents);
                } else {
                    releaseFiles.add(file(relative, version, contents));
                }
            } else {
                Matcher matcher = VERSION.matcher(file.getName());
                if (!matcher.find()) {
                    listener.getLogger()
                            .println("Bytebase: ignoring SQL file without a migration version: " + relative);
                    continue;
                }
                releaseFiles.add(file(relative, matcher.group(1), contents));
            }
        }
        if (options.isDeclarative() && options.getCommand().equals("rollout")) {
            releaseFiles.add(file(options.getFilePattern(), version, merged.toByteArray()));
        }
        if (releaseFiles.isEmpty()) {
            throw new AbortException("No versioned SQL migrations found");
        }
        return releaseFiles;
    }

    private static JSONObject file(String path, String version, byte[] contents) {
        return BytebaseClient.object(
                "path",
                path,
                "version",
                version,
                "statement",
                Base64.getEncoder().encodeToString(contents));
    }
}
