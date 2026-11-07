package io.jenkins.plugins.bytebase;

import static io.jenkins.plugins.bytebase.BytebaseClient.object;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

final class BytebaseRunner {
    private final BytebaseBuilder options;
    private final BytebaseServer server;
    private final Run<?, ?> run;
    private final FilePath workspace;
    private final TaskListener listener;
    private final JSONObject result = new JSONObject();
    private BytebaseClient client;

    BytebaseRunner(
            BytebaseBuilder options, BytebaseServer server, Run<?, ?> run, FilePath workspace, TaskListener listener) {
        this.options = options;
        this.server = server;
        this.run = run;
        this.workspace = workspace;
        this.listener = listener;
    }

    void execute() throws IOException, InterruptedException {
        FilePath output = options.getOutput().isBlank() ? null : WorkspaceFiles.child(workspace, options.getOutput());
        Throwable failure = null;
        try {
            client = BytebaseClient.forServer(server, run, options.getCredentialsId(), options.getHttpTimeoutSeconds());
            client.login();
            if (options.getCommand().equals("check")) {
                check();
            } else if (options.getCommand().equals("inspect")) {
                BytebaseInspection.execute(client, options, result);
                listener.getLogger().println("Bytebase inspection passed: " + options.getInspectionMode());
            } else {
                rollout();
            }
        } catch (InterruptedException | WaitTimeoutException e) {
            failure = e;
            boolean interrupted = Thread.interrupted();
            try {
                cancel();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                synchronized (run) {
                    BytebaseResultAction action = run.getAction(BytebaseResultAction.class);
                    if (action == null) {
                        action = new BytebaseResultAction();
                        run.addAction(action);
                    }
                    action.addReport(options.getCommand(), result.toString(2));
                    run.save();
                }
                if (output != null) {
                    // Revalidate after API work in case a workspace symlink has changed.
                    output = WorkspaceFiles.child(workspace, options.getOutput());
                    FilePath parent = output.getParent();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    output.write(result.toString(2), "UTF-8");
                }
            } catch (IOException | InterruptedException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
                listener.getLogger()
                        .println(
                                "Bytebase: could not save all output; inspect build results and workspace availability");
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private JSONObject release() throws IOException, InterruptedException {
        JSONObject release = object(
                "type",
                options.isDeclarative() ? "DECLARATIVE" : "VERSIONED",
                "files",
                WorkspaceFiles.collect(workspace, options, listener));
        if (!options.getVcsUrl().isBlank()) {
            release.put("vcsSource", object("vcsType", options.getVcsType(), "url", options.getVcsUrl()));
        }
        return release;
    }

    private void check() throws IOException, InterruptedException {
        JSONObject response = client.call(
                "ReleaseService",
                "CheckRelease",
                object(
                        "parent",
                        options.getProject(),
                        "release",
                        release(),
                        "targets",
                        options.targetList(),
                        "customRules",
                        options.getCustomRules()));
        result.put("checkResults", response);
        int errors = 0;
        int warnings = 0;
        for (Object item : array(response, "results")) {
            for (Object entry : array((JSONObject) item, "advices")) {
                String status = ((JSONObject) entry).optString("status");
                if (status.equals("ERROR")) {
                    errors++;
                }
                if (status.equals("WARNING")) {
                    warnings++;
                }
            }
        }
        listener.getLogger().printf("Bytebase SQL review: %d error(s), %d warning(s).%n", errors, warnings);
        if (!options.getCheckRelease().equals("SKIP")
                && (errors > 0 || (warnings > 0 && options.getCheckRelease().equals("FAIL_ON_WARNING")))) {
            throw new AbortException("Bytebase SQL quality gate failed; see Bytebase results on this build");
        }
    }

    private void rollout() throws IOException, InterruptedException {
        String plan;
        if (!options.getPlan().isBlank()) {
            JSONObject existing = client.call("PlanService", "GetPlan", object("name", options.getPlan()));
            plan = resource(existing, options.getProject() + "/plans/");
        } else {
            JSONObject created = client.call(
                    "ReleaseService",
                    "CreateRelease",
                    object(
                            "parent",
                            options.getProject(),
                            "release",
                            release(),
                            "releaseIdTemplate",
                            options.getReleaseIdTemplate(),
                            "releaseIdTimezone",
                            options.getReleaseIdTimezone()));
            String releaseName = resource(created, options.getProject() + "/releases/");
            record("release", releaseName);
            JSONObject spec = object(
                    "id",
                    UUID.randomUUID().toString(),
                    "changeDatabaseConfig",
                    object("targets", options.targetList(), "release", releaseName));
            JSONObject createdPlan = client.call(
                    "PlanService",
                    "CreatePlan",
                    object(
                            "parent",
                            options.getProject(),
                            "plan",
                            object(
                                    "title",
                                    releaseName.substring(releaseName.lastIndexOf('/') + 1),
                                    "specs",
                                    List.of(spec))));
            plan = resource(createdPlan, options.getProject() + "/plans/");
        }
        record("plan", plan);
        JSONObject rollout = client.call("RolloutService", "CreateRollout", object("parent", plan));
        String rolloutName = rollout.optString("name");
        if (!rolloutName.equals(plan + "/rollout")) {
            throw new AbortException("Unexpected Bytebase rollout resource name");
        }
        record("rollout", rolloutName);
        if (options.getTargetStage().isBlank()) {
            listener.getLogger()
                    .println(
                            "Bytebase: rollout created. Server auto-rollout policies may execute tasks independently.");
            return;
        }
        waitForRollout(rolloutName);
    }

    private void record(String key, String resource) {
        result.put(key, resource);
        listener.getLogger().println("Bytebase " + key + ": " + server.getUrl() + "/" + resource);
    }

    private void waitForRollout(String name) throws IOException, InterruptedException {
        long deadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(options.getWaitTimeoutSeconds());
        JSONObject rollout = getRollout(name);
        List<String> stages = new ArrayList<>();
        boolean found = false;
        for (Object item : array(rollout, "stages")) {
            JSONObject stage = (JSONObject) item;
            stages.add(stage.getString("name"));
            if (stage.optString("environment").equals(options.getTargetStage())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AbortException(
                    "Target environment does not exist in this rollout; no tasks were started by Jenkins");
        }
        for (String stageName : stages) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Bytebase rollout interrupted");
                }
                if (System.nanoTime() >= deadline) {
                    throw new WaitTimeoutException();
                }
                JSONObject current = getRollout(name);
                JSONObject stage = null;
                for (Object item : array(current, "stages")) {
                    JSONObject candidate = (JSONObject) item;
                    if (candidate.optString("name").equals(stageName)) {
                        stage = candidate;
                        break;
                    }
                }
                if (stage == null) {
                    throw new AbortException("Rollout stages changed while Jenkins was waiting");
                }
                List<String> pending = new ArrayList<>();
                boolean done = true;
                for (Object item : array(stage, "tasks")) {
                    JSONObject task = (JSONObject) item;
                    switch (task.optString("status")) {
                        case "DONE", "SKIPPED":
                            break;
                        case "FAILED", "CANCELED":
                            throw new AbortException("Bytebase rollout has failed or canceled tasks");
                        case "NOT_STARTED":
                            pending.add(task.getString("name"));
                            done = false;
                            break;
                        default:
                            done = false;
                    }
                }
                if (done) {
                    listener.getLogger().println("Bytebase stage complete: " + stage.optString("environment"));
                    break;
                }
                if (!pending.isEmpty()) {
                    client.call("RolloutService", "BatchRunTasks", object("parent", stageName, "tasks", pending));
                }
                Thread.sleep(options.getPollIntervalSeconds() * 1000L);
            }
        }
    }

    private JSONObject getRollout(String name) throws IOException, InterruptedException {
        return client.call("RolloutService", "GetRollout", object("name", name));
    }

    private void cancel() {
        if (!result.containsKey("rollout") || options.getTargetStage().isBlank()) {
            return;
        }
        listener.getLogger().println("Bytebase: build interrupted or timed out; canceling pending/running task runs.");
        try {
            BytebaseClient cleanup = BytebaseClient.forServer(server, run, options.getCredentialsId(), 15);
            cleanup.login();
            cancelTaskRuns(cleanup, result.getString("rollout"));
        } catch (IOException | InterruptedException e) {
            listener.getLogger().println("Bytebase: task run cancellation failed; check rollout status in Bytebase.");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void cancelTaskRuns(BytebaseClient client, String rollout) throws IOException, InterruptedException {
        String page = "";
        HashSet<String> seen = new HashSet<>();
        Map<String, List<String>> byStage = new LinkedHashMap<>();
        do {
            if (!seen.add(page)) {
                throw new AbortException("Repeated Bytebase task run page token");
            }
            JSONObject response = client.call(
                    "RolloutService",
                    "ListTaskRuns",
                    object("parent", rollout + "/stages/-/tasks/-", "pageSize", 1000, "pageToken", page));
            for (Object item : array(response, "taskRuns")) {
                JSONObject taskRun = (JSONObject) item;
                if (!List.of("RUNNING", "PENDING").contains(taskRun.optString("status"))) {
                    continue;
                }
                String name = taskRun.getString("name");
                if (!name.startsWith(rollout + "/stages/") || !name.contains("/tasks/")) {
                    throw new AbortException("Unexpected Bytebase task run resource name");
                }
                String stage = name.substring(0, name.indexOf("/tasks/"));
                byStage.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(name);
            }
            page = response.optString("nextPageToken");
        } while (!page.isEmpty());
        IOException failure = null;
        for (Map.Entry<String, List<String>> entry : byStage.entrySet()) {
            try {
                client.call(
                        "RolloutService",
                        "BatchCancelTaskRuns",
                        object("parent", entry.getKey() + "/tasks/-", "taskRuns", entry.getValue()));
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String resource(JSONObject response, String prefix) throws AbortException {
        String name = response.optString("name");
        if (!name.startsWith(prefix) || !name.substring(prefix.length()).matches("[^/\\s]+")) {
            throw new AbortException("Unexpected Bytebase resource name");
        }
        return name;
    }

    private static JSONArray array(JSONObject object, String key) {
        JSONArray array = object.optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }

    private static final class WaitTimeoutException extends AbortException {
        WaitTimeoutException() {
            super("Timed out waiting for Bytebase rollout");
        }
    }
}
