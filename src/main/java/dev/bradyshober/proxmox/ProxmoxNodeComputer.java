package dev.bradyshober.proxmox;

import hudson.slaves.SlaveComputer;
import javax.annotation.Nonnull;

/**
 * Runtime computer for {@link ProxmoxNode}.
 */
public class ProxmoxNodeComputer extends SlaveComputer {

    public ProxmoxNodeComputer(ProxmoxNode node) {
        super(node);
    }

    @Override
    public ProxmoxNode getNode() {
        return (ProxmoxNode) super.getNode();
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        ProxmoxNode node = getNode();
        if (node == null) {
            return getName();
        }
        return node.getDisplayName();
    }
}
