package io.jenkins.plugins.bytebase;

import static io.jenkins.plugins.bytebase.BytebaseClient.object;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.HostnameSpecification;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpServer;
import hudson.AbortException;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BytebasePluginTest {
    private static final String PROJECT = "projects/example";
    private static final String PLAN = PROJECT + "/plans/12";
    private static final String ROLLOUT = PLAN + "/rollout";
    private static final String STAGE = ROLLOUT + "/stages/test";
    private static final String TARGET = "instances/example/databases/example";

    @Test
    void pipelineChecksOnAgentAndPersistsOutputBeforeQualityGate(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            api.handler = (method, body) -> method.equals("CheckRelease")
                    ? object(
                            "results",
                            List.of(object(
                                    "file",
                                    "V3__add_source_system.sql",
                                    "target",
                                    TARGET,
                                    "advices",
                                    List.of(object("status", "ERROR", "title", "Example policy")))))
                    : api.normal(method, body);
            j.createOnlineSlave();
            WorkflowJob job = j.createProject(WorkflowJob.class);
            job.setDefinition(new CpsFlowDefinition("""
                    node('!built-in') {
                      writeFile file: 'migrations/V3__add_source_system.sql', text: 'ALTER TABLE public.example ADD COLUMN source_system text;'
                      try {
                        bytebase serverName: 'test', command: 'check', project: 'projects/example',
                          targets: 'instances/example/databases/example', filePattern: 'migrations/**/*.sql', checkRelease: 'FAIL_ON_ERROR'
                      } finally { archiveArtifacts artifacts: 'bytebase-result.json' }
                    }
                    """, true));
            WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("SQL quality gate failed", build);
            assertEquals(1, build.getArtifacts().size());
            assertNotNull(build.getAction(BytebaseResultAction.class));
            assertTrue(build.getAction(BytebaseResultAction.class).getReport().contains("Example policy"));
            JSONObject request = api.bodies("CheckRelease").get(0);
            JSONObject file =
                    request.getJSONObject("release").getJSONArray("files").getJSONObject(0);
            assertEquals("3", file.getString("version"));
            assertTrue(new String(Base64.getDecoder().decode(file.getString("statement")), StandardCharsets.UTF_8)
                    .contains("source_system"));
            assertFalse(JenkinsRule.getLog(build).contains("account-secret"));
        }
    }

    @Test
    void freestyleAndConfigurationRoundtrip(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder options = options("check");
            options.setCustomRules("All tables need a primary key");
            job.getBuildersList().add(options);
            job = j.configRoundtrip(job);
            assertEquals(
                    "All tables need a primary key",
                    job.getBuildersList().get(BytebaseBuilder.class).getCustomRules());
            sql(j.jenkins.getWorkspaceFor(job));
            FreeStyleBuild build = j.buildAndAssertSuccess(job);
            assertTrue(j.jenkins
                    .getWorkspaceFor(job)
                    .child("bytebase-result.json")
                    .readToString()
                    .contains("checkResults"));
            assertEquals(
                    "All tables need a primary key",
                    api.bodies("CheckRelease").get(0).getString("customRules"));
            assertEquals(
                    200,
                    j.createWebClient()
                            .getPage(build, "bytebase/")
                            .getWebResponse()
                            .getStatusCode());
            var configurationPage = j.createWebClient().goTo("configure");
            assertTrue(configurationPage.asNormalizedText().contains("Bytebase"));
            j.submit(configurationPage.getFormByName("config"));
            BytebaseGlobalConfiguration.get().load();
            assertEquals(
                    "test",
                    BytebaseGlobalConfiguration.get().getServers().get(0).getName());
            assertEquals(
                    api.url(),
                    BytebaseGlobalConfiguration.get().getServers().get(0).getUrl());
            assertEquals(
                    "account",
                    BytebaseGlobalConfiguration.get().getServers().get(0).getCredentialsId());
        }
    }

    @Test
    void jobsSavedBeforeInspectionFieldsRemainExecutable(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            FreeStyleProject job = j.createFreeStyleProject();
            job.getBuildersList().add(options("check"));
            job.save();
            var configFile = job.getConfigFile().getFile().toPath();
            String xml = Files.readString(configFile);
            for (String field :
                    List.of("inspectionMode", "expectedUrl", "expectedHost", "expectedPort", "expectedService")) {
                xml = xml.replaceAll("(?s)<" + field + "(?:\\s[^>]*)?/>|<" + field + ">.*?</" + field + ">", "");
            }
            Files.writeString(configFile, xml);
            job.doReload();
            BytebaseBuilder restored = job.getBuildersList().get(BytebaseBuilder.class);
            assertEquals("preflight", restored.getInspectionMode());
            assertEquals("", restored.getExpectedUrl());
            assertEquals("", restored.getExpectedHost());
            assertEquals("", restored.getExpectedPort());
            assertEquals("", restored.getExpectedService());
            assertEquals("migrations/**/*.sql", restored.getFilePattern());
            sql(j.jenkins.getWorkspaceFor(job));
            j.buildAndAssertSuccess(job);
            assertEquals(1, api.bodies("CheckRelease").size());
            assertTrue(api.mutations().isEmpty());
        }
    }

    @Test
    void rolloutCreatesResourcesAndStopsAtTargetStage(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            AtomicInteger started = new AtomicInteger();
            api.handler = (method, body) -> {
                if (method.equals("GetRollout")) {
                    return object(
                            "name",
                            ROLLOUT,
                            "stages",
                            List.of(
                                    stage("test", started.get() > 0 ? "DONE" : "NOT_STARTED"),
                                    stage("prod", "NOT_STARTED")));
                }
                if (method.equals("BatchRunTasks")) {
                    started.incrementAndGet();
                }
                return api.normal(method, body);
            };
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder options = options("rollout");
            options.setTargetStage("environments/test");
            options.setPollIntervalSeconds(1);
            options.setVcsType("GITLAB");
            options.setVcsUrl("https://git.example.com/team/database/commit/example");
            job.getBuildersList().add(options);
            sql(j.jenkins.getWorkspaceFor(job));
            FreeStyleBuild build = j.buildAndAssertSuccess(job);
            assertEquals(1, started.get());
            assertEquals(STAGE, api.bodies("BatchRunTasks").get(0).getString("parent"));
            assertEquals(
                    List.of("CreateRelease", "CreatePlan", "CreateRollout"),
                    api.mutations().subList(0, 3));
            JSONObject result = JSONObject.fromObject(
                    j.jenkins.getWorkspaceFor(job).child("bytebase-result.json").readToString());
            assertEquals(ROLLOUT, result.getString("rollout"));
            assertEquals(PLAN, result.getString("plan"));
            assertEquals(
                    "GITLAB",
                    api.bodies("CreateRelease")
                            .get(0)
                            .getJSONObject("release")
                            .getJSONObject("vcsSource")
                            .getString("vcsType"));
            j.assertLogContains("stage complete: environments/test", build);
        }
    }

    @Test
    void existingPlanIgnoresFilesAndMissingTargetDoesNotStartTasks(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder options = options("rollout");
            options.setPlan(PLAN);
            options.setFilePattern("../ignored");
            options.setTargets("ignored");
            job.getBuildersList().add(options);
            j.buildAndAssertSuccess(job);
            assertEquals(List.of("CreateRollout"), api.mutations());
            assertTrue(api.bodies("GetRollout").isEmpty());
            options.setTargetStage("environments/missing");
            FreeStyleBuild failed = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("Target environment does not exist", failed);
            assertTrue(api.bodies("BatchRunTasks").isEmpty());
        }
    }

    @Test
    void declarativeFilesMergeOnlyDuringRollout(JenkinsRule j) throws Exception {
        FilePath workspace =
                new FilePath(Files.createTempDirectory("bytebase-workspace").toFile());
        try {
            workspace.child("schema/a.sql").write("CREATE SCHEMA public;", "UTF-8");
            workspace.child("schema/b.sql").write("CREATE TABLE public.example(id int);", "UTF-8");
            BytebaseBuilder options = options("check");
            options.setDeclarative(true);
            options.setFilePattern("schema/*.sql");
            JSONArray checked = WorkspaceFiles.collect(workspace, options, hudson.model.TaskListener.NULL);
            assertEquals(2, checked.size());
            BytebaseBuilder rollout = options("rollout");
            rollout.setDeclarative(true);
            rollout.setFilePattern("schema/*.sql");
            JSONArray merged = WorkspaceFiles.collect(workspace, rollout, hudson.model.TaskListener.NULL);
            assertEquals(1, merged.size());
            assertEquals(
                    "CREATE SCHEMA public;\nCREATE TABLE public.example(id int);",
                    new String(
                            Base64.getDecoder().decode(merged.getJSONObject(0).getString("statement")),
                            StandardCharsets.UTF_8));
        } finally {
            workspace.deleteRecursive();
        }
    }

    @Test
    void timeoutCancelsTaskRunsAcrossPagesAndPreservesResources(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            api.handler = (method, body) -> {
                if (method.equals("GetRollout")) {
                    return object("name", ROLLOUT, "stages", List.of(stage("test", "RUNNING")));
                }
                if (method.equals("ListTaskRuns")) {
                    boolean first = body.optString("pageToken").isEmpty();
                    return object(
                            "taskRuns",
                            List.of(object(
                                    "name",
                                    (first ? STAGE : ROLLOUT + "/stages/prod") + "/tasks/1/taskRuns/1",
                                    "status",
                                    first ? "RUNNING" : "PENDING")),
                            "nextPageToken",
                            first ? "next" : "");
                }
                return api.normal(method, body);
            };
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder options = options("rollout");
            options.setPlan(PLAN);
            options.setTargetStage("environments/test");
            options.setPollIntervalSeconds(1);
            options.setWaitTimeoutSeconds(1);
            job.getBuildersList().add(options);
            FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("Timed out", build);
            assertEquals(2, api.bodies("ListTaskRuns").size());
            assertEquals(2, api.bodies("BatchCancelTaskRuns").size());
            assertTrue(build.getAction(BytebaseResultAction.class).getReport().contains(ROLLOUT));
        }
    }

    @Test
    void failedTaskFailsBuildAndAbortCancelsRunningTasks(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            api.handler = (method, body) -> method.equals("GetRollout")
                    ? object("stages", List.of(stage("test", "FAILED")))
                    : api.normal(method, body);
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder options = options("rollout");
            options.setPlan(PLAN);
            options.setTargetStage("environments/test");
            options.setPollIntervalSeconds(1);
            job.getBuildersList().add(options);
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            api.handler = (method, body) -> {
                if (method.equals("GetRollout")) {
                    return object("stages", List.of(stage("test", "RUNNING")));
                }
                if (method.equals("ListTaskRuns")) {
                    return object(
                            "taskRuns", List.of(object("name", STAGE + "/tasks/1/taskRuns/1", "status", "RUNNING")));
                }
                return api.normal(method, body);
            };
            api.requests.clear();
            var future = job.scheduleBuild2(0);
            FreeStyleBuild build = future.waitForStart();
            j.waitForMessage("Bytebase rollout:", build);
            while (api.bodies("GetRollout").isEmpty()) {
                Thread.sleep(50);
            }
            build.getExecutor().interrupt();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(build));
            assertEquals(1, api.bodies("BatchCancelTaskRuns").size());
        }
    }

    @Test
    void validatesWorkspaceAndTargetBoundaries(JenkinsRule j) throws Exception {
        assertThrows(AbortException.class, () -> WorkspaceFiles.validateRelative("../secret.sql"));
        assertThrows(AbortException.class, () -> WorkspaceFiles.validateRelative("/tmp/secret.sql"));
        BytebaseBuilder mixed = options("check");
        mixed.setTargets(TARGET + ",projects/example/databaseGroups/all");
        assertThrows(AbortException.class, mixed::validate);
        BytebaseBuilder plan = options("rollout");
        plan.setPlan("projects/other/plans/12");
        assertThrows(AbortException.class, plan::validate);
        FilePath workspace =
                new FilePath(Files.createTempDirectory("bytebase-links").toFile());
        var outside = Files.createTempDirectory("bytebase-outside");
        try {
            Files.createSymbolicLink(
                    java.nio.file.Path.of(workspace.child("escape").getRemote()), outside);
            assertThrows(AbortException.class, () -> WorkspaceFiles.child(workspace, "escape/secret.sql"));
        } finally {
            workspace.deleteRecursive();
            Files.delete(outside);
        }
    }

    @Test
    void accessTokensAndAuthRefreshRespectRetryBoundaries(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            var store = SystemCredentialsProvider.getInstance().getCredentials();
            store.add(new StringCredentialsImpl(
                    CredentialsScope.GLOBAL, "token", "", Secret.fromString("example-token")));
            BytebaseServer server = new BytebaseServer("token", api.url(), "token");
            BytebaseClient client = BytebaseClient.forServer(server, null, "", 10);
            client.login();
            client.call("ReleaseService", "CheckRelease", object());
            assertTrue(api.bodies("Login").isEmpty());
            assertEquals("Bearer example-token", api.authorization);
            AtomicInteger calls = new AtomicInteger();
            api.status = method -> method.equals("GetPlan") && calls.getAndIncrement() == 0 ? 401 : 200;
            client = BytebaseClient.forServer(new BytebaseServer("test", api.url(), "account"), null, "", 10);
            client.login();
            client.call("PlanService", "GetPlan", object("name", PLAN));
            assertEquals(2, api.bodies("Login").size());
            api.status = method -> method.equals("CreateRelease") ? 503 : 200;
            BytebaseClient mutationClient = client;
            assertThrows(IOException.class, () -> mutationClient.call("ReleaseService", "CreateRelease", object()));
            assertEquals(1, api.bodies("CreateRelease").size());
        }
    }

    @Test
    void warningAndErrorQualityGatesAndMultipleReports(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            FreeStyleProject job = j.createFreeStyleProject();
            sql(j.jenkins.getWorkspaceFor(job));
            for (String severity : List.of("WARNING", "ERROR")) {
                api.handler = (method, body) -> method.equals("CheckRelease")
                        ? object("results", List.of(object("advices", List.of(object("status", severity)))))
                        : api.normal(method, body);
                for (String gate : List.of("SKIP", "FAIL_ON_ERROR", "FAIL_ON_WARNING")) {
                    BytebaseBuilder options = options("check");
                    options.setCheckRelease(gate);
                    job.getBuildersList().clear();
                    job.getBuildersList().add(options);
                    boolean fails =
                            !gate.equals("SKIP") && (severity.equals("ERROR") || gate.equals("FAIL_ON_WARNING"));
                    FreeStyleBuild build =
                            j.assertBuildStatus(fails ? Result.FAILURE : Result.SUCCESS, job.scheduleBuild2(0));
                    assertTrue(build.getAction(BytebaseResultAction.class)
                            .getReport()
                            .contains(severity));
                    assertTrue(j.jenkins
                            .getWorkspaceFor(job)
                            .child("bytebase-result.json")
                            .readToString()
                            .contains(severity));
                }
            }
            api.handler = api::normal;
            WorkflowJob pipeline = j.createProject(WorkflowJob.class);
            pipeline.setDefinition(new CpsFlowDefinition("""
                    node {
                      writeFile file: 'V1.sql', text: 'CREATE TABLE public.example(id int);'
                      bytebase serverName: 'test', command: 'check', project: 'projects/example',
                        targets: 'instances/example/databases/example', filePattern: '*.sql'
                      bytebase serverName: 'test', command: 'rollout', project: 'projects/example', plan: 'projects/example/plans/12'
                    }
                    """, true));
            WorkflowRun build = j.buildAndAssertSuccess(pipeline);
            assertEquals(1, build.getActions(BytebaseResultAction.class).size());
            String report = build.getAction(BytebaseResultAction.class).getReport();
            assertTrue(report.contains("checkResults"));
            assertTrue(report.contains(ROLLOUT));
        }
    }

    @Test
    void missingCredentialsDoNotCallBytebase(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            BytebaseGlobalConfiguration.get().setServers(List.of(new BytebaseServer("test", api.url(), "missing")));
            FreeStyleProject job = j.createFreeStyleProject();
            job.getBuildersList().add(options("check"));
            FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("credential is missing or unavailable", build);
            assertTrue(api.requests.isEmpty());
            configure(j, api);
            BytebaseGlobalConfiguration.get().setServers(List.of(new BytebaseServer("test", api.url(), "")));
            BytebaseBuilder override = options("check");
            override.setCredentialsId("account");
            job.getBuildersList().clear();
            job.getBuildersList().add(override);
            sql(j.jenkins.getWorkspaceFor(job));
            j.buildAndAssertSuccess(job);
            assertEquals(1, api.bodies("Login").size());
        }
    }

    @Test
    void namedConnectionsUseDistinctAccountsAndNeverFallback(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            var credentials = SystemCredentialsProvider.getInstance().getCredentials();
            credentials.add(
                    new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "a", "", "a@example.com", "key-a"));
            credentials.add(
                    new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "b", "", "b@example.com", "key-b"));
            BytebaseGlobalConfiguration.get()
                    .setServers(List.of(
                            new BytebaseServer("shared", api.url(), "a"),
                            new BytebaseServer("team-b", api.url(), "b")));
            api.handler = (method, body) -> method.equals("Login")
                    ? object("token", body.getString("email").equals("a@example.com") ? "token-a" : "token-b")
                    : api.normal(method, body);
            WorkflowJob pipeline = j.createProject(WorkflowJob.class);
            pipeline.setDefinition(new CpsFlowDefinition("""
                    node {
                      writeFile file: 'V1.sql', text: 'CREATE TABLE public.example(id int);'
                      bytebase serverName: 'shared', command: 'check', project: 'projects/example',
                        targets: 'instances/example/databases/example', filePattern: '*.sql'
                      bytebase serverName: 'team-b', command: 'check', project: 'projects/example',
                        targets: 'instances/example/databases/example', filePattern: '*.sql'
                    }
                    """, true));
            j.buildAndAssertSuccess(pipeline);
            assertEquals(
                    List.of("Bearer token-a", "Bearer token-b"),
                    api.requests.stream()
                            .filter(r -> r.getString("method").equals("CheckRelease"))
                            .map(r -> r.getString("authorization"))
                            .toList());
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder step = new BytebaseBuilder("team-b", "rollout", PROJECT);
            step.setTargets(TARGET);
            step.setFilePattern("migrations/*.sql");
            job.getBuildersList().add(step);
            sql(j.jenkins.getWorkspaceFor(job));
            api.requests.clear();
            api.status = method -> method.equals("Login") ? 401 : 200;
            FreeStyleBuild badKey = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("Service Key", badKey);
            assertEquals(1, api.bodies("Login").size());
            assertEquals("b@example.com", api.bodies("Login").get(0).getString("email"));
            assertTrue(api.mutations().isEmpty());
            api.requests.clear();
            step.setCredentialsId("missing-override");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            assertTrue(api.requests.isEmpty());
            step.setCredentialsId("");
            step = new BytebaseBuilder("team-b", "check", PROJECT);
            step.setTargets(TARGET);
            step.setFilePattern("migrations/*.sql");
            job.getBuildersList().clear();
            job.getBuildersList().add(step);
            api.status = method -> method.equals("CheckRelease") ? 403 : 200;
            FreeStyleBuild denied = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.assertLogContains("No fallback credential", denied);
            assertEquals(1, api.bodies("Login").size());
            assertTrue(api.mutations().isEmpty());
            api.requests.clear();
            step.setExpectedUrl("https://wrong.invalid");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            assertTrue(api.requests.isEmpty());
        }
    }

    @Test
    void connectionTestExplainsMissingAndInvalidServiceKeys(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            var web = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
            for (String path : List.of(
                    "descriptorByName/io.jenkins.plugins.bytebase.BytebaseServer/fillCredentialsIdItems?url="
                            + java.net.URLEncoder.encode(api.url(), StandardCharsets.UTF_8),
                    "descriptorByName/io.jenkins.plugins.bytebase.BytebaseBuilder/fillServerNameItems")) {
                var endpoint = j.getURL().toURI().resolve(path).toURL();
                var get = web.getPage(endpoint.toString()).getWebResponse();
                assertTrue(List.of(404, 405).contains(get.getStatusCode()), "Dropdown must reject GET");
                var post = web.loadWebResponse(web.addCrumb(new WebRequest(endpoint, HttpMethod.POST)));
                assertEquals(200, post.getStatusCode());
                assertTrue(post.getContentAsString().contains(path.contains("CredentialsId") ? "account" : "test"));
                assertFalse(post.getContentAsString().contains("account-secret"));
            }
            assertTrue(api.requests.isEmpty(), "Listing credentials and connections must not call Bytebase");
            var descriptor = new BytebaseServer.DescriptorImpl();
            assertEquals(
                    hudson.util.FormValidation.Kind.ERROR, descriptor.doTestConnection("test", api.url(), "").kind);
            assertEquals(
                    hudson.util.FormValidation.Kind.OK, descriptor.doTestConnection("test", api.url(), "account").kind);
            api.status = method -> method.equals("Login") ? 401 : 200;
            var failed = descriptor.doTestConnection("test", api.url(), "account");
            assertEquals(hudson.util.FormValidation.Kind.ERROR, failed.kind);
            assertTrue(failed.getMessage().contains("Service Key"));
            assertTrue(api.mutations().isEmpty());
        }
    }

    @Test
    void credentialOverrideDropdownHonorsJobPermissionsAndConnectionDomain(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new StringCredentialsImpl(
                            CredentialsScope.GLOBAL, "access-token", "", Secret.fromString("token-secret")));
            CredentialsProvider.lookupStores(j.jenkins)
                    .iterator()
                    .next()
                    .addDomain(
                            new Domain("other", "", List.of(new HostnameSpecification("other.invalid", null))),
                            List.of(new UsernamePasswordCredentialsImpl(
                                    CredentialsScope.GLOBAL,
                                    "other-account",
                                    "",
                                    "other@example.com",
                                    "other-secret")));
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder step = options("check");
            step.setCredentialsId("account");
            job.getBuildersList().add(step);
            job = j.configRoundtrip(job);
            assertEquals(
                    "account", job.getBuildersList().get(BytebaseBuilder.class).getCredentialsId());
            var descriptor = j.jenkins.getDescriptorByType(BytebaseBuilder.DescriptorImpl.class);
            assertEquals(
                    Set.of("", "account", "access-token", "removed-account"),
                    descriptor.doFillCredentialsIdItems(job, "test", "removed-account").stream()
                            .map(option -> option.value)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(
                    List.of("", "removed-account"),
                    descriptor.doFillCredentialsIdItems(job, "unknown", "removed-account").stream()
                            .map(option -> option.value)
                            .toList());
            var web = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
            var endpoint = job.getUrl()
                    + "descriptorByName/io.jenkins.plugins.bytebase.BytebaseBuilder/fillCredentialsIdItems?serverName=test";
            assertTrue(List.of(404, 405)
                    .contains(web.goTo(endpoint, null).getWebResponse().getStatusCode()));
            var post = web.loadWebResponse(web.addCrumb(
                    new WebRequest(j.getURL().toURI().resolve(endpoint).toURL(), HttpMethod.POST)));
            assertEquals(200, post.getStatusCode());
            assertTrue(post.getContentAsString().contains("access-token"));
            assertFalse(post.getContentAsString().contains("other-account"));
            assertFalse(post.getContentAsString().contains("account-secret"));
            assertFalse(post.getContentAsString().contains("token-secret"));
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                    .grant(Jenkins.READ, Item.READ)
                    .everywhere()
                    .to("reader", "editor")
                    .grant(Item.CONFIGURE)
                    .onItems(job)
                    .to("editor"));
            try (var ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
                assertTrue(descriptor.doFillCredentialsIdItems(job, "test", "").stream()
                        .allMatch(option -> option.value.isEmpty()));
                assertTrue(descriptor.doFillCredentialsIdItems(null, "test", "").stream()
                        .allMatch(option -> option.value.isEmpty()));
                assertEquals(
                        List.of("saved-id"),
                        descriptor.doFillCredentialsIdItems(job, "test", "saved-id").stream()
                                .map(option -> option.value)
                                .toList());
            }
            try (var ignored = ACL.as2(User.getById("editor", true).impersonate2())) {
                assertTrue(descriptor.doFillCredentialsIdItems(job, "test", "").stream()
                        .anyMatch(option -> option.value.equals("account")));
                assertTrue(descriptor.doFillCredentialsIdItems(null, "test", "").stream()
                        .allMatch(option -> option.value.isEmpty()));
            }
            assertTrue(api.requests.isEmpty(), "Rendering credentials must not call Bytebase");
        }
    }

    @Test
    void httpClientHonorsControllerNetworkSettingsWithoutFollowingRedirects(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            List<String> proxiedPaths = new CopyOnWriteArrayList<>();
            List<String> authorizations = new CopyOnWriteArrayList<>();
            AtomicInteger status = new AtomicInteger(200);
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            proxy.createContext("/", exchange -> {
                proxiedPaths.add(exchange.getRequestURI().toString());
                authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.getRequestBody().readAllBytes();
                byte[] response = object("token", "proxy-token").toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", api.url() + "/redirect-target");
                exchange.sendResponseHeaders(status.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            proxy.start();
            ProxyConfiguration original = j.jenkins.proxy;
            try {
                j.jenkins.proxy =
                        new ProxyConfiguration("127.0.0.1", proxy.getAddress().getPort());
                BytebaseServer server = new BytebaseServer("proxied", "http://bytebase.invalid", "account");
                BytebaseClient client = BytebaseClient.forServer(server, null, "", 5);
                client.login();
                client.call("ReleaseService", "CheckRelease", object());
                assertEquals(
                        List.of(
                                "http://bytebase.invalid/bytebase.v1.AuthService/Login",
                                "http://bytebase.invalid/bytebase.v1.ReleaseService/CheckRelease"),
                        proxiedPaths);
                assertEquals(List.of("null", "Bearer proxy-token"), authorizations);
                assertTrue(api.requests.isEmpty());
                j.jenkins.proxy.setNoProxyHost("127.0.0.1");
                BytebaseClient direct = BytebaseClient.forServer(
                        BytebaseGlobalConfiguration.get().findServer("test"), null, "", 5);
                direct.login();
                direct.call("ReleaseService", "CheckRelease", object());
                assertEquals(2, proxiedPaths.size(), "Excluded hosts must bypass the controller proxy");
                assertEquals(1, api.bodies("CheckRelease").size());
                api.requests.clear();
                status.set(307);
                IOException redirect =
                        assertThrows(IOException.class, () -> client.call("ReleaseService", "CheckRelease", object()));
                assertTrue(redirect.getMessage().contains("HTTP 307"));
                assertEquals(3, proxiedPaths.size());
                assertTrue(api.requests.isEmpty(), "Redirects must not forward the access token to another host");
            } finally {
                j.jenkins.proxy = original;
                proxy.stop(0);
            }
        }
    }

    @Test
    void nativeInspectionPreservesLabGuardsAndRedactsInstanceSecrets(JenkinsRule j) throws Exception {
        try (Api api = new Api()) {
            configure(j, api);
            JSONObject database = object(
                    "name",
                    TARGET,
                    "project",
                    PROJECT,
                    "state",
                    "ACTIVE",
                    "effectiveEnvironment",
                    "environments/test",
                    "syncStatus",
                    "OK");
            JSONObject instance = object(
                    "name",
                    "instances/example",
                    "engine",
                    "ORACLE",
                    "state",
                    "ACTIVE",
                    "dataSources",
                    List.of(object(
                            "type",
                            "ADMIN",
                            "username",
                            "ADMIN",
                            "host",
                            "lab.invalid",
                            "port",
                            "1521",
                            "serviceName",
                            "LAB",
                            "password",
                            "do-not-save-secret")));
            JSONObject rollout = object(
                    "name",
                    ROLLOUT,
                    "stages",
                    List.of(object(
                            "name",
                            STAGE,
                            "environment",
                            "environments/test",
                            "tasks",
                            List.of(object(
                                    "name",
                                    STAGE + "/tasks/1",
                                    "target",
                                    TARGET,
                                    "type",
                                    "DATABASE_MIGRATE",
                                    "status",
                                    "NOT_STARTED")))));
            api.handler = (method, body) -> switch (method) {
                case "GetDatabase" -> database;
                case "GetInstance" -> instance;
                case "GetRollout" -> rollout;
                default -> api.normal(method, body);
            };
            FreeStyleProject job = j.createFreeStyleProject();
            BytebaseBuilder inspect = new BytebaseBuilder("test", "inspect", PROJECT);
            inspect.setTargets(TARGET);
            inspect.setTargetStage("environments/test");
            inspect.setExpectedHost("lab.invalid");
            inspect.setExpectedPort("1521");
            inspect.setExpectedService("LAB");
            job.getBuildersList().add(inspect);
            FreeStyleBuild build = j.buildAndAssertSuccess(job);
            assertFalse(build.getAction(BytebaseResultAction.class).getReport().contains("do-not-save-secret"));
            assertFalse(j.jenkins
                    .getWorkspaceFor(job)
                    .child("bytebase-result.json")
                    .readToString()
                    .contains("do-not-save-secret"));
            database.put("project", "projects/other");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            database.put("project", PROJECT);
            instance.getJSONArray("dataSources").getJSONObject(0).put("host", "wrong.invalid");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            inspect.setInspectionMode("ready");
            inspect.setPlan(PLAN);
            j.buildAndAssertSuccess(job);
            JSONObject task = rollout.getJSONArray("stages")
                    .getJSONObject(0)
                    .getJSONArray("tasks")
                    .getJSONObject(0);
            task.put("status", "DONE");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            inspect.setInspectionMode("done");
            j.buildAndAssertSuccess(job);
            task.put("status", "SKIPPED");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            task.put("status", "DONE");
            task.put("target", "instances/example/databases/other");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            rollout.put("stages", List.of());
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            assertTrue(api.mutations().isEmpty());
        }
    }

    private static void configure(JenkinsRule j, Api api) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "account", "test", "ci@example.com", "account-secret"));
        BytebaseGlobalConfiguration.get().setServers(List.of(new BytebaseServer("test", api.url(), "account")));
    }

    private static BytebaseBuilder options(String command) {
        BytebaseBuilder value = new BytebaseBuilder("test", command, PROJECT);
        value.setFilePattern("migrations/**/*.sql");
        value.setTargets(TARGET);
        return value;
    }

    private static void sql(FilePath workspace) throws Exception {
        workspace
                .child("migrations/V3__add_source_system.sql")
                .write("ALTER TABLE public.example ADD COLUMN source_system text;", "UTF-8");
    }

    private static JSONObject stage(String environment, String status) {
        String name = ROLLOUT + "/stages/" + environment;
        return object(
                "name",
                name,
                "environment",
                "environments/" + environment,
                "tasks",
                List.of(object("name", name + "/tasks/1", "status", status)));
    }

    private static final class Api implements AutoCloseable {
        private final HttpServer server;
        final List<JSONObject> requests = new CopyOnWriteArrayList<>();
        volatile BiFunction<String, JSONObject, JSONObject> handler = this::normal;
        volatile java.util.function.ToIntFunction<String> status = ignored -> 200;
        volatile String authorization;

        Api() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestURI().getPath().replaceAll(".*/", "");
                JSONObject body = JSONObject.fromObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(object(
                        "method",
                        method,
                        "body",
                        body,
                        "authorization",
                        String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] response = handler.apply(method, body).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.applyAsInt(method), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<JSONObject> bodies(String method) {
            return requests.stream()
                    .filter(r -> r.getString("method").equals(method))
                    .map(r -> r.getJSONObject("body"))
                    .toList();
        }

        List<String> mutations() {
            return requests.stream()
                    .map(r -> r.getString("method"))
                    .filter(m -> m.startsWith("Create") || m.startsWith("Batch"))
                    .toList();
        }

        JSONObject normal(String method, JSONObject body) {
            return switch (method) {
                case "Login" -> object("token", "example-token");
                case "GetActuatorInfo" -> object("version", "3.22.1");
                case "CreateRelease" -> object("name", PROJECT + "/releases/example");
                case "CreatePlan", "GetPlan" -> object("name", PLAN);
                case "CreateRollout" -> object("name", ROLLOUT);
                case "GetRollout" -> object("name", ROLLOUT, "stages", List.of(stage("test", "DONE")));
                default -> object();
            };
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
