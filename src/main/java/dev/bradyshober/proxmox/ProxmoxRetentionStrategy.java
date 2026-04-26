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
    private static final String NULL_TASK_DISPLAY = "<null>";
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private static final class CheckContext {
        private final Jenkins jenkinsInstance;
        private final ProxmoxCloud proxmoxCloud;
        private final String configuredCloudName;
        private final String configuredVmId;
        private final int effectiveIdleMinutes;
        private final int effectiveMaxLifetimeMinutes;
        private final int effectiveMaxBuilds;
        private final boolean maxLifetimeExceeded;

        private CheckContext(
                Jenkins jenkinsInstance,
                ProxmoxCloud proxmoxCloud,
                String configuredCloudName,
                String configuredVmId,
                int effectiveIdleMinutes,
                int effectiveMaxLifetimeMinutes,
                int effectiveMaxBuilds,
                boolean maxLifetimeExceeded) {
            this.jenkinsInstance = jenkinsInstance;
            this.proxmoxCloud = proxmoxCloud;
            this.configuredCloudName = configuredCloudName;
            this.configuredVmId = configuredVmId;
            this.effectiveIdleMinutes = effectiveIdleMinutes;
            this.effectiveMaxLifetimeMinutes = effectiveMaxLifetimeMinutes;
            this.effectiveMaxBuilds = effectiveMaxBuilds;
            this.maxLifetimeExceeded = maxLifetimeExceeded;
        }
    }

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
        initializeCreationTimeIfNeeded();
        CheckContext context = resolveCheckContext();

        markOfflineForMaxLifetimeIfNeeded(computer, context);
        boolean maxBuildsExceeded = updateBuildCountersAndEvaluateMaxBuilds(computer, context.effectiveMaxBuilds);
        markOfflineForMaxBuildsIfNeeded(computer, maxBuildsExceeded, context.effectiveMaxBuilds);

        if (!computer.isIdle()) {
            return 1; // Busy - check again in 1 minute
        }

        if (shouldSkipTermination(computer, context)) {
            return 1;
        }

        if (!context.maxLifetimeExceeded
                && !maxBuildsExceeded
                && !isIdleThresholdReached(computer, context.effectiveIdleMinutes)) {
            return 1; // Not idle long enough yet
        }

        logTerminationReason(computer, context, maxBuildsExceeded);
        disconnectComputer(computer);
        terminateVmAndRemoveNode(computer, context);
        return 0; // No more checks needed - node is being removed
    }

    private void initializeCreationTimeIfNeeded() {
        if (createdAtMillis <= 0) {
            createdAtMillis = System.currentTimeMillis();
        }
    }

    private CheckContext resolveCheckContext() {
        String configuredCloudName = cloudName;
        String configuredVmId = vmId;
        int effectiveIdleMinutes = idleMinutes;
        int effectiveMaxLifetimeMinutes = maxLifetimeMinutes;
        int effectiveMaxBuilds = maxBuildsPerAgent;

        Jenkins jenkinsInstance = Jenkins.getInstanceOrNull();
        ProxmoxCloud proxmoxCloud = resolveProxmoxCloud(jenkinsInstance, configuredCloudName);
        if (proxmoxCloud != null) {
            // Cloud-level policy is authoritative for lifecycle thresholds.
            effectiveIdleMinutes = proxmoxCloud.getIdleMinutesBeforeTermination();
            effectiveMaxLifetimeMinutes = proxmoxCloud.getMaxLifetimeMinutes();
            effectiveMaxBuilds = proxmoxCloud.getMaxBuildsPerAgent();
        }

        boolean maxLifetimeExceeded = isMaxLifetimeExceeded(effectiveMaxLifetimeMinutes);
        return new CheckContext(
                jenkinsInstance,
                proxmoxCloud,
                configuredCloudName,
                configuredVmId,
                effectiveIdleMinutes,
                effectiveMaxLifetimeMinutes,
                effectiveMaxBuilds,
                maxLifetimeExceeded);
    }

    private ProxmoxCloud resolveProxmoxCloud(Jenkins jenkinsInstance, String configuredCloudName) {
        if (jenkinsInstance == null || configuredCloudName == null || configuredCloudName.isBlank()) {
            return null;
        }

        hudson.slaves.Cloud cloud = jenkinsInstance.clouds.getByName(configuredCloudName);
        if (cloud instanceof ProxmoxCloud resolvedCloud) {
            return resolvedCloud;
        }
        return null;
    }

    private boolean isMaxLifetimeExceeded(int effectiveMaxLifetimeMinutes) {
        return effectiveMaxLifetimeMinutes > 0
                && (System.currentTimeMillis() - createdAtMillis) >= (effectiveMaxLifetimeMinutes * MILLIS_PER_MINUTE);
    }

    private void markOfflineForMaxLifetimeIfNeeded(SlaveComputer computer, CheckContext context) {
        if (!context.maxLifetimeExceeded || computer.isTemporarilyOffline()) {
            return;
        }

        String offlineReason = MAX_LIFETIME_DRAIN_REASON_PREFIX + context.effectiveMaxLifetimeMinutes
                + " minutes; draining running jobs before termination";
        computer.setTemporarilyOffline(true, new OfflineCause.ByCLI(offlineReason));
        LOGGER.log(
                Level.INFO,
                "{0}{1} exceeded max lifetime of {2} min; marked temporarily offline for drain",
                new Object[] {AGENT_PREFIX, computer.getName(), context.effectiveMaxLifetimeMinutes});
    }

    private boolean updateBuildCountersAndEvaluateMaxBuilds(SlaveComputer computer, int effectiveMaxBuilds) {
        if (effectiveMaxBuilds <= 0) {
            return false;
        }

        @SuppressWarnings("deprecation")
        int completedBuilds = computer.getBuilds().size();
        int runningBuilds = computer.countBusy();
        int remaining = Math.max(0, effectiveMaxBuilds - completedBuilds - runningBuilds);
        boolean maxBuildsExceeded = (completedBuilds + runningBuilds) >= effectiveMaxBuilds;

        hudson.model.Node node = computer.getNode();
        if (node instanceof ProxmoxNode proxmoxNode) {
            // Keep UI state in sync with real execution history to avoid stale counters.
            proxmoxNode.setBuildsRemaining(remaining);
        }
        return maxBuildsExceeded;
    }

    private void markOfflineForMaxBuildsIfNeeded(
            SlaveComputer computer, boolean maxBuildsExceeded, int effectiveMaxBuilds) {
        // Mark offline immediately if max builds is exceeded, before checking idle.
        if (!maxBuildsExceeded || computer.isTemporarilyOffline()) {
            return;
        }

        String offlineReason = MAX_BUILDS_DRAIN_REASON_PREFIX + effectiveMaxBuilds
                + " builds; draining running jobs before termination";
        computer.setTemporarilyOffline(true, new OfflineCause.ByCLI(offlineReason));
        LOGGER.log(
                Level.INFO,
                "{0}{1} reached max build count of {2}; marked temporarily offline for drain",
                new Object[] {AGENT_PREFIX, computer.getName(), effectiveMaxBuilds});
    }

    private boolean shouldSkipTermination(SlaveComputer computer, CheckContext context) {
        if (context.jenkinsInstance == null) {
            return false;
        }

        if (context.configuredCloudName == null
                || context.configuredCloudName.isBlank()
                || context.configuredVmId == null
                || context.configuredVmId.isBlank()) {
            LOGGER.log(
                    Level.WARNING, "Skipping VM termination because cloudName/vmId are not set on retention strategy");
            return true;
        }

        if (context.proxmoxCloud == null) {
            LOGGER.log(
                    Level.WARNING, "Could not find ProxmoxCloud ''{0}'' for VM cleanup", context.configuredCloudName);
            return true;
        }

        // Minimum floor takes precedence: keep idle agents alive until replacement capacity exists.
        if (!context.proxmoxCloud.canTerminateVmForScaleDown(context.configuredVmId)) {
            LOGGER.log(
                    Level.FINE,
                    "Skipping idle termination for {0} because cloud minimum instance floor is reached",
                    computer.getName());
            return true;
        }

        return false;
    }

    private boolean isIdleThresholdReached(SlaveComputer computer, int effectiveIdleMinutes) {
        long idleMillis = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
        long idleMinutesElapsed = idleMillis / MILLIS_PER_MINUTE;
        return idleMinutesElapsed >= effectiveIdleMinutes;
    }

    private void logTerminationReason(SlaveComputer computer, CheckContext context, boolean maxBuildsExceeded) {
        if (maxBuildsExceeded) {
            LOGGER.log(Level.INFO, "{0}{1} reached max build count and is now idle; terminating VM {2}", new Object[] {
                AGENT_PREFIX, computer.getName(), context.configuredVmId
            });
            return;
        }

        if (context.maxLifetimeExceeded) {
            LOGGER.log(
                    Level.INFO,
                    "{0}{1} reached max lifetime of {2} min and is now idle; terminating VM {3}",
                    new Object[] {
                        AGENT_PREFIX, computer.getName(), context.effectiveMaxLifetimeMinutes, context.configuredVmId
                    });
            return;
        }

        long idleMillis = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
        long idleMinutesElapsed = idleMillis / MILLIS_PER_MINUTE;
        LOGGER.log(Level.INFO, "{0}{1} idle for {2} min (threshold {3} min); terminating VM {4}", new Object[] {
            AGENT_PREFIX, computer.getName(), idleMinutesElapsed, context.effectiveIdleMinutes, context.configuredVmId
        });
    }

    private void disconnectComputer(SlaveComputer computer) {
        try {
            computer.disconnect(null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "Error disconnecting computer " + computer.getName());
        }
    }

    private void terminateVmAndRemoveNode(SlaveComputer computer, CheckContext context) {
        // Locate the cloud and trigger VM termination when provenance is known.
        if (context.proxmoxCloud == null) {
            return;
        }

        context.proxmoxCloud.terminateInstance(context.configuredVmId);

        // Remove the orphaned node from Jenkins.
        try {
            hudson.model.Node node = computer.getNode();
            if (node != null && context.jenkinsInstance != null) {
                context.jenkinsInstance.removeNode(node);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not remove node " + computer.getName() + " from Jenkins");
        }
    }

    @Override
    public void start(SlaveComputer computer) {
        computer.connect(false);
    }

    public void taskAccepted(Executor executor) {
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
                    "{0}{1} accepted its final allowed build (max={2}); disabled for further scheduling",
                    new Object[] {AGENT_PREFIX, computer.getName(), configuredMaxBuilds});
        } else {
            LOGGER.log(Level.FINE, "{0}{1} has {2} builds remaining", new Object[] {
                AGENT_PREFIX, computer.getName(), remaining
            });
        }
    }

    // Backward-compatible overload used by tests and any direct callers.
    public void taskAccepted(Executor executor, Queue.Task task) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(
                    Level.FINEST,
                    "taskAccepted bridge invoked for task {0}",
                    task != null ? task.getDisplayName() : NULL_TASK_DISPLAY);
        }
        taskAccepted(executor);
    }

    public void taskCompleted(Executor executor) {
        maybeTriggerDrainTermination(executor);
    }

    // Backward-compatible overload used by tests and any direct callers.
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "taskCompleted bridge invoked for task {0} (durationMs={1})", new Object[] {
                task != null ? task.getDisplayName() : NULL_TASK_DISPLAY, durationMS
            });
        }
        taskCompleted(executor);
    }

    public void taskCompletedWithProblems(Executor executor) {
        maybeTriggerDrainTermination(executor);
    }

    // Backward-compatible overload used by tests and any direct callers.
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(
                    Level.FINEST,
                    "taskCompletedWithProblems bridge invoked for task {0} (durationMs={1}, hasProblems={2})",
                    new Object[] {task != null ? task.getDisplayName() : NULL_TASK_DISPLAY, durationMS, problems != null
                    });
        }
        taskCompletedWithProblems(executor);
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
            withStrategy(executor, strategy -> strategy.taskAccepted(executor));
        }

        @Override
        public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
            withStrategy(executor, strategy -> strategy.taskCompleted(executor));
        }

        @Override
        public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
            withStrategy(executor, strategy -> strategy.taskCompletedWithProblems(executor));
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
