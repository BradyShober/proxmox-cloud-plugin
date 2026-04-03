package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;

/**
 * WebSocket-based inbound launcher for Proxmox-provisioned agents.
 *
 * <p>This must extend {@link JNLPLauncher} so Jenkins recognizes the node as an
 * inbound agent and accepts the WebSocket remoting connection initiated by the VM.
 */
public class ProxmoxWebSocketLauncher extends JNLPLauncher {

    public ProxmoxWebSocketLauncher() {
        this(null);
    }

    public ProxmoxWebSocketLauncher(String ipAddress) {
        super();
        setWebSocket(true);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Proxmox WebSocket Launcher";
        }
    }
}
