package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.slaves.ComputerConnector;
import hudson.slaves.ComputerConnectorDescriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import java.io.IOException;
import java.io.Serializable;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * JNLP connector for inbound (WebSocket) agents.
 * Host parameter is ignored since JNLP agents connect inbound.
 */
public class ProxmoxJNLPConnector extends ComputerConnector implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean webSocket;

    @DataBoundConstructor
    public ProxmoxJNLPConnector() {
        this.webSocket = false;
    }

    @DataBoundSetter
    public void setWebSocket(boolean webSocket) {
        this.webSocket = webSocket;
    }

    @Override
    public ComputerLauncher launch(@Nonnull String host, TaskListener listener) throws IOException {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(webSocket);
        return launcher;
    }

    public boolean isWebSocket() {
        return webSocket;
    }

    @Extension
    public static class DescriptorImpl extends ComputerConnectorDescriptor {
        @Override
        public String getDisplayName() {
            return "JNLP/WebSocket (Inbound Agent)";
        }
    }
}
