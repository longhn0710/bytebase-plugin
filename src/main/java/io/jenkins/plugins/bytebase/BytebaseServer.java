package io.jenkins.plugins.bytebase;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public final class BytebaseServer extends AbstractDescribableImpl<BytebaseServer> {
    private final String name;
    private final String url;
    private final String credentialsId;

    @DataBoundConstructor
    public BytebaseServer(String name, String url, String credentialsId) {
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.credentialsId = credentialsId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    void validate() {
        URI uri = URI.create(url);
        if (name.isBlank()
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException(
                    "Server needs a name and an HTTP(S) URL without user info, query or fragment");
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<BytebaseServer> {
        @Override
        public String getDisplayName() {
            return "Bytebase connection";
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String url) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }
            StandardListBoxModel items = new StandardListBoxModel();
            items.includeEmptyValue();
            List<StandardCredentials> credentials = CredentialsProvider.lookupCredentialsInItemGroup(
                    StandardCredentials.class,
                    Jenkins.get(),
                    ACL.SYSTEM2,
                    URIRequirementBuilder.fromUri(url).build());
            for (StandardCredentials credential : credentials) {
                if (credential instanceof StringCredentials
                        || credential instanceof StandardUsernamePasswordCredentials) {
                    String label = credential.getDescription().isBlank()
                                    || credential.getDescription().equals(credential.getId())
                            ? credential.getId()
                            : credential.getDescription() + " (" + credential.getId() + ")";
                    String kind = credential instanceof StringCredentials ? "Secret text" : "Username with password";
                    items.add(label + " [" + kind + "]", credential.getId());
                }
            }
            return items;
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String name, @QueryParameter String url, @QueryParameter String credentialsId)
                throws InterruptedException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (credentialsId == null || credentialsId.isBlank()) {
                return FormValidation.error(
                        "Select the service account credential before testing. Use Jenkins Username with password: Username = Bytebase service account email, Password = Service Key.");
            }
            try {
                BytebaseServer server = new BytebaseServer(name, url, credentialsId);
                server.validate();
                BytebaseClient client = BytebaseClient.forServer(server, null, "", 15);
                client.login();
                client.call("UserService", "GetCurrentUser", new net.sf.json.JSONObject());
                String version = client.call("ActuatorService", "GetActuatorInfo", new net.sf.json.JSONObject())
                        .optString("version", "unknown");
                return FormValidation.ok("Connected. Bytebase " + version);
            } catch (IOException e) {
                return FormValidation.error(e.getMessage());
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Enter a connection name and a valid Bytebase base URL.");
            }
        }
    }
}
