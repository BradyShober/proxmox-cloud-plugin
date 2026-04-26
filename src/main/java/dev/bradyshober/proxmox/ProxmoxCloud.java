package dev.bradyshober.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHConnector;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerConnector;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpSlaveAgentProtocol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Proxmox Cloud provider for dynamic agent provisioning.
 * Listens to the Jenkins build queue and provisions VMs from a template when builds are pending.
 */
public class ProxmoxCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloud.class.getName());
    private static final String PROVISIONED_BY_PLUGIN_TAG = "jenkins-proxmox-plugin";
    private static final String CLOUD_TAG_PREFIX = "jenkins-cloud-";
    private static final String SYSTEMCTL_CMD = "systemctl";
    private static final String DEFAULT_JENKINS_URL = "http://jenkins:8080/";
    private static final ConcurrentMap<String, VmAllocationState> VM_ALLOCATION_STATES = new ConcurrentHashMap<>();

    @Serial
    private static final long serialVersionUID = 1L;

    private final ProxmoxServerConfig serverConfig;
    private final ProxmoxAgentTemplate agentTemplate;
    private final List<ProxmoxInstance> instances;
    private transient ProxmoxClient proxmoxClient;
    /** Tracks async provisions in-flight so the reconciler doesn't over-provision. */
    private transient AtomicInteger pendingProvisions;
    /** Ensures we reconcile tagged VMs from Proxmox before new provisioning proceeds. */
    private transient volatile boolean startupReconciled;

    private static final class VmAllocationState {
        private final Set<String> reservedVmIds = new HashSet<>();
    }

    static final class CloneReservation {
        private final String vmId;
        private final String upidClone;

        CloneReservation(String vmId, String upidClone) {
            this.vmId = vmId;
            this.upidClone = upidClone;
        }

        String getVmId() {
            return vmId;
        }

        String getUpidClone() {
            return upidClone;
        }
    }

    @FunctionalInterface
    interface CloneStarter {
        String startClone(String vmId) throws Exception;
    }

    @DataBoundConstructor
    public ProxmoxCloud(
            String name,
            String host,
            String apiTokenCredentialId,
            boolean skipTlsVerification,
            String node,
            String templateVmId,
            String agentNameTemplate,
            int minInstances,
            int maxInstances,
            int idleMinutesBeforeTermination,
            ComputerConnector computerConnector,
            String sshUsername,
            String sshPublicKey,
            String labels,
            String remoteFsRoot,
            int numExecutors) {
        super(name);
        this.serverConfig = new ProxmoxServerConfig(
                host, apiTokenCredentialId, !skipTlsVerification, node == null || node.isBlank() ? "pve" : node);

        ComputerConnector configuredConnector = computerConnector != null ? computerConnector : defaultConnector();

        this.agentTemplate = new ProxmoxAgentTemplate(
                templateVmId,
                agentNameTemplate == null || agentNameTemplate.isBlank() ? "proxmox-agent" : agentNameTemplate,
                Math.max(0, minInstances),
                maxInstances > 0 ? maxInstances : 1,
                configuredConnector,
                sshUsername == null || sshUsername.isBlank() ? "jenkins" : sshUsername,
                sshPublicKey,
                labels == null || labels.isBlank() ? "proxmox" : labels,
                remoteFsRoot == null || remoteFsRoot.isBlank() ? "/home/jenkins" : remoteFsRoot,
                idleMinutesBeforeTermination > 0
                        ? idleMinutesBeforeTermination
                        : ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES);
        agentTemplate.setNumExecutors(Math.max(1, numExecutors));
        this.instances = Collections.synchronizedList(new ArrayList<>());
        initTransientState();
    }

    private static ComputerConnector defaultConnector() {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);
        return connector;
    }

    private void initTransientState() {
        getPendingProvisions();
    }

    @Serial
    private Object readResolve() {
        proxmoxClient = null;
        startupReconciled = false;
        initTransientState();
        return this;
    }

    /** Lazily initialise the transient pending-provisions counter (survives XStream round-trip). */
    private synchronized AtomicInteger getPendingProvisions() {
        if (pendingProvisions == null) {
            pendingProvisions = new AtomicInteger(0);
        }
        return pendingProvisions;
    }

    private String getVmAllocationScopeKey() {
        String host = serverConfig.getHost() == null
                ? ""
                : serverConfig.getHost().trim().toLowerCase(Locale.ROOT);
        String node = serverConfig.getNode() == null
                ? ""
                : serverConfig.getNode().trim().toLowerCase(Locale.ROOT);
        return host + "|" + node;
    }

    private VmAllocationState getVmAllocationState() {
        return VM_ALLOCATION_STATES.computeIfAbsent(getVmAllocationScopeKey(), unused -> new VmAllocationState());
    }

    CloneReservation reserveVmIdAndStartClone(
            String agentName, java.util.concurrent.Callable<String> nextVmIdSupplier, CloneStarter cloneStarter)
            throws Exception {
        VmAllocationState allocationState = getVmAllocationState();
        String allocationScope = getVmAllocationScopeKey();

        synchronized (allocationState) {
            String vmId = nextVmIdSupplier.call();
            if (vmId == null || vmId.isBlank()) {
                throw new IOException("Proxmox returned a blank VM ID for cloud '" + name + "'");
            }

            // Wait in a while loop so the condition is re-checked after every wake,
            // guarding against spurious wakeups (fixes java:S2274).
            // Each iteration fetches a fresh VM ID — Proxmox may offer a different one.
            int attempt = 0;
            while (allocationState.reservedVmIds.contains(vmId) && attempt < 25) {
                attempt++;
                LOGGER.log(
                        Level.FINE,
                        "VM ID {0} is already reserved for Proxmox allocation scope {1}; waiting to retry (attempt {2})",
                        new Object[] {vmId, allocationScope, attempt});
                allocationState.wait(200L);
                vmId = nextVmIdSupplier.call();
                if (vmId == null || vmId.isBlank()) {
                    throw new IOException("Proxmox returned a blank VM ID for cloud '" + name + "'");
                }
            }

            if (allocationState.reservedVmIds.contains(vmId)) {
                throw new IOException(
                        "Timed out waiting for a unique Proxmox VM ID for allocation scope '" + allocationScope + "'");
            }

            allocationState.reservedVmIds.add(vmId);
            try {
                LOGGER.log(Level.FINE, "Allocated VM ID from Proxmox for {0}: {1}", new Object[] {agentName, vmId});
                String upidClone = cloneStarter.startClone(vmId);
                LOGGER.log(Level.FINE, "Clone task started for VM {0}: {1}", new Object[] {vmId, upidClone});
                return new CloneReservation(vmId, upidClone);
            } catch (Exception e) {
                allocationState.reservedVmIds.remove(vmId);
                allocationState.notifyAll();
                throw e;
            }
        }
    }

    private CloneReservation reserveVmIdAndStartClone(String agentName) throws Exception {
        String templateVmId = agentTemplate.getTemplateVmId();
        return reserveVmIdAndStartClone(
                agentName,
                () -> proxmoxClient.getNextVmId(),
                vmId -> proxmoxClient.cloneVmWithCloudInit(templateVmId, vmId, agentName, null));
    }

    private void releaseReservedVmId(String vmId) {
        if (vmId == null || vmId.isBlank()) {
            return;
        }

        VmAllocationState allocationState = getVmAllocationState();
        synchronized (allocationState) {
            if (allocationState.reservedVmIds.remove(vmId)) {
                allocationState.notifyAll();
            }
        }
    }

    /**
     * Count the number of Jenkins nodes that are owned by this cloud instance.
     * Uses the live Jenkins node list rather than the (possibly stale) {@link #instances} list.
     */
    int countLiveCloudNodes() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return 0;
        return (int) jenkins.getNodes().stream()
                .filter(node -> node instanceof hudson.model.Slave)
                .map(node -> (hudson.model.Slave) node)
                .filter(node -> node.getRetentionStrategy() instanceof ProxmoxRetentionStrategy)
                .filter(node -> {
                    ProxmoxRetentionStrategy strategy = (ProxmoxRetentionStrategy) node.getRetentionStrategy();
                    return name.equals(strategy.getCloudName()) && !isDraining(node);
                })
                .count();
    }

    private boolean isDrainingForMaxLifetime(hudson.model.Slave node) {
        hudson.model.Computer computer = node.toComputer();
        if (!(computer instanceof hudson.slaves.SlaveComputer slaveComputer) || !slaveComputer.isTemporarilyOffline()) {
            return false;
        }
        String reason = slaveComputer.getOfflineCauseReason();
        return reason != null && reason.startsWith(ProxmoxRetentionStrategy.MAX_LIFETIME_DRAIN_REASON_PREFIX);
    }

    private boolean isDrainingForMaxBuilds(hudson.model.Slave node) {
        hudson.model.Computer computer = node.toComputer();
        if (!(computer instanceof hudson.slaves.SlaveComputer slaveComputer) || !slaveComputer.isTemporarilyOffline()) {
            return false;
        }
        String reason = slaveComputer.getOfflineCauseReason();
        return reason != null && reason.startsWith(ProxmoxRetentionStrategy.MAX_BUILDS_DRAIN_REASON_PREFIX);
    }

    /** Returns true if the node is being drained for any reason (max lifetime or max builds). */
    private boolean isDraining(hudson.model.Slave node) {
        return isDrainingForMaxLifetime(node) || isDrainingForMaxBuilds(node);
    }

    /**
     * Check if this cloud can provision agents for the given label.
     * Returns true if the label is in the cloud's configured labels.
     */
    @Override
    public boolean canProvision(Label label) {
        if (label == null) {
            return true; // Can provision for any label if none specified
        }

        String labelName = label.getName();
        String configuredLabels = agentTemplate.getLabels();

        // Split configured labels by whitespace and check if label matches any
        String[] labels = configuredLabels.split("\\s+");
        for (String cloudLabel : labels) {
            if (!cloudLabel.isBlank() && cloudLabel.equals(labelName)) {
                return true;
            }
        }

        LOGGER.log(
                Level.FINE, "Label ''{0}'' not in configured labels: {1}", new Object[] {labelName, configuredLabels});
        return false;
    }

    /**
     * Called by Jenkins to determine how many new agents can be provisioned.
     * Returns provisioning suggestions based on pending queue items and instance capacity.
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.log(Level.FINE, "Provisioning requested for label: {0}, excessWorkload: {1}", new Object[] {
            label, excessWorkload
        });

        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        try {
            ensureStartupReconciled();
            if (proxmoxClient == null) {
                initializeClient();
            }

            // Check current instance count against max.
            // Use the live Jenkins node count (more accurate than the in-memory list after restarts)
            // plus any in-flight provisions from the reconciler to avoid over-provisioning.
            int currentCount = Math.max(
                    instances.size(),
                    countLiveCloudNodes() + getPendingProvisions().get());
            int maxInstances = agentTemplate.getMaxInstances();
            int availableCapacity = maxInstances - currentCount;

            if (availableCapacity <= 0) {
                LOGGER.log(Level.INFO, "Instance capacity reached: {0}/{1}", new Object[] {currentCount, maxInstances});
                return plannedNodes;
            }

            int queueDemand = Math.max(0, excessWorkload);
            int minFloorDemand = Math.max(0, agentTemplate.getMinInstances() - currentCount);

            // Keep floor capacity warm while also satisfying queue demand.
            int instancesToProvision = Math.min(Math.max(queueDemand, minFloorDemand), availableCapacity);
            if (instancesToProvision <= 0) {
                return plannedNodes;
            }

            for (int i = 0; i < instancesToProvision; i++) {
                // Generate unique agent name
                String agentName = generateAgentName();

                LOGGER.log(Level.INFO, "Planning Proxmox agent provisioning for {0}", agentName);

                // Create cloud-init script for agent bootstrap
                String cloudInitScript = generateCloudInitScript(agentName);

                // Provision the VM asynchronously
                NodeProvisioner.PlannedNode plannedNode = new NodeProvisioner.PlannedNode(
                        agentName,
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                return provisionAgent(agentName, cloudInitScript);
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, e, () -> "Failed to provision agent: " + agentName);
                                return null;
                            }
                        }),
                        agentTemplate.getNumExecutors());

                plannedNodes.add(plannedNode);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during provisioning", e);
        }

        return plannedNodes;
    }

    /**
     * Provision a single agent on Proxmox.
     *
     * <p>For <b>WebSocket / JNLP inbound</b> mode the node is pre-registered in Jenkins so the JNLP
     * MAC secret can be computed, then — after the VM boots — the secret is written as a systemd
     * service file via the QEMU guest agent. The VM then connects back to Jenkins automatically.
     *
     * <p>For <b>SSH/other outbound</b> mode the VM is started first, its IP is resolved via the QEMU
     * guest agent, then the node is created with a launcher obtained from the configured connector.
     */
    private Node provisionAgent(String agentName, String cloudInitScript) throws Exception {
        LOGGER.log(Level.INFO, "Starting provisioning of agent: {0}", agentName);

        ComputerConnector configuredConnector = agentTemplate.getComputerConnector();
        boolean inboundConnector = isInboundConnector(configuredConnector);

        // Compute the JNLP secret up front – it only depends on agentName, not vmId.
        // The secret is injected post-boot via the QEMU guest agent (not cloud-init).
        String jnlpSecret = null;
        if (inboundConnector) {
            jnlpSecret = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(agentName);
            LOGGER.log(Level.FINE, "Computed inbound agent secret for {0}", agentName);
        }

        // -----------------------------------------------------------------------
        // Allocate a VM ID and immediately submit the clone task while holding the
        // vmAllocationLock.  This prevents concurrent provisioning threads from
        // calling getNextVmId() before any clone has been registered in Proxmox,
        // which would cause every thread to receive the same ID.
        // -----------------------------------------------------------------------
        String vmId = null;
        boolean vmIdReserved = false;
        CloneReservation cloneReservation = reserveVmIdAndStartClone(agentName);
        vmId = cloneReservation.getVmId();
        String upidClone = cloneReservation.getUpidClone();
        vmIdReserved = true;

        try {
            // Pre-register the Jenkins node now that the real vmId is known.
            // This must happen before the VM boots so Jenkins can accept the inbound connection.
            ProxmoxNode preRegisteredNode = null;
            if (inboundConnector) {
                preRegisteredNode = buildDumbSlave(agentName, vmId, null);
                Jenkins.get().addNode(preRegisteredNode);
                LOGGER.log(Level.INFO, "Pre-registered Jenkins node for inbound agent: {0}", agentName);
            }

            // Track this instance
            ProxmoxInstance instance = new ProxmoxInstance(vmId, agentName);
            instance.setUpidTaskId(upidClone);
            instances.add(instance);

            // Wait for clone task to complete
            waitForTaskCompletion("clone", vmId, upidClone);
            releaseReservedVmId(vmId);
            vmIdReserved = false;
            instance.setState(ProxmoxInstance.InstanceState.STARTING);

            // Configure cloud-init on the cloned VM: create the agent user, inject the SSH
            // public key into authorized_keys, and request DHCP on the primary NIC.
            // Requires the template to have a cloud-init drive attached.
            try {
                proxmoxClient.configureVmCloudInit(
                        vmId, agentTemplate.getSshUsername(), agentTemplate.getSshPublicKey(), buildProvisioningTags());
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "Could not configure cloud-init params for VM {0}; continuing anyway. Error: {1}",
                        new Object[] {vmId, e.getMessage()});
            }

            // Start the VM
            String upidStart = proxmoxClient.startVm(vmId);
            LOGGER.log(Level.FINE, "Start task initiated for VM {0}: {1}", new Object[] {vmId, upidStart});

            // Wait for VM to start
            waitForTaskCompletion("start", vmId, upidStart);
            instance.setState(ProxmoxInstance.InstanceState.RUNNING);
            LOGGER.log(Level.INFO, "VM provisioned and started: {0}", agentName);

            // -----------------------------------------------------------------------
            // WebSocket / JNLP inbound: write the agent service file with the JNLP
            // secret via QEMU guest agent, then start the service.
            // Requires qemu-guest-agent and Java to be pre-installed in the template.
            // -----------------------------------------------------------------------
            if (inboundConnector && jnlpSecret != null) {
                String serviceContent = buildJenkinsAgentServiceContent(agentName, jnlpSecret);
                LOGGER.log(Level.FINE, "Waiting for QEMU guest agent on VM {0}", vmId);
                proxmoxClient.waitForGuestAgent(vmId);

                // Download agent.jar from Jenkins before writing the service file.
                downloadAgentJar(proxmoxClient, vmId);

                proxmoxClient.writeFileViaGuestAgent(vmId, "/etc/systemd/system/jenkins-agent.service", serviceContent);
                LOGGER.log(Level.FINE, "Wrote jenkins-agent.service to VM {0} via guest agent", vmId);

                // Validate unit syntax early so provisioning logs include the parse error.
                proxmoxClient.execCommandViaGuestAgent(
                        vmId, "systemd-analyze", "verify", "/etc/systemd/system/jenkins-agent.service");
                proxmoxClient.execCommandViaGuestAgent(vmId, SYSTEMCTL_CMD, "daemon-reload");
                proxmoxClient.execCommandViaGuestAgent(vmId, SYSTEMCTL_CMD, "enable", "--now", "jenkins-agent.service");
                proxmoxClient.execCommandViaGuestAgent(vmId, SYSTEMCTL_CMD, "is-active", "jenkins-agent.service");
                LOGGER.log(Level.INFO, "Started jenkins-agent.service on VM {0}; waiting for inbound connection", vmId);
                return preRegisteredNode;
            }

            // -----------------------------------------------------------------------
            // SSH/other outbound mode: resolve IP via QEMU guest agent, then create the
            // launcher using the connector, and return the node.
            // -----------------------------------------------------------------------
            LOGGER.log(Level.FINE, "Resolving IP address for VM {0} via QEMU guest agent", vmId);
            String ipAddress = null;
            try {
                ipAddress = proxmoxClient.getVmIpAddress(vmId);
                instance.setIpAddress(ipAddress);
                LOGGER.log(Level.INFO, "Resolved VM {0} IP address: {1}", new Object[] {vmId, ipAddress});
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "Could not resolve VM IP address via guest agent; the connector may not be able to connect. Error: {0}",
                        e.getMessage());
            }

            return buildDumbSlave(agentName, vmId, ipAddress);
        } catch (Exception e) {
            if (vmIdReserved) {
                releaseReservedVmId(vmId);
            }
            throw e;
        }
    }

    /**
     * Wait for a Proxmox task to complete with polling.
     */
    private void waitForTaskCompletion(String phase, String vmId, String upid) throws Exception {
        int maxAttempts = 120; // 10 minutes with 5-second intervals
        int attempt = 0;

        LOGGER.log(Level.FINE, "Waiting for Proxmox {0} task on VM {1}: {2}", new Object[] {phase, vmId, upid});

        while (attempt < maxAttempts) {
            LOGGER.log(Level.FINE, "Polling Proxmox {0} task for VM {1} (attempt {2}/{3}): {4}", new Object[] {
                phase, vmId, attempt + 1, maxAttempts, upid
            });
            if (proxmoxClient.isTaskComplete(upid)) {
                LOGGER.log(Level.FINE, "Proxmox {0} task completed for VM {1}: {2}", new Object[] {phase, vmId, upid});
                return;
            }
            Thread.sleep(5000); // Wait 5 seconds before checking again
            attempt++;
        }

        throw new Exception("Task timeout during " + phase + " for VM " + vmId + ": " + upid);
    }

    private void waitForTaskCompletion(String upid) throws Exception {
        waitForTaskCompletion("task", "unknown", upid);
    }

    /**
     * Build a Jenkins {@link ProxmoxNode} for the provisioned VM, wired with the
     * appropriate launcher and a {@link ProxmoxRetentionStrategy} that will delete the
     * VM once the agent has been idle for {@link ProxmoxRetentionStrategy#DEFAULT_IDLE_MINUTES}
     * minutes.
     *
     * @param agentName display name for the Jenkins node
     * @param vmId      Proxmox VM ID that backs this node (used by the retention strategy)
     * @param ipAddress resolved IP address for SSH connections; may be {@code null} for
     *                  WebSocket/inbound agents
     */
    private ProxmoxNode buildDumbSlave(String agentName, String vmId, String ipAddress) throws Exception {
        ComputerLauncher launcher = buildNodeLauncher(ipAddress);

        return new ProxmoxNode(
                agentName,
                "Proxmox provisioned agent (VM " + vmId + ")",
                agentTemplate.getRemoteFsRoot(),
                agentTemplate.getNumExecutors(),
                Node.Mode.NORMAL,
                agentTemplate.getLabels(),
                launcher,
                new ProxmoxRetentionStrategy(
                        name,
                        vmId,
                        agentTemplate.getIdleMinutesBeforeTermination(),
                        agentTemplate.getMaxLifetimeMinutes(),
                        agentTemplate.getMaxBuildsPerAgent()),
                agentTemplate.getMaxBuildsPerAgent());
    }

    private ComputerLauncher buildNodeLauncher(String ipAddress) throws IOException, InterruptedException {
        ComputerConnector connector = agentTemplate.getComputerConnector();
        if (connector == null) {
            throw new IOException("No connector configured for launching agents");
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            // For inbound (JNLP) connectors, the host parameter is ignored and may not be null.
            if (isInboundConnector(connector)) {
                return connector.launch("", TaskListener.NULL);
            }
            // Outbound connectors require an address
            throw new IOException("Outbound connector requires a resolved VM IP address");
        }
        // For outbound connectors (SSH, etc.), use the resolved IP
        return connector.launch(ipAddress, TaskListener.NULL);
    }

    private boolean isInboundConnector(ComputerConnector connector) {
        return connector instanceof ProxmoxJNLPConnector;
    }

    String buildProvisioningTags() {
        return PROVISIONED_BY_PLUGIN_TAG + ";" + buildCloudOwnershipTag();
    }

    String buildCloudOwnershipTag() {
        String sanitizedCloudName =
                name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        sanitizedCloudName = sanitizedCloudName.replaceAll("-+", "-").replaceAll("(^-)|(-$)", "");
        if (sanitizedCloudName.isBlank()) {
            sanitizedCloudName = "default";
        }
        return CLOUD_TAG_PREFIX + sanitizedCloudName;
    }

    static boolean hasTag(String tags, String expectedTag) {
        if (expectedTag == null || expectedTag.isBlank() || tags == null || tags.isBlank()) {
            return false;
        }
        String[] parts = tags.split("[;,\\s]+");
        for (String part : parts) {
            if (expectedTag.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    boolean isManagedVmTags(String tags) {
        return hasTag(tags, PROVISIONED_BY_PLUGIN_TAG) && hasTag(tags, buildCloudOwnershipTag());
    }

    private void ensureStartupReconciled() {
        if (startupReconciled) {
            return;
        }
        synchronized (this) {
            if (startupReconciled) {
                return;
            }
            try {
                reconcileExistingTaggedVms();
                startupReconciled = true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed startup VM reconciliation for cloud '" + name + "'", e);
            }
        }
    }

    public synchronized void reconcileExistingTaggedVms() throws Exception {
        if (proxmoxClient == null) {
            initializeClient();
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }

        int discovered = 0;
        int restored = 0;
        for (ProxmoxClient.ProxmoxVmSummary vm : proxmoxClient.listNodeVms()) {
            if (!isReconciliationCandidate(vm)) {
                continue;
            }
            discovered++;
            if (reattachVmNodeIfEligible(jenkins, vm)) {
                restored++;
            }
        }

        if (discovered > 0) {
            LOGGER.log(
                    Level.INFO,
                    "Cloud ''{0}'': reconciled {1}/{2} tagged VM(s) back into Jenkins after startup",
                    new Object[] {name, restored, discovered});
        }
    }

    private boolean isReconciliationCandidate(ProxmoxClient.ProxmoxVmSummary vm) {
        return vm != null && isManagedVmTags(vm.getTags());
    }

    private boolean reattachVmNodeIfEligible(Jenkins jenkins, ProxmoxClient.ProxmoxVmSummary vm) throws Exception {
        String vmId = vm.getVmId();
        if (vmId == null || vmId.isBlank() || findNodeByVmId(jenkins, vmId) != null) {
            return false;
        }

        if (!isRecoverableVmState(vmId, vm.getStatus())) {
            return false;
        }

        String agentName = resolveRecoveredAgentName(vm);
        if (jenkins.getNode(agentName) != null) {
            LOGGER.log(
                    Level.WARNING,
                    "Skipping VM {0} reconciliation because node name already exists: {1}",
                    new Object[] {vmId, agentName});
            return false;
        }

        String ipAddress = resolveReconciledVmIp(vmId);
        if (ipAddress == null && !isInboundConnector(agentTemplate.getComputerConnector())) {
            return false;
        }

        ProxmoxNode recoveredNode = buildDumbSlave(agentName, vmId, ipAddress);
        jenkins.addNode(recoveredNode);
        instances.add(new ProxmoxInstance(vmId, agentName));
        return true;
    }

    private boolean isRecoverableVmState(String vmId, String status) {
        String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if ("running".equals(normalized) || "starting".equals(normalized)) {
            return true;
        }

        LOGGER.log(Level.FINE, "Skipping VM {0} during reconciliation because status is ''{1}''", new Object[] {
            vmId, normalized
        });
        return false;
    }

    private String resolveRecoveredAgentName(ProxmoxClient.ProxmoxVmSummary vm) {
        String vmName = vm.getName();
        if (vmName == null || vmName.isBlank()) {
            return agentTemplate.getAgentNameTemplate() + "-" + vm.getVmId();
        }
        return vmName;
    }

    private String resolveReconciledVmIp(String vmId) {
        if (isInboundConnector(agentTemplate.getComputerConnector())) {
            return null;
        }

        try {
            return proxmoxClient.getVmIpAddress(vmId);
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "Skipping node re-attachment for VM {0} because IP could not be resolved via guest agent",
                    vmId);
            LOGGER.log(Level.FINE, "IP resolution failure details", e);
            return null;
        }
    }

    /**
     * Generate a cloud-init YAML script to bootstrap the Jenkins agent.
     * <p>
     * The script installs Java, creates the {@code jenkins} user, downloads
     * {@code agent.jar} from the Jenkins controller, and writes a systemd service
     * unit that starts the remoting process on every boot.
     * <p>
     * The literal token {@code %SECRET%} is left in the script; callers must replace
     * it with the real JNLP MAC before passing the script to the Proxmox API.
     */
    private String generateCloudInitScript(String agentName) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String jenkinsUrl = jenkins != null ? jenkins.getRootUrl() : DEFAULT_JENKINS_URL;
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            jenkinsUrl = DEFAULT_JENKINS_URL;
        }
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }

        // Construct the remoting command the agent will run.
        // WebSocket mode: java -jar agent.jar -jnlpUrl ... -secret ... -webSocket
        // SSH mode:       the launcher drives connection; the script is informational only.
        String agentCmd = "java -jar /home/jenkins/agent.jar"
                + " -url " + jenkinsUrl
                + " -name " + agentName
                + " -secret %SECRET%"
                + " -webSocket"
                + " -workDir /home/jenkins";

        StringBuilder sb = new StringBuilder();
        sb.append("#cloud-config\n");
        sb.append("package_update: true\n");
        sb.append("package_upgrade: true\n");
        sb.append("packages:\n");
        sb.append("  - openjdk-21-jre-headless\n");
        sb.append("  - openssh-server\n");
        sb.append("  - curl\n");
        sb.append("  - git\n");
        sb.append("runcmd:\n");
        sb.append("  - 'curl -fsSL -o /home/jenkins/agent.jar ")
                .append(jenkinsUrl)
                .append("jnlpJars/agent.jar'\n");
        sb.append("  - 'chown jenkins:jenkins /home/jenkins/agent.jar'\n");
        sb.append("write_files:\n");
        sb.append("  - path: /etc/systemd/system/jenkins-agent.service\n");
        sb.append("    permissions: '0644'\n");
        sb.append("    content: |\n");
        sb.append("      [Unit]\n");
        sb.append("      Description=Jenkins Agent\n");
        sb.append("      After=network-online.target\n");
        sb.append("      Wants=network-online.target\n");
        sb.append("      [Service]\n");
        sb.append("      User=jenkins\n");
        sb.append("      WorkingDirectory=/home/jenkins\n");
        sb.append("      ExecStart=").append(agentCmd).append("\n");
        sb.append("      Restart=on-failure\n");
        sb.append("      RestartSec=10\n");
        sb.append("      [Install]\n");
        sb.append("      WantedBy=multi-user.target\n");
        sb.append("  - path: /var/lib/cloud/scripts/per-boot/start-jenkins-agent.sh\n");
        sb.append("    permissions: '0755'\n");
        sb.append("    content: |\n");
        sb.append("      #!/bin/sh\n");
        sb.append("      systemctl daemon-reload\n");
        sb.append("      systemctl enable --now jenkins-agent.service\n");

        return sb.toString();
    }

    /**
     * Build a systemd unit file for the Jenkins agent with the real JNLP secret embedded.
     * This content is written to {@code /etc/systemd/system/jenkins-agent.service} via the QEMU
     * guest agent after the VM has booted, bypassing the Proxmox snippet upload limitation.
     *
     * <p>The template VM must have Java and {@code /home/jenkins/agent.jar} pre-installed, and
     * {@code qemu-guest-agent} must be running inside the guest.
     */
    /**
     * Download agent.jar from this Jenkins instance onto the VM via the QEMU guest agent.
     * Tries {@code curl} first, then falls back to {@code wget}.
     * The file is placed at {@code /home/jenkins/agent.jar}.
     */
    private void downloadAgentJar(ProxmoxClient client, String vmId) throws Exception {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String jenkinsUrl = jenkins != null ? jenkins.getRootUrl() : null;
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            throw new IOException("Jenkins root URL is not configured; cannot download agent.jar");
        }
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        String agentJarUrl = jenkinsUrl + "jnlpJars/agent.jar";
        String destPath = "/home/jenkins/agent.jar";
        String destDir = "/home/jenkins";

        LOGGER.log(Level.FINE, "Downloading agent.jar from {0} onto VM {1}", new Object[] {agentJarUrl, vmId});

        // Ensure destination path exists before download attempts.
        // client.execCommandViaGuestAgent(vmId, "mkdir", "-p", destDir);

        // Prefer wget because some templates do not include curl.
        boolean downloaded =
                tryDownloadWithRetries(client, vmId, agentJarUrl, destPath, "wget", "-O", destPath, agentJarUrl)
                        || tryDownloadWithRetries(
                                client,
                                vmId,
                                agentJarUrl,
                                destPath,
                                "wget",
                                "--no-check-certificate",
                                "-O",
                                destPath,
                                agentJarUrl)
                        || tryDownloadWithRetries(
                                client,
                                vmId,
                                agentJarUrl,
                                destPath,
                                "curl",
                                "-fsSL",
                                "--insecure",
                                "-o",
                                destPath,
                                agentJarUrl);

        if (!downloaded) {
            throw new IOException("Unable to download agent.jar to VM " + vmId + " after retries using wget/curl. URL="
                    + agentJarUrl);
        }

        // Verify file is non-empty and readable by service user.
        client.execCommandViaGuestAgent(vmId, "test", "-s", destPath);

        // Fix ownership so the jenkins user can read the file.
        client.execCommandViaGuestAgent(vmId, "chown", "jenkins:jenkins", destDir);
        client.execCommandViaGuestAgent(vmId, "chown", "jenkins:jenkins", destPath);
    }

    private boolean tryDownloadWithRetries(
            ProxmoxClient client, String vmId, String url, String destPath, String... commandAndArgs) {
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                client.execCommandViaGuestAgent(vmId, commandAndArgs);
                LOGGER.log(Level.FINE, "agent.jar download succeeded on VM {0} using {1} (attempt {2})", new Object[] {
                    vmId, commandAndArgs[0], attempt
                });
                return true;
            } catch (Exception e) {
                LOGGER.log(
                        Level.FINE,
                        "agent.jar download attempt {0}/{1} failed on VM {2} using {3} to fetch {4} -> {5}: {6}",
                        new Object[] {attempt, maxAttempts, vmId, commandAndArgs[0], url, destPath, e.getMessage()});
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private String buildJenkinsAgentServiceContent(String agentName, String jnlpSecret) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String jenkinsUrl = jenkins != null ? jenkins.getRootUrl() : DEFAULT_JENKINS_URL;
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            jenkinsUrl = DEFAULT_JENKINS_URL;
        }
        if (!jenkinsUrl.endsWith("/")) {
            jenkinsUrl += "/";
        }
        String execStart = "java -jar /home/jenkins/agent.jar"
                + " -url " + jenkinsUrl
                + " -name " + agentName
                + " -secret " + jnlpSecret
                + " -webSocket"
                + " -workDir /home/jenkins";

        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=Jenkins Agent\n");
        sb.append("After=network-online.target\n");
        sb.append("Wants=network-online.target\n");
        sb.append("\n");
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("User=jenkins\n");
        sb.append("WorkingDirectory=/home/jenkins\n");
        sb.append("ExecStart=").append(execStart).append("\n");
        sb.append("Restart=on-failure\n");
        sb.append("RestartSec=10\n");
        sb.append("StandardOutput=journal\n");
        sb.append("StandardError=journal\n");
        sb.append("\n");
        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");

        return sb.toString();
    }

    /**
     * Generate a unique agent name.
     */
    private String generateAgentName() {
        return agentTemplate.getAgentNameTemplate() + "-" + System.nanoTime();
    }

    /**
     * Proactively provision agents to ensure the cloud stays at or above {@code minInstances}.
     * Called by the periodic {@link MinInstancesReconciler} every minute, independent of queue demand.
     */
    public void reconcileMinInstances() {
        ensureStartupReconciled();

        int minInstances = agentTemplate.getMinInstances();
        if (minInstances <= 0) {
            return; // No minimum configured – nothing to do.
        }

        int liveCount = countLiveCloudNodes();
        int inFlight = getPendingProvisions().get();
        int effectiveCount = liveCount + inFlight;

        if (effectiveCount >= minInstances) {
            return; // Already at or above the floor.
        }

        int deficit = Math.min(minInstances - effectiveCount, agentTemplate.getMaxInstances() - effectiveCount);
        if (deficit <= 0) {
            return;
        }

        LOGGER.log(
                Level.INFO,
                "Cloud ''{0}'': live={1}, inFlight={2}, min={3} → provisioning {4} agent(s) to maintain minimum floor",
                new Object[] {name, liveCount, inFlight, minInstances, deficit});

        try {
            if (proxmoxClient == null) {
                initializeClient();
            }
        } catch (Exception e) {
            LOGGER.log(
                    Level.SEVERE,
                    e,
                    () -> "Cannot initialise Proxmox client for minimum-floor reconciliation on cloud '" + name + "'");
            return;
        }

        boolean inbound = isInboundConnector(agentTemplate.getComputerConnector());
        for (int i = 0; i < deficit; i++) {
            String agentName = generateAgentName();
            String cloudInitScript = generateCloudInitScript(agentName);
            getPendingProvisions().incrementAndGet();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    Node node = provisionAgent(agentName, cloudInitScript);
                    // Inbound (JNLP/WebSocket) agents are pre-registered inside provisionAgent();
                    // Outbound agents are returned and must be added here.
                    if (node != null && !inbound) {
                        Jenkins.get().addNode(node);
                    }
                } catch (Exception e) {
                    LOGGER.log(
                            Level.SEVERE,
                            e,
                            () -> "Failed to provision minimum-floor agent '" + agentName + "' for cloud '" + name
                                    + "'");
                } finally {
                    getPendingProvisions().decrementAndGet();
                }
            });
        }
    }

    /**
     * Stop and delete a Proxmox VM that was backing a Jenkins agent.
     * This method is called by {@link ProxmoxRetentionStrategy} when the agent
     * becomes idle beyond the configured threshold.
     *
     * @param vmId Proxmox VM ID to terminate
     */
    public void terminateInstance(String vmId) {
        LOGGER.log(Level.INFO, "Terminating Proxmox VM: {0}", vmId);
        try {
            if (proxmoxClient == null) {
                initializeClient();
            }
            // Attempt a graceful stop first
            try {
                String upid = proxmoxClient.stopVm(vmId);
                waitForTaskCompletion(upid);
                LOGGER.log(Level.INFO, "VM {0} stopped", vmId);
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING, e, () -> "Could not gracefully stop VM " + vmId + "; attempting delete anyway");
            }
            // Delete the VM
            proxmoxClient.deleteVm(vmId);
            instances.removeIf(i -> vmId.equals(i.getVmId()));
            LOGGER.log(Level.INFO, "VM {0} deleted and removed from instance list", vmId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> "Error terminating VM " + vmId);
        }
    }

    /**
     * Initialize the Proxmox API client.
     */
    private synchronized void initializeClient() throws Exception {
        if (proxmoxClient == null) {
            proxmoxClient = new ProxmoxClient(serverConfig);
        }
    }

    // Getters for configuration
    public ProxmoxServerConfig getServerConfig() {
        return serverConfig;
    }

    public String getHost() {
        return serverConfig.getHost();
    }

    public String getNode() {
        return serverConfig.getNode();
    }

    public String getApiTokenCredentialId() {
        return serverConfig.getApiTokenCredentialId();
    }

    public boolean isVerifySsl() {
        return serverConfig.isVerifySsl();
    }

    public boolean isSkipTlsVerification() {
        return !serverConfig.isVerifySsl();
    }

    public ProxmoxAgentTemplate getAgentTemplate() {
        return agentTemplate;
    }

    public String getTemplateVmId() {
        return agentTemplate.getTemplateVmId();
    }

    public String getAgentNameTemplate() {
        return agentTemplate.getAgentNameTemplate();
    }

    public int getMaxInstances() {
        return agentTemplate.getMaxInstances();
    }

    public int getMinInstances() {
        return agentTemplate.getMinInstances();
    }

    public ComputerConnector getComputerConnector() {
        return agentTemplate.getComputerConnector();
    }

    public String getSshUsername() {
        return agentTemplate.getSshUsername();
    }

    public String getSshPublicKey() {
        return agentTemplate.getSshPublicKey();
    }

    public String getLabels() {
        return agentTemplate.getLabels();
    }

    public String getRemoteFsRoot() {
        return agentTemplate.getRemoteFsRoot();
    }

    public int getIdleMinutesBeforeTermination() {
        return agentTemplate.getIdleMinutesBeforeTermination();
    }

    public int getMaxLifetimeMinutes() {
        return agentTemplate.getMaxLifetimeMinutes();
    }

    @DataBoundSetter
    public void setMaxLifetimeMinutes(int maxLifetimeMinutes) {
        agentTemplate.setMaxLifetimeMinutes(maxLifetimeMinutes);
    }

    public int getMaxBuildsPerAgent() {
        return agentTemplate.getMaxBuildsPerAgent();
    }

    @DataBoundSetter
    public void setMaxBuildsPerAgent(int maxBuildsPerAgent) {
        agentTemplate.setMaxBuildsPerAgent(maxBuildsPerAgent);
    }

    public int getNumExecutors() {
        return agentTemplate.getNumExecutors();
    }

    @DataBoundSetter
    public void setNumExecutors(int numExecutors) {
        agentTemplate.setNumExecutors(numExecutors);
    }

    public List<ProxmoxInstance> getInstances() {
        return new ArrayList<>(instances);
    }

    public synchronized boolean canTerminateVmForScaleDown(String vmId) {
        int minInstances = agentTemplate.getMinInstances();
        if (minInstances <= 0) {
            return true; // No floor configured – always allow termination.
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return false;
        }

        long healthyCount = jenkins.getNodes().stream()
                .filter(node -> node instanceof hudson.model.Slave)
                .map(node -> (hudson.model.Slave) node)
                .filter(node -> node.getRetentionStrategy() instanceof ProxmoxRetentionStrategy)
                .filter(node -> {
                    ProxmoxRetentionStrategy strategy = (ProxmoxRetentionStrategy) node.getRetentionStrategy();
                    return name.equals(strategy.getCloudName()) && !isDraining(node);
                })
                .count();

        boolean targetIsDraining = jenkins.getNodes().stream()
                .filter(node -> node instanceof hudson.model.Slave)
                .map(node -> (hudson.model.Slave) node)
                .filter(node -> node.getRetentionStrategy() instanceof ProxmoxRetentionStrategy)
                .anyMatch(node -> {
                    ProxmoxRetentionStrategy strategy = (ProxmoxRetentionStrategy) node.getRetentionStrategy();
                    return name.equals(strategy.getCloudName())
                            && vmId != null
                            && vmId.equals(strategy.getVmId())
                            && isDraining(node);
                });

        if (targetIsDraining) {
            // Draining agents are not part of usable floor capacity; terminate once healthy floor is satisfied.
            return healthyCount >= minInstances;
        }

        return healthyCount > minInstances;
    }

    /**
     * Periodic task that runs every minute and ensures every {@link ProxmoxCloud} stays at or above
     * its configured {@code minInstances} floor, independent of the Jenkins build queue.
     */
    @Extension
    public static class MinInstancesReconciler extends AsyncPeriodicWork {

        public MinInstancesReconciler() {
            super("Proxmox minimum instances reconciler");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.MINUTES.toMillis(1);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException, InterruptedException {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return;
            }
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof ProxmoxCloud proxmoxCloud) {
                    try {
                        proxmoxCloud.reconcileMinInstances();
                    } catch (Exception e) {
                        LOGGER.log(
                                Level.WARNING, "Minimum-floor reconciliation error for cloud '" + cloud.name + "'", e);
                    }
                }
            }
        }
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void reconcileCloudNodesOnStartup() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        for (Cloud cloud : jenkins.clouds) {
            if (cloud instanceof ProxmoxCloud proxmoxCloud) {
                proxmoxCloud.ensureStartupReconciled();
            }
        }
    }

    private Node findNodeByVmId(Jenkins jenkins, String vmId) {
        for (Node node : jenkins.getNodes()) {
            if (!(node instanceof hudson.model.Slave slave)) {
                continue;
            }
            if (!(slave.getRetentionStrategy() instanceof ProxmoxRetentionStrategy retention)) {
                continue;
            }
            if (name.equals(retention.getCloudName()) && vmId.equals(retention.getVmId())) {
                return node;
            }
        }
        return null;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        public List<Descriptor<ComputerConnector>> getComputerConnectorDescriptors() {
            List<Descriptor<ComputerConnector>> all = Jenkins.get().getDescriptorList(ComputerConnector.class);
            List<Descriptor<ComputerConnector>> filtered = new ArrayList<>();
            for (Descriptor<ComputerConnector> descriptor : all) {
                if (descriptor.clazz == SSHConnector.class || descriptor.clazz == ProxmoxJNLPConnector.class) {
                    filtered.add(descriptor);
                }
            }
            return filtered;
        }

        public ListBoxModel doFillApiTokenCredentialIdItems(@QueryParameter String apiTokenCredentialId) {
            Jenkins jenkins = Jenkins.get();
            StandardListBoxModel options = new StandardListBoxModel();

            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return options.includeCurrentValue(apiTokenCredentialId);
            }

            return options.includeEmptyValue()
                    .includeAs(ACL.SYSTEM2, jenkins, ProxmoxApiTokenCredentials.class, Collections.emptyList())
                    .includeCurrentValue(apiTokenCredentialId);
        }

        public FormValidation doCheckApiTokenCredentialId(@QueryParameter String value) {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.ADMINISTER);

            if (value == null || value.isBlank()) {
                return FormValidation.error("Select a Proxmox API Token credential");
            }

            ProxmoxApiTokenCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            ProxmoxApiTokenCredentials.class, jenkins, ACL.SYSTEM2, Collections.emptyList()),
                    CredentialsMatchers.withId(value));
            if (credentials == null) {
                return FormValidation.error("Credential not found or is not a Proxmox API Token credential");
            }

            if (credentials.getUsername() == null || credentials.getUsername().isBlank()) {
                return FormValidation.error("Credential username is required");
            }
            if (credentials.getRealm() == null || credentials.getRealm().isBlank()) {
                return FormValidation.error("Credential login realm is required");
            }
            if (credentials.getTokenId() == null || credentials.getTokenId().isBlank()) {
                return FormValidation.error("Credential token identifier is required");
            }
            if (credentials.getTokenSecret() == null
                    || credentials.getTokenSecret().getPlainText().isBlank()) {
                return FormValidation.error("Credential token secret is required");
            }

            return FormValidation.ok();
        }

        @Override
        public String getDisplayName() {
            return "Proxmox Cloud";
        }
    }
}
