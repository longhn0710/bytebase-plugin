package io.jenkins.plugins.bytebase;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.AbortException;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

final class BytebaseClient {
    private final String url;
    private final String username;
    private final Secret password;
    private final boolean staticToken;
    private final Duration timeout;
    private final HttpClient http;
    private Secret token;

    BytebaseClient(String url, String username, String password, String token, int timeout) {
        this.url = url;
        this.username = username;
        this.password = Secret.fromString(password);
        this.token = token == null ? null : Secret.fromString(token);
        this.staticToken = token != null;
        this.timeout = Duration.ofSeconds(timeout);
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static BytebaseClient forServer(BytebaseServer server, Run<?, ?> run, String overrideId, int timeout)
            throws IOException {
        server.validate();
        String id = overrideId == null || overrideId.isBlank() ? server.getCredentialsId() : overrideId;
        if (id == null || id.isBlank()) {
            throw new AbortException(
                    "Select Bytebase credentials on the server or set credentialsId on the build step");
        }
        StandardCredentials credential = find(id, server.getUrl(), run);
        String username = null;
        String password = null;
        String token = null;
        if (credential instanceof StandardUsernamePasswordCredentials account) {
            username = account.getUsername();
            password = account.getPassword().getPlainText();
        } else if (credential instanceof StringCredentials accessToken) {
            token = accessToken.getSecret().getPlainText();
        } else {
            throw new AbortException("Bytebase needs Username with password or Secret text credentials");
        }
        return new BytebaseClient(server.getUrl(), username, password, token, timeout);
    }

    private static StandardCredentials find(String id, String url, Run<?, ?> run) throws IOException {
        StandardCredentials credential;
        if (run != null) {
            credential = CredentialsProvider.findCredentialById(
                    id,
                    StandardCredentials.class,
                    run,
                    URIRequirementBuilder.fromUri(url).build());
        } else {
            credential = CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardCredentials.class,
                            Jenkins.get(),
                            ACL.SYSTEM2,
                            URIRequirementBuilder.fromUri(url).build())
                    .stream()
                    .filter(c -> c.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }
        if (credential == null) {
            throw new AbortException("Bytebase credential is missing or unavailable to this job and URL");
        }
        return credential;
    }

    void login() throws IOException, InterruptedException {
        if (!staticToken) {
            JSONObject response = request(
                    "AuthService", "Login", object("email", username, "password", password.getPlainText()), false);
            token = Secret.fromString(response.optString("token"));
        }
        if (token == null || token.getPlainText().isBlank()) {
            throw new AbortException("Bytebase authentication returned no access token");
        }
    }

    JSONObject call(String service, String method, JSONObject body) throws IOException, InterruptedException {
        boolean safeRetry = method.startsWith("Get") || method.startsWith("List") || method.equals("CheckRelease");
        boolean refreshed = false;
        int transientRetries = 0;
        while (true) {
            try {
                return request(service, method, body, true);
            } catch (RpcException e) {
                if (e.status == 401 && !staticToken && !refreshed) {
                    login();
                    refreshed = true;
                } else if (safeRetry && Set.of(429, 502, 503, 504).contains(e.status) && transientRetries < 2) {
                    Thread.sleep(1000L << transientRetries++);
                } else {
                    throw e;
                }
            }
        }
    }

    private JSONObject request(String service, String method, JSONObject body, boolean authenticated)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + "/bytebase.v1." + service + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Connect-Protocol-Version", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (authenticated && token != null) {
            builder.header("Authorization", "Bearer " + token.getPlainText());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), ignored -> new LimitedBodySubscriber());
        } catch (IOException e) {
            // A lost response to a mutation is ambiguous; never replay it automatically.
            throw new AbortException("Bytebase " + method
                    + " network failure or oversized response; inspect Bytebase before retrying a mutation");
        }
        if (response.statusCode() != 200) {
            throw new RpcException(method, response.statusCode());
        }
        try {
            return JSONObject.fromObject(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new AbortException("Bytebase " + method + " returned invalid JSON");
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > 16 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Bytebase response exceeds 16 MiB"));
                    return;
                }
                byte[] chunk = new byte[size];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    static JSONObject object(Object... pairs) {
        JSONObject json = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            json.put(pairs[i].toString(), pairs[i + 1]);
        }
        return json;
    }

    private static final class RpcException extends IOException {
        private final int status;

        RpcException(String method, int status) {
            super("Bytebase " + method + " failed (HTTP " + status + "). "
                    + (status == 401
                            ? "Check the service account email and Service Key."
                            : status == 403
                                    ? "Permission denied; check this account's Bytebase project permissions. No fallback credential was used."
                                    : "Inspect Bytebase policies and server API compatibility before retrying."));
            this.status = status;
        }
    }
}
