package dev.bradyshober.proxmox;

import hudson.model.Computer;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;

/**
 * A utility class for Proxmox agents that provides dynamic display-name rendering.
 *
 * <p>Since {@link DumbSlave} is final, we cannot extend it. Instead, this utility
 * provides a static method to compute the display name based on current build state,
 * accounting for the remaining build count when {@code maxBuildsPerAgent} is configured
 * on the {@link ProxmoxRetentionStrategy}.
 */
public class ProxmoxSlave {

    /**
     * Computes the display name for a Proxmox agent, optionally appending the remaining
     * build count when max-builds is configured.
     *
     * @param slave the Jenkins Slave node (typically a DumbSlave from ProxmoxCloud)
     * @return the display name to show in the UI
     */
    public static String getDisplayName(DumbSlave slave) {
        String baseName = slave.getNodeName();
        RetentionStrategy<?> rs = slave.getRetentionStrategy();
        if (!(rs instanceof ProxmoxRetentionStrategy strategy)) {
            return baseName;
        }
        int max = strategy.getMaxBuildsPerAgent();
        if (max <= 0) {
            return baseName;
        }
        Computer computer = slave.toComputer();
        if (computer == null) {
            return baseName + " (" + max + " builds remaining)";
        }
        @SuppressWarnings("deprecation")
        int completed = computer.getBuilds().size();
        int running = computer.countBusy();
        int remaining = Math.max(0, max - completed - running);
        return baseName + " (" + remaining + " builds remaining)";
    }
}


