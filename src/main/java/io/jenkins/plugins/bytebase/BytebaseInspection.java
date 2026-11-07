package io.jenkins.plugins.bytebase;

import static io.jenkins.plugins.bytebase.BytebaseClient.object;

import hudson.AbortException;
import java.io.IOException;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

final class BytebaseInspection {
    private BytebaseInspection() {}

    static void execute(BytebaseClient client, BytebaseBuilder options, JSONObject result)
            throws IOException, InterruptedException {
        if (options.getInspectionMode().equals("preflight")) {
            preflight(client, options, result);
        } else {
            rollout(client, options, result);
        }
    }

    private static void preflight(BytebaseClient client, BytebaseBuilder options, JSONObject result)
            throws IOException, InterruptedException {
        JSONObject actuator = client.call("ActuatorService", "GetActuatorInfo", object());
        result.put("serverVersion", actuator.optString("version"));
        require(!actuator.optString("version").isBlank(), "Bytebase returned no server version");
        String target = options.targetList().get(0);
        JSONObject database = select(
                client.call("DatabaseService", "GetDatabase", object("name", target)),
                "name",
                "project",
                "state",
                "effectiveEnvironment",
                "syncStatus");
        result.put("database", database);
        require(
                target.equals(database.optString("name"))
                        && options.getProject().equals(database.optString("project"))
                        && "ACTIVE".equals(database.optString("state"))
                        && options.getTargetStage().equals(database.optString("effectiveEnvironment"))
                        && "OK".equals(database.optString("syncStatus")),
                "Database/project/environment/sync mismatch");

        String instanceName = target.substring(0, target.lastIndexOf("/databases/"));
        JSONObject raw = client.call("InstanceService", "GetInstance", object("name", instanceName));
        JSONObject instance = select(raw, "name", "engine", "state");
        JSONArray sources = new JSONArray();
        for (Object value : array(raw, "dataSources")) {
            JSONObject source = (JSONObject) value;
            if ("ADMIN".equals(source.optString("type"))) {
                sources.add(select(source, "username", "host", "port", "serviceName"));
            }
        }
        instance.put("dataSources", sources);
        result.put("instance", instance);
        require(
                instanceName.equals(instance.optString("name"))
                        && "ORACLE".equals(instance.optString("engine"))
                        && "ACTIVE".equals(instance.optString("state"))
                        && sources.size() == 1,
                "Expected an active Oracle instance with exactly one ADMIN data source");
        JSONObject source = sources.getJSONObject(0);
        require(
                "ADMIN".equals(source.optString("username"))
                        && options.getExpectedHost().equals(source.optString("host"))
                        && options.getExpectedPort().equals(source.optString("port"))
                        && options.getExpectedService().equals(source.optString("serviceName")),
                "Oracle connection is not the approved ADMIN endpoint for this lab target");
    }

    private static void rollout(BytebaseClient client, BytebaseBuilder options, JSONObject result)
            throws IOException, InterruptedException {
        String name = options.getPlan() + "/rollout";
        JSONObject raw = client.call("RolloutService", "GetRollout", object("name", name));
        JSONObject snapshot = select(raw, "name");
        JSONArray stages = new JSONArray();
        for (Object value : array(raw, "stages")) {
            JSONObject stage = (JSONObject) value;
            JSONObject filtered = select(stage, "name", "environment");
            JSONArray tasks = new JSONArray();
            for (Object item : array(stage, "tasks")) {
                tasks.add(select((JSONObject) item, "name", "target", "status", "type"));
            }
            filtered.put("tasks", tasks);
            stages.add(filtered);
        }
        snapshot.put("stages", stages);
        result.put("rollout", name);
        result.put("rolloutSnapshot", snapshot);
        require(
                name.equals(snapshot.optString("name")) && stages.size() == 1,
                "Unexpected rollout resource or number of stages");
        JSONObject stage = stages.getJSONObject(0);
        JSONArray tasks = array(stage, "tasks");
        require(
                options.getTargetStage().equals(stage.optString("environment")) && !tasks.isEmpty(),
                "Missing expected environment or rollout tasks");
        for (Object item : tasks) {
            JSONObject task = (JSONObject) item;
            require(
                    options.targetList().get(0).equals(task.optString("target"))
                            && "DATABASE_MIGRATE".equals(task.optString("type")),
                    "Unexpected rollout target or task type");
            if (options.getInspectionMode().equals("ready")) {
                require(
                        List.of("NOT_STARTED", "PENDING").contains(task.optString("status")),
                        "Task already executed/failed/canceled; inspect the original plan before retrying");
            } else {
                require(
                        "DONE".equals(task.optString("status")),
                        "Rollout is not verified DONE; SKIPPED is not accepted");
            }
        }
    }

    private static JSONObject select(JSONObject source, String... fields) {
        JSONObject selected = new JSONObject();
        for (String field : fields) {
            if (source.containsKey(field)) {
                selected.put(field, source.get(field));
            }
        }
        return selected;
    }

    private static JSONArray array(JSONObject source, String key) {
        JSONArray values = source.optJSONArray(key);
        return values == null ? new JSONArray() : values;
    }

    private static void require(boolean condition, String message) throws AbortException {
        if (!condition) {
            throw new AbortException(message);
        }
    }
}
