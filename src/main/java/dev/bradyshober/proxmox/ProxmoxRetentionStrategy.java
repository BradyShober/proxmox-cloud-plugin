package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.model.Slave;
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
    private static final String AGENT_PREFIX = "Agent ";

    /** Default idle timeout before the VM is terminated (5 minutes). */
    public static final int DEFAULT_IDLE_MINUTES = 5;
    /** 0 disables max-lifetime based rotation. */
    public static final int DEFAULT_MAX_LIFETIME_MINUTES = 0;
    /** Prefix used for temporary-offline reason while draining max-lifetime agents. */
    public static final String MAX_LIFETIME_DRAIN_REASON_PREFIX = "Exceeded max lifetime of ";
    /** Prefix used for temporary-offline reason while draining max-builds agents. */
    public static final String MAX_BUILDS_DRAIN_REASON_PREFIX = "Reached max build count of ";

    private volatile String cloudName;
    private volatile String vmId;
    private int idleMinutes;
    private int maxLifetimeMinutes;
    private int maxBuildsPerAgent;
    private long createdAtMillis;

    @DataBoundConstructor
    public ProxmoxRetentionStrategy() {
        this(null, null, DEFAULT_IDLE_MINUTES, DEFAULT_MAX_LIFETIME_MINUTES, 0);
    }

    public ProxmoxRetentionStrategy(String cloudName, String vmId, int idleMinutes) {
        this(cloudName, vmId, idleMinutes, DEFAULT_MAX_LIFETIME_MINUTES, 0);
    }

    public ProxmoxRetentionStrategy(String cloudName, String vmId, int idleMinutes, int maxLifetimeMinutes) {
        this(cloudName, vmId, idleMinutes, maxLifetimeMinutes, 0);
    }

    public ProxmoxRetentionStrategy(
            String cloudName, String vmId, int idleMinutes, int maxLifetimeMinutes, int maxBuildsPerAgent) {
        this.cloudName = cloudName;
        this.vmId = vmId;
        this.idleMinutes = idleMinutes > 0 ? idleMinutes : DEFAULT_IDLE_MINUTES;
        this.maxLifetimeMinutes = Math.max(0, maxLifetimeMinutes);
        this.maxBuildsPerAgent = Math.max(0, maxBuildsPerAgent);
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

    @DataBoundSetter
    public void setMaxBuildsPerAgent(int maxBuildsPerAgent) {
        this.maxBuildsPerAgent = Math.max(0, maxBuildsPerAgent);
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

        String configuredCloudName = cloudName;
        String configuredVmId = vmId;
        int effectiveIdleMinutes = idleMinutes;
        int effectiveMaxLifetimeMinutes = maxLifetimeMinutes;
        int effectiveMaxBuilds = maxBuildsPerAgent;

        Jenkins jenkinsInstance = Jenkins.getInstanceOrNull();
        ProxmoxCloud proxmoxCloud = null;
        if (jenkinsInstance != null && configuredCloudName != null && !configuredCloudName.isBlank()) {
            hudson.slaves.Cloud cloud = jenkinsInstance.clouds.getByName(configuredCloudName);
            if (cloud instanceof ProxmoxCloud resolvedCloud) {
                proxmoxCloud = resolvedCloud;
                // Cloud-level policy is authoritative for lifecycle thresholds.
                effectiveIdleMinutes = proxmoxCloud.getIdleMinutesBeforeTermination();
                effectiveMaxLifetimeMinutes = proxmoxCloud.getMaxLifetimeMinutes();
                effectiveMaxBuilds = proxmoxCloud.getMaxBuildsPerAgent();
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
                    AGENT_PREFIX + computer.getName() + " exceeded max lifetime of " + effectiveMaxLifetimeMinutes
                            + " min; marked temporarily offline for drain");
        }

        boolean maxBuildsExceeded = false;
        if (effectiveMaxBuilds > 0) {
            @SuppressWarnings("deprecation")
            int completedBuilds = computer.getBuilds().size();
            int runningBuilds = computer.countBusy();
            int remaining = Math.max(0, effectiveMaxBuilds - completedBuilds - runningBuilds);
            maxBuildsExceeded = (completedBuilds + runningBuilds) >= effectiveMaxBuilds;

            hudson.model.Node node = computer.getNode();
            if (node instanceof ProxmoxNode proxmoxNode) {
                // Keep UI state in sync with real execution history to avoid stale counters.
                proxmoxNode.setBuildsRemaining(remaining);
            }
        }

        // Mark offline immediately if max builds is exceeded, before checking idle
        if (maxBuildsExceeded && !computer.isTemporarilyOffline()) {
            String offlineReason = MAX_BUILDS_DRAIN_REASON_PREFIX + effectiveMaxBuilds
                    + " builds; draining running jobs before termination";
            computer.setTemporarilyOffline(true, new OfflineCause.ByCLI(offlineReason));
            LOGGER.log(
                    Level.INFO,
                    AGENT_PREFIX + computer.getName() + " reached max build count of " + effectiveMaxBuilds
                            + "; marked temporarily offline for drain");
        }

        if (!computer.isIdle()) {
            return 1; // Busy - check again in 1 minute
        }

        if (jenkinsInstance != null) {
            if (configuredCloudName == null
                    || configuredCloudName.isBlank()
                    || configuredVmId == null
                    || configuredVmId.isBlank()) {
                LOGGER.log(
                        Level.WARNING,
                        "Skipping VM termination because cloudName/vmId are not set on retention strategy");
                return 1;
            }

            if (proxmoxCloud == null) {
                LOGGER.log(Level.WARNING, "Could not find ProxmoxCloud '" + configuredCloudName + "' for VM cleanup");
                return 1;
            }

            // Minimum floor takes precedence: keep idle agents alive until replacement capacity exists.
            if (!proxmoxCloud.canTerminateVmForScaleDown(configuredVmId)) {
                LOGGER.log(
                        Level.FINE,
                        "Skipping idle termination for " + computer.getName()
                                + " because cloud minimum instance floor is reached");
                return 1;
            }
        }

        if (!maxLifetimeExceeded && !maxBuildsExceeded) {
            long idleMillis = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
            long idleMinutesElapsed = idleMillis / (60_000L);

            if (idleMinutesElapsed < effectiveIdleMinutes) {
                return 1; // Not idle long enough yet
            }

            LOGGER.log(
                    Level.INFO,
                    AGENT_PREFIX + computer.getName() + " idle for " + idleMinutesElapsed + " min (threshold "
                            + effectiveIdleMinutes + " min); terminating VM " + configuredVmId);
        } else if (maxBuildsExceeded) {
            LOGGER.log(
                    Level.INFO,
                    AGENT_PREFIX + computer.getName() + " reached max build count and is now idle; terminating VM "
                            + configuredVmId);
        } else {
            LOGGER.log(
                    Level.INFO,
                    AGENT_PREFIX + computer.getName() + " reached max lifetime of " + effectiveMaxLifetimeMinutes
                            + " min and is now idle; terminating VM " + configuredVmId);
        }

        try {
            computer.disconnect(null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error disconnecting computer " + computer.getName(), e);
        }

        // Locate the cloud and trigger VM termination when provenance is known.
        if (proxmoxCloud != null) {
            proxmoxCloud.terminateInstance(configuredVmId);

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

    public void taskAccepted(Executor executor, Queue.Task task) {
        if (!(executor.getOwner() instanceof SlaveComputer computer)) {
            return;
        }
        hudson.model.Node node = computer.getNode();
        if (!(node instanceof ProxmoxNode proxmoxNode)) {
            return;
        }
        int configuredMaxBuilds = proxmoxNode.getEffectiveMaxBuildsPerAgent();
        if (configuredMaxBuilds <= 0) {
            return;
        }

        @SuppressWarnings("deprecation")
        int completedBuilds = computer.getBuilds().size();
        int runningBuilds = computer.countBusy();
        // taskAccepted can race with executor busy-state updates; assume at least one active assignment.
        int effectiveRunningBuilds = Math.max(1, runningBuilds);
        int remainingFromHistory = Math.max(0, configuredMaxBuilds - completedBuilds - effectiveRunningBuilds);
        int currentRemaining = proxmoxNode.getBuildsRemaining();
        int remainingAfterAcceptance = currentRemaining > 0 ? currentRemaining - 1 : Integer.MAX_VALUE;
        int remaining = remainingAfterAcceptance == Integer.MAX_VALUE
                ? remainingFromHistory
                : Math.min(Math.max(0, remainingAfterAcceptance), remainingFromHistory);
        proxmoxNode.setBuildsRemaining(remaining);
        if (remaining <= 0) {
            computer.setAcceptingTasks(false);
            if (!computer.isTemporarilyOffline()) {
                String offlineReason = MAX_BUILDS_DRAIN_REASON_PREFIX + configuredMaxBuilds
                        + " builds; draining running jobs before termination";
                computer.setTemporarilyOffline(true, new OfflineCause.ByCLI(offlineReason));
            }
            LOGGER.log(
                    Level.INFO,
                    AGENT_PREFIX + computer.getName() + " accepted its final allowed build (max=" + configuredMaxBuilds
                            + "); disabled for further scheduling");
        } else {
            LOGGER.log(Level.FINE, AGENT_PREFIX + computer.getName() + " has " + remaining + " builds remaining");
        }
    }

    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        maybeTriggerDrainTermination(executor);
    }

    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        maybeTriggerDrainTermination(executor);
    }

    private void maybeTriggerDrainTermination(Executor executor) {
        if (!(executor.getOwner() instanceof SlaveComputer computer)) {
            return;
        }
        if (computer.countBusy() == 0 && !computer.isAcceptingTasks()) {
            check(computer);
        }
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

    public int getMaxBuildsPerAgent() {
        return maxBuildsPerAgent;
    }

    @Extension
    public static class BuildEventBridge implements ExecutorListener {
        @Override
        public void taskAccepted(Executor executor, Queue.Task task) {
            withStrategy(executor, strategy -> strategy.taskAccepted(executor, task));
        }

        @Override
        public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
            withStrategy(executor, strategy -> strategy.taskCompleted(executor, task, durationMS));
        }

        @Override
        public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
            withStrategy(
                    executor, strategy -> strategy.taskCompletedWithProblems(executor, task, durationMS, problems));
        }

        private static void withStrategy(
                Executor executor, java.util.function.Consumer<ProxmoxRetentionStrategy> callback) {
            if (!(executor.getOwner() instanceof SlaveComputer computer)) {
                return;
            }
            Slave node = computer.getNode();
            if (node == null) {
                return;
            }
            if (!(node.getRetentionStrategy() instanceof ProxmoxRetentionStrategy strategy)) {
                return;
            }
            callback.accept(strategy);
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Proxmox VM Retention Strategy";
        }
    }
}
