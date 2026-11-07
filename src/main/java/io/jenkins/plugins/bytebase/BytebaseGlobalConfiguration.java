package io.jenkins.plugins.bytebase;

import hudson.Extension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

@Extension
@Symbol("bytebaseConfiguration")
public final class BytebaseGlobalConfiguration extends GlobalConfiguration {
    private List<BytebaseServer> servers = new ArrayList<>();

    public BytebaseGlobalConfiguration() {
        load();
    }

    public static BytebaseGlobalConfiguration get() {
        return GlobalConfiguration.all().get(BytebaseGlobalConfiguration.class);
    }

    public List<BytebaseServer> getServers() {
        return List.copyOf(servers);
    }

    @DataBoundSetter
    public void setServers(List<BytebaseServer> servers) {
        List<BytebaseServer> values = servers == null ? List.of() : List.copyOf(servers);
        HashSet<String> names = new HashSet<>();
        for (BytebaseServer server : values) {
            server.validate();
            if (!names.add(server.getName())) {
                throw new IllegalArgumentException("Bytebase server names must be unique");
            }
        }
        this.servers = values;
        save();
    }

    BytebaseServer findServer(String name) {
        return servers.stream()
                .filter(server -> server.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Bytebase server; configure it in Manage Jenkins / System"));
    }

    @Override
    public boolean configure(StaplerRequest2 request, JSONObject json) throws FormException {
        request.bindJSON(this, json);
        save();
        return true;
    }
}
