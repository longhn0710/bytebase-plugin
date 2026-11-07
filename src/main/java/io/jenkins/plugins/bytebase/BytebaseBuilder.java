package io.jenkins.plugins.bytebase;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public final class BytebaseBuilder extends Builder implements SimpleBuildStep {
    private final String serverName;
    private final String command;
    private final String project;
    private String credentialsId = "";
    private String targets = "";
    private String filePattern = "";
    private boolean declarative;
    private String inspectionMode = "preflight";
    private String expectedUrl = "";
    private String expectedHost = "";
    private String expectedPort = "";
    private String expectedService = "";
    private String checkRelease = "SKIP";
    private String customRules = "";
    private String plan = "";
    private String targetStage = "";
    private String releaseIdTemplate = "release_{date}-RC{iteration}";
    private String releaseIdTimezone = "UTC";
    private String output = "bytebase-result.json";
    private int httpTimeoutSeconds = 120;
    private int waitTimeoutSeconds = 3600;
    private int pollIntervalSeconds = 5;
    private String vcsType = "";
    private String vcsUrl = "";

    @DataBoundConstructor
    public BytebaseBuilder(String serverName, String command, String project) {
        this.serverName = serverName;
        this.command = command;
        this.project = project;
    }

    private Object readResolve() {
        // XStream skips field initializers when loading jobs saved before these fields existed.
        inspectionMode = inspectionMode == null ? "preflight" : inspectionMode;
        expectedUrl = text(expectedUrl);
        expectedHost = text(expectedHost);
        expectedPort = text(expectedPort);
        expectedService = text(expectedService);
        return this;
    }

    public String getServerName() {
        return serverName;
    }

    public String getCommand() {
        return command;
    }

    public String getProject() {
        return project;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String value) {
        credentialsId = text(value);
    }

    public String getTargets() {
        return targets;
    }

    @DataBoundSetter
    public void setTargets(String value) {
        targets = text(value);
    }

    public String getFilePattern() {
        return filePattern;
    }

    @DataBoundSetter
    public void setFilePattern(String value) {
        filePattern = text(value);
    }

    public String getInspectionMode() {
        return inspectionMode;
    }

    @DataBoundSetter
    public void setInspectionMode(String value) {
        inspectionMode = text(value);
    }

    public String getExpectedUrl() {
        return expectedUrl;
    }

    @DataBoundSetter
    public void setExpectedUrl(String value) {
        expectedUrl = text(value);
    }

    public String getExpectedHost() {
        return expectedHost;
    }

    @DataBoundSetter
    public void setExpectedHost(String value) {
        expectedHost = text(value);
    }

    public String getExpectedPort() {
        return expectedPort;
    }

    @DataBoundSetter
    public void setExpectedPort(String value) {
        expectedPort = text(value);
    }

    public String getExpectedService() {
        return expectedService;
    }

    @DataBoundSetter
    public void setExpectedService(String value) {
        expectedService = text(value);
    }

    public boolean isDeclarative() {
        return declarative;
    }

    @DataBoundSetter
    public void setDeclarative(boolean value) {
        declarative = value;
    }

    public String getCheckRelease() {
        return checkRelease;
    }

    @DataBoundSetter
    public void setCheckRelease(String value) {
        checkRelease = text(value);
    }

    public String getCustomRules() {
        return customRules;
    }

    @DataBoundSetter
    public void setCustomRules(String value) {
        customRules = text(value);
    }

    public String getPlan() {
        return plan;
    }

    @DataBoundSetter
    public void setPlan(String value) {
        plan = text(value);
    }

    public String getTargetStage() {
        return targetStage;
    }

    @DataBoundSetter
    public void setTargetStage(String value) {
        targetStage = text(value);
    }

    public String getReleaseIdTemplate() {
        return releaseIdTemplate;
    }

    @DataBoundSetter
    public void setReleaseIdTemplate(String value) {
        releaseIdTemplate = text(value);
    }

    public String getReleaseIdTimezone() {
        return releaseIdTimezone;
    }

    @DataBoundSetter
    public void setReleaseIdTimezone(String value) {
        releaseIdTimezone = text(value);
    }

    public String getOutput() {
        return output;
    }

    @DataBoundSetter
    public void setOutput(String value) {
        output = text(value);
    }

    public int getHttpTimeoutSeconds() {
        return httpTimeoutSeconds;
    }

    @DataBoundSetter
    public void setHttpTimeoutSeconds(int value) {
        httpTimeoutSeconds = value;
    }

    public int getWaitTimeoutSeconds() {
        return waitTimeoutSeconds;
    }

    @DataBoundSetter
    public void setWaitTimeoutSeconds(int value) {
        waitTimeoutSeconds = value;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    @DataBoundSetter
    public void setPollIntervalSeconds(int value) {
        pollIntervalSeconds = value;
    }

    public String getVcsType() {
        return vcsType;
    }

    @DataBoundSetter
    public void setVcsType(String value) {
        vcsType = text(value);
    }

    public String getVcsUrl() {
        return vcsUrl;
    }

    @DataBoundSetter
    public void setVcsUrl(String value) {
        vcsUrl = text(value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    List<String> targetList() {
        return Arrays.stream(targets.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
    }

    void validate() throws AbortException {
        if (!Set.of("check", "rollout", "inspect").contains(text(command))) {
            throw new AbortException("command must be check, rollout or inspect");
        }
        if (!text(project).matches("projects/[^/\\s]+")) {
            throw new AbortException("project must be projects/{project}");
        }
        if (!Set.of("SKIP", "FAIL_ON_WARNING", "FAIL_ON_ERROR").contains(checkRelease)) {
            throw new AbortException("Invalid checkRelease; use SKIP, FAIL_ON_WARNING or FAIL_ON_ERROR");
        }
        if (httpTimeoutSeconds < 1
                || httpTimeoutSeconds > 600
                || waitTimeoutSeconds < 1
                || pollIntervalSeconds < 1
                || pollIntervalSeconds > 300) {
            throw new AbortException(
                    "HTTP timeout must be 1..600 seconds, poll interval 1..300 and wait timeout positive");
        }
        if (!targetStage.isEmpty() && !targetStage.matches("environments/[^/\\s]+")) {
            throw new AbortException("targetStage must be environments/{environment}");
        }
        if (command.equals("inspect")) {
            if (!Set.of("preflight", "ready", "done").contains(inspectionMode)
                    || targetList().size() != 1
                    || !targetList().get(0).matches("(?:projects/[^/\\s]+/)?instances/[^/\\s]+/databases/[^/\\s]+")
                    || targetStage.isBlank()) {
                throw new AbortException(
                        "Inspection requires preflight/ready/done mode, one database target and targetStage");
            }
            if (inspectionMode.equals("preflight")) {
                if (expectedHost.isBlank() || expectedPort.isBlank() || expectedService.isBlank()) {
                    throw new AbortException(
                            "Oracle preflight requires expectedHost, expectedPort and expectedService");
                }
            } else if (!plan.matches(java.util.regex.Pattern.quote(project) + "/plans/[^/\\s]+")) {
                throw new AbortException("Inspection plan must belong to the specified project");
            }
        } else if (command.equals("rollout") && !plan.isBlank()) {
            if (!plan.matches(java.util.regex.Pattern.quote(project) + "/plans/[^/\\s]+")) {
                throw new AbortException("plan must belong to the specified project");
            }
        } else {
            WorkspaceFiles.validateRelative(filePattern);
            List<String> values = targetList();
            int groups = 0;
            if (values.isEmpty()) {
                throw new AbortException("At least one target is required");
            }
            for (String target : values) {
                if (target.matches(java.util.regex.Pattern.quote(project) + "/databaseGroups/[^/\\s]+")) {
                    groups++;
                } else if (!target.matches("(?:projects/[^/\\s]+/)?instances/[^/\\s]+/databases/[^/\\s]+")) {
                    throw new AbortException("Invalid Bytebase target resource name");
                }
            }
            if (groups > 0 && values.size() != 1) {
                throw new AbortException("Use database targets or exactly one database group");
            }
        }
        if (!output.isBlank()) {
            WorkspaceFiles.validateRelative(output);
        }
        try {
            ZoneId.of(releaseIdTimezone);
        } catch (java.time.DateTimeException e) {
            throw new AbortException("Invalid releaseIdTimezone");
        }
        if (!vcsType.isBlank() || !vcsUrl.isBlank()) {
            if (!Set.of("GITHUB", "GITLAB", "BITBUCKET", "AZURE_DEVOPS").contains(vcsType)
                    || !vcsUrl.matches("https?://[^\\s]+")) {
                throw new AbortException("vcsType and HTTP(S) vcsUrl must both be specified");
            }
        }
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        validate();
        BytebaseServer server;
        try {
            server = BytebaseGlobalConfiguration.get().findServer(serverName);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        if (!expectedUrl.isBlank() && !server.getUrl().equals(expectedUrl.replaceAll("/+$", ""))) {
            throw new AbortException("Selected Bytebase connection URL does not match expectedUrl");
        }
        new BytebaseRunner(this, server, run, workspace, listener).execute();
    }

    @Extension
    @Symbol("bytebase")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> type) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Bytebase: check SQL, rollout or inspect";
        }

        public ListBoxModel doFillCommandItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Check SQL", "check");
            items.add("Rollout", "rollout");
            items.add("Inspect database or rollout (read only)", "inspect");
            return items;
        }

        public ListBoxModel doFillCheckReleaseItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("SKIP");
            items.add("FAIL_ON_WARNING");
            items.add("FAIL_ON_ERROR");
            return items;
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String serverName, @QueryParameter String credentialsId) {
            StandardListBoxModel items = new StandardListBoxModel();
            if (item == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !item.hasPermission(Item.CONFIGURE) || !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return items.includeCurrentValue(credentialsId);
            }
            BytebaseServer server;
            try {
                server = BytebaseGlobalConfiguration.get().findServer(serverName);
            } catch (IllegalArgumentException e) {
                return items.includeEmptyValue().includeCurrentValue(credentialsId);
            }
            var authentication = item instanceof Queue.Task task ? Tasks.getAuthenticationOf2(task) : ACL.SYSTEM2;
            var requirements = URIRequirementBuilder.fromUri(server.getUrl()).build();
            var matcher = CredentialsMatchers.anyOf(
                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                    CredentialsMatchers.instanceOf(StringCredentials.class));
            items.includeEmptyValue();
            if (item == null) {
                items.includeMatchingAs(
                        authentication, Jenkins.get(), StandardCredentials.class, requirements, matcher);
            } else {
                items.includeMatchingAs(authentication, item, StandardCredentials.class, requirements, matcher);
            }
            return items.includeCurrentValue(credentialsId);
        }

        @POST
        public ListBoxModel doFillServerNameItems() {
            ListBoxModel items = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.READ)) {
                return items;
            }
            BytebaseGlobalConfiguration.get().getServers().forEach(server -> items.add(server.getName()));
            return items;
        }
    }
}
