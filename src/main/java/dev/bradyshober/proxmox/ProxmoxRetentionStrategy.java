package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Retention strategy for Proxmox-provisioned agents.
 * <p>
 * When an agent has been idle for more than {@code idleMinutes} minutes the strategy
 * disconnects the computer and delegates to {@link ProxmoxCloud#terminateInstance} to
 * stop and delete the backing VM, then removes the node from Jenkins.
 * </p>
 */
public class ProxmoxRetentionStrategy extends RetentionStrategy<SlaveComputer> {
    private static final Logger LOGGER = Logger.getLogger(ProxmoxRetentionStrategy.class.getName());

    /** Default idle timeout before the VM is terminated (5 minutes). */
    public static final int DEFAULT_IDLE_MINUTES = 5;
    /** 0 disables max-lifetime based rotation. */
    public static final int DEFAULT_MAX_LIFETIME_MINUTES = 0;
    /** Prefix used for temporary-offline reason while draining max-lifetime agents. */
    public static final String MAX_LIFETIME_DRAIN_REASON_PREFIX = "Exceeded max lifetime of ";

    private String cloudName;
    private String vmId;
    private int idleMinutes;
    private int maxLifetimeMinutes;
    private long createdAtMillis;

    @DataBoundConstructor
    public ProxmoxRetentionStrategy() {
        this(null, null, DEFAULT_IDLE_MINUTES, DEFAULT_MAX_LIFETIME_MINUTES);
    }

    public ProxmoxRetentionStrategy(String cloudName, String vmId, int idleMinutes) {
        this(cloudName, vmId, idleMinutes, DEFAULT_MAX_LIFETIME_MINUTES);
    }

    public ProxmoxRetentionStrategy(String cloudName, String vmId, int idleMinutes, int maxLifetimeMinutes) {
        this.cloudName = cloudName;
        this.vmId = vmId;
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : DEFAULT_IDLE_MINUTES;
        this.maxLifetimeMinutes = Math.max(0, maxLifetimeMinutes);
        this.createdAtMillis = System.currentTimeMillis();
    }

    @DataBoundSetter
    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    @DataBoundSetter
    public void setVmId(String vmId) {
        this.vmId = vmId;
    }

    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : DEFAULT_IDLE_MINUTES;
    }

    @DataBoundSetter
    public void setMaxLifetimeMinutes(int maxLifetimeMinutes) {
        this.maxLifetimeMinutes = Math.max(0, maxLifetimeMinutes);
    }

    /**
     * Called periodically by Jenkins to decide whether to keep or terminate the agent.
     *
     * @return number of minutes until the next check (1 minute), or 0 when terminating
     */
    @Override
    public synchronized long check(SlaveComputer computer) {
        if (createdAtMillis <= 0) {
            createdAtMillis = System.currentTimeMillis();
        }

        int effectiveIdleMinutes = idleMinutes;
        int effectiveMaxLifetimeMinutes = maxLifetimeMinutes;

        Jenkins jenkinsInstance = Jenkins.getInstanceOrNull();
        ProxmoxCloud proxmoxCloud = null;
        if (jenkinsInstance != null && cloudName != null && !cloudName.isBlank()) {
            hudson.slaves.Cloud cloud = jenkinsInstance.clouds.getByName(cloudName);
            if (cloud instanceof ProxmoxCloud resolvedCloud) {
                proxmoxCloud = resolvedCloud;
                // Cloud-level policy is authoritative for lifecycle thresholds.
                effectiveIdleMinutes = proxmoxCloud.getIdleMinutesBeforeTermination();
                effectiveMaxLifetimeMinutes = proxmoxCloud.getMaxLifetimeMinutes();
            }
        }

        boolean maxLifetimeExceeded = effectiveMaxLifetimeMinutes > 0
                && (System.currentTimeMillis() - createdAtMillis) >= (effectiveMaxLifetimeMinutes * 60_000L);

        if (maxLifetimeExceeded && !computer.isTemporarilyOffline()) {
            String offlineReason = MAX_LIFETIME_DRAIN_REASON_PREFIX + effectiveMaxLifetimeMinutes
                    + " minutes; draining running jobs before termination";
            computer.setTemporarilyOffline(true, new OfflineCause.ByCLI(offlineReason));
            LOGGER.log(
                    Level.INFO,
                    "Agent " + computer.getName() + " exceeded max lifetime of " + effectiveMaxLifetimeMinutes
                            + " min; marked temporarily offline for drain");
        }

        if (!computer.isIdle()) {
            return 1; // Busy - check again in 1 minute
        }

        if (jenkinsInstance != null) {
            if (cloudName == null || cloudName.isBlank() || vmId == null || vmId.isBlank()) {
                LOGGER.log(Level.WARNING, "Skipping VM termination because cloudName/vmId are not set on retention strategy");
                return 1;
            }

            if (proxmoxCloud == null) {
                LOGGER.log(Level.WARNING, "Could not find ProxmoxCloud '" + cloudName + "' for VM cleanup");
                return 1;
            }

            // Minimum floor takes precedence: keep idle agents alive until replacement capacity exists.
            if (!proxmoxCloud.canTerminateVmForScaleDown(vmId)) {
                LOGGER.log(
                        Level.FINE,
                        "Skipping idle termination for " + computer.getName()
                                + " because cloud minimum instance floor is reached");
                return 1;
            }
        }

        if (!maxLifetimeExceeded) {
            long idleMillis = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
            long idleMinutesElapsed = idleMillis / (60_000L);

            if (idleMinutesElapsed < effectiveIdleMinutes) {
                return 1; // Not idle long enough yet
            }

            LOGGER.log(
                    Level.INFO,
                    "Agent " + computer.getName() + " idle for " + idleMinutesElapsed + " min (threshold "
                            + effectiveIdleMinutes + " min); terminating VM " + vmId);
        } else {
            LOGGER.log(
                    Level.INFO,
                    "Agent " + computer.getName() + " reached max lifetime of " + effectiveMaxLifetimeMinutes
                            + " min and is now idle; terminating VM " + vmId);
        }

        try {
            computer.disconnect(null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error disconnecting computer " + computer.getName(), e);
        }

        // Locate the cloud and trigger VM termination when provenance is known.
        if (proxmoxCloud != null) {
            proxmoxCloud.terminateInstance(vmId);

            // Remove the orphaned node from Jenkins
            try {
                hudson.model.Node node = computer.getNode();
                if (node != null) {
                    jenkinsInstance.removeNode(node);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not remove node " + computer.getName() + " from Jenkins", e);
            }
        }

        return 0; // No more checks needed - node is being removed
    }

    @Override
    public void start(SlaveComputer computer) {
        computer.connect(false);
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getVmId() {
        return vmId;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public int getMaxLifetimeMinutes() {
        return maxLifetimeMinutes;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Proxmox VM Retention Strategy";
        }
    }
}
