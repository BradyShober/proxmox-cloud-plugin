package dev.bradyshober.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpSlaveAgentProtocol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Proxmox Cloud provider for dynamic agent provisioning.
 * Listens to the Jenkins build queue and provisions VMs from a template when builds are pending.
 */
public class ProxmoxCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloud.class.getName());

    @Serial
    private static final long serialVersionUID = 1L;

    private final ProxmoxServerConfig serverConfig;
    private final ProxmoxAgentTemplate agentTemplate;
    private final List<ProxmoxInstance> instances;
    private transient ProxmoxClient proxmoxClient;

    @DataBoundConstructor
    public ProxmoxCloud(
            String name,
            String host,
            String apiTokenCredentialId,
            boolean verifySsl,
            String node,
            String templateVmId,
            String agentNameTemplate,
            int maxInstances,
            String launcherStrategy,
            String sshUsername,
            String sshPrivateKey,
            String sshPublicKey,
            int sshPort,
            String labels) {
        super(name);
        this.serverConfig = new ProxmoxServerConfig(
                host, apiTokenCredentialId, verifySsl, node == null || node.isBlank() ? "pve" : node);

        // Parse launcher strategy from string (form dropdown passes string)
        ProxmoxAgentTemplate.LauncherStrategy strategy;
        try {
            strategy = launcherStrategy != null && !launcherStrategy.isBlank()
                    ? ProxmoxAgentTemplate.LauncherStrategy.valueOf(launcherStrategy)
                    : ProxmoxAgentTemplate.LauncherStrategy.SSH;
        } catch (IllegalArgumentException e) {
            strategy = ProxmoxAgentTemplate.LauncherStrategy.SSH;
        }

        this.agentTemplate = new ProxmoxAgentTemplate(
                templateVmId,
                agentNameTemplate == null || agentNameTemplate.isBlank() ? "proxmox-agent" : agentNameTemplate,
                maxInstances > 0 ? maxInstances : 1,
                strategy,
                sshUsername == null || sshUsername.isBlank() ? "jenkins" : sshUsername,
                sshPrivateKey,
                sshPublicKey,
                sshPort > 0 ? sshPort : 22,
                labels == null || labels.isBlank() ? "proxmox" : labels);
        this.instances = Collections.synchronizedList(new ArrayList<>());
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

        LOGGER.log(Level.FINE, "Label '" + labelName + "' not in configured labels: " + configuredLabels);
        return false;
    }

    /**
     * Called by Jenkins to determine how many new agents can be provisioned.
     * Returns provisioning suggestions based on pending queue items and instance capacity.
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.log(Level.FINE, "Provisioning requested for label: " + label + ", excessWorkload: " + excessWorkload);

        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        if (excessWorkload <= 0) {
            return plannedNodes;
        }

        try {
            if (proxmoxClient == null) {
                initializeClient();
            }

            // Check current instance count against max
            int currentCount = instances.size();
            int maxInstances = agentTemplate.getMaxInstances();
            int availableCapacity = maxInstances - currentCount;

            if (availableCapacity <= 0) {
                LOGGER.log(Level.INFO, "Instance capacity reached: " + currentCount + "/" + maxInstances);
                return plannedNodes;
            }

            // Provision as many instances as needed (up to available capacity)
            int instancesToProvision = Math.min(excessWorkload, availableCapacity);

            for (int i = 0; i < instancesToProvision; i++) {
                // Generate unique agent name
                String agentName = generateAgentName();

                LOGGER.log(Level.INFO, "Planning Proxmox agent provisioning for " + agentName);

                // Create cloud-init script for agent bootstrap
                String cloudInitScript = generateCloudInitScript(agentName);

                // Provision the VM asynchronously
                NodeProvisioner.PlannedNode plannedNode = new NodeProvisioner.PlannedNode(
                        agentName,
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                return provisionAgent(agentName, cloudInitScript);
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, "Failed to provision agent: " + agentName, e);
                                return null;
                            }
                        }),
                        1 // Number of executors per node
                        );

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
     * <p>For <b>SSH outbound</b> mode the VM is started first, its IP is resolved via the QEMU
     * guest agent, then the node is created and launched.
     */
    private Node provisionAgent(String agentName, String cloudInitScript) throws Exception {
        LOGGER.log(Level.INFO, "Starting provisioning of agent: " + agentName);

        // Fetch a valid integer VM ID from Proxmox to avoid conflicts
        String vmId = proxmoxClient.getNextVmId();
        LOGGER.log(Level.FINE, "Allocated VM ID from Proxmox for " + agentName + ": " + vmId);

        ProxmoxAgentTemplate.LauncherStrategy strategy = agentTemplate.getLauncherStrategy();

        // Compute the JNLP secret now (before clone) so the node can be pre-registered.
        // The secret is injected post-boot via guest agent — not via cloud-init snippets,
        // which the Proxmox REST API does not support for snippet content type.
        String jnlpSecret = null;
        DumbSlave preRegisteredNode = null;
        if (strategy == ProxmoxAgentTemplate.LauncherStrategy.WEBSOCKET) {
            jnlpSecret = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(agentName);
            LOGGER.log(Level.FINE, "Computed inbound agent secret for " + agentName);

            // Pre-register so Jenkins accepts the inbound connection
            preRegisteredNode = buildDumbSlave(agentName, vmId, null);
            Jenkins.get().addNode(preRegisteredNode);
            LOGGER.log(Level.INFO, "Pre-registered Jenkins node for inbound agent: " + agentName);
        }

        // Clone the template VM (no cicustom — snippet upload is not supported via Proxmox API)
        String templateVmId = agentTemplate.getTemplateVmId();
        String upidClone = proxmoxClient.cloneVmWithCloudInit(templateVmId, vmId, agentName, null);
        LOGGER.log(Level.FINE, "Clone task started for VM " + vmId + ": " + upidClone);

        // Track this instance
        ProxmoxInstance instance = new ProxmoxInstance(vmId, agentName);
        instance.setUpidTaskId(upidClone);
        instances.add(instance);

        // Wait for clone task to complete
        waitForTaskCompletion("clone", vmId, upidClone);
        instance.setState(ProxmoxInstance.InstanceState.STARTING);

        // Configure cloud-init on the cloned VM: create the agent user, inject the SSH
        // public key into authorized_keys, and request DHCP on the primary NIC.
        // Requires the template to have a cloud-init drive attached.
        try {
            proxmoxClient.configureVmCloudInit(vmId, agentTemplate.getSshUsername(), agentTemplate.getSshPublicKey());
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not configure cloud-init params for VM " + vmId + "; continuing anyway. Error: "
                            + e.getMessage());
        }

        // Start the VM
        String upidStart = proxmoxClient.startVm(vmId);
        LOGGER.log(Level.FINE, "Start task initiated for VM " + vmId + ": " + upidStart);

        // Wait for VM to start
        waitForTaskCompletion("start", vmId, upidStart);
        instance.setState(ProxmoxInstance.InstanceState.RUNNING);
        LOGGER.log(Level.INFO, "VM provisioned and started: " + agentName);

        // -----------------------------------------------------------------------
        // WebSocket / JNLP inbound: write the agent service file with the JNLP
        // secret via QEMU guest agent, then start the service.
        // Requires qemu-guest-agent and Java to be pre-installed in the template.
        // -----------------------------------------------------------------------
        if (strategy == ProxmoxAgentTemplate.LauncherStrategy.WEBSOCKET && jnlpSecret != null) {
            String serviceContent = buildJenkinsAgentServiceContent(agentName, jnlpSecret);
            LOGGER.log(Level.FINE, "Waiting for QEMU guest agent on VM " + vmId);
            proxmoxClient.waitForGuestAgent(vmId);

            // Download agent.jar from Jenkins before writing the service file.
            downloadAgentJar(proxmoxClient, vmId);

            proxmoxClient.writeFileViaGuestAgent(vmId, "/etc/systemd/system/jenkins-agent.service", serviceContent);
            LOGGER.log(Level.FINE, "Wrote jenkins-agent.service to VM " + vmId + " via guest agent");

            // Validate unit syntax early so provisioning logs include the parse error.
            proxmoxClient.execCommandViaGuestAgent(
                    vmId, "systemd-analyze", "verify", "/etc/systemd/system/jenkins-agent.service");
            proxmoxClient.execCommandViaGuestAgent(vmId, "systemctl", "daemon-reload");
            proxmoxClient.execCommandViaGuestAgent(vmId, "systemctl", "enable", "--now", "jenkins-agent.service");
            proxmoxClient.execCommandViaGuestAgent(vmId, "systemctl", "is-active", "jenkins-agent.service");
            LOGGER.log(Level.INFO, "Started jenkins-agent.service on VM " + vmId + "; waiting for inbound connection");
            return preRegisteredNode;
        }

        // -----------------------------------------------------------------------
        // SSH mode: resolve IP via QEMU guest agent, then create + return the node.
        // -----------------------------------------------------------------------
        if (strategy == ProxmoxAgentTemplate.LauncherStrategy.SSH) {
            LOGGER.log(Level.FINE, "Resolving IP address for VM " + vmId + " via QEMU guest agent");
            try {
                String ipAddress = proxmoxClient.getVmIpAddress(vmId);
                instance.setIpAddress(ipAddress);
                LOGGER.log(Level.INFO, "Resolved VM " + vmId + " IP address: " + ipAddress);
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "Could not resolve VM IP address via guest agent; "
                                + "the SSH launcher may not be able to connect. Error: " + e.getMessage());
            }
            return buildDumbSlave(agentName, vmId, instance.getIpAddress());
        }

        // Fallback – should not normally be reached
        return buildDumbSlave(agentName, vmId, null);
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
                LOGGER.log(Level.FINE, "Proxmox " + phase + " task completed for VM " + vmId + ": " + upid);
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
     * Build a Jenkins {@link DumbSlave} node for the provisioned VM, wired with the
     * appropriate launcher and a {@link ProxmoxRetentionStrategy} that will delete the
     * VM once the agent has been idle for {@link ProxmoxRetentionStrategy#DEFAULT_IDLE_MINUTES}
     * minutes.
     *
     * @param agentName display name for the Jenkins node
     * @param vmId      Proxmox VM ID that backs this node (used by the retention strategy)
     * @param ipAddress resolved IP address for SSH connections; may be {@code null} for
     *                  WebSocket/inbound agents
     */
    private DumbSlave buildDumbSlave(String agentName, String vmId, String ipAddress) throws Exception {
        ProxmoxAgentTemplate.LauncherStrategy strategy = agentTemplate.getLauncherStrategy();

        ComputerLauncher launcher =
                switch (strategy) {
                    case SSH ->
                        new ProxmoxSshLauncher(
                                ipAddress,
                                agentTemplate.getSshPort(),
                                agentTemplate.getSshUsername(),
                                agentTemplate.getSshPrivateKey());
                    case WEBSOCKET -> new ProxmoxWebSocketLauncher(ipAddress);
                };

        return new DumbSlave(
                agentName,
                "Proxmox provisioned agent (VM " + vmId + ")",
                "/home/jenkins",
                "1",
                Node.Mode.NORMAL,
                agentTemplate.getLabels(),
                launcher,
                new ProxmoxRetentionStrategy(name, vmId, ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES));
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
        String jenkinsUrl = jenkins != null ? jenkins.getRootUrl() : "http://jenkins:8080/";
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            jenkinsUrl = "http://jenkins:8080/";
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

        LOGGER.log(Level.FINE, "Downloading agent.jar from " + agentJarUrl + " onto VM " + vmId);

        // Ensure destination path exists before download attempts.
        //client.execCommandViaGuestAgent(vmId, "mkdir", "-p", destDir);

        // Prefer wget because some templates do not include curl.
        boolean downloaded = tryDownloadWithRetries(client, vmId, agentJarUrl, destPath, "wget", "-O", destPath, agentJarUrl)
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
            throw new IOException("Unable to download agent.jar to VM " + vmId
                    + " after retries using wget/curl. URL=" + agentJarUrl);
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
                LOGGER.log(
                        Level.FINE,
                        "agent.jar download succeeded on VM " + vmId + " using " + commandAndArgs[0]
                                + " (attempt " + attempt + ")");
                return true;
            } catch (Exception e) {
                LOGGER.log(
                        Level.FINE,
                        "agent.jar download attempt " + attempt + "/" + maxAttempts + " failed on VM " + vmId
                                + " using " + commandAndArgs[0] + " to fetch " + url + " -> " + destPath
                                + ": " + e.getMessage());
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
        String jenkinsUrl = jenkins != null ? jenkins.getRootUrl() : "http://jenkins:8080/";
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            jenkinsUrl = "http://jenkins:8080/";
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
     * Stop and delete a Proxmox VM that was backing a Jenkins agent.
     * This method is called by {@link ProxmoxRetentionStrategy} when the agent
     * becomes idle beyond the configured threshold.
     *
     * @param vmId Proxmox VM ID to terminate
     */
    public void terminateInstance(String vmId) {
        LOGGER.log(Level.INFO, "Terminating Proxmox VM: " + vmId);
        try {
            if (proxmoxClient == null) {
                initializeClient();
            }
            // Attempt a graceful stop first
            try {
                String upid = proxmoxClient.stopVm(vmId);
                waitForTaskCompletion(upid);
                LOGGER.log(Level.INFO, "VM " + vmId + " stopped");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not gracefully stop VM " + vmId + "; attempting delete anyway", e);
            }
            // Delete the VM
            proxmoxClient.deleteVm(vmId);
            instances.removeIf(i -> vmId.equals(i.getVmId()));
            LOGGER.log(Level.INFO, "VM " + vmId + " deleted and removed from instance list");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error terminating VM " + vmId, e);
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

    public ProxmoxAgentTemplate.LauncherStrategy getLauncherStrategy() {
        return agentTemplate.getLauncherStrategy();
    }

    public String getLauncherStrategyString() {
        return agentTemplate.getLauncherStrategy().name();
    }

    public String getSshUsername() {
        return agentTemplate.getSshUsername();
    }

    public String getSshPrivateKey() {
        return agentTemplate.getSshPrivateKey();
    }

    public String getSshPublicKey() {
        return agentTemplate.getSshPublicKey();
    }

    public int getSshPort() {
        return agentTemplate.getSshPort();
    }

    public String getLabels() {
        return agentTemplate.getLabels();
    }

    public List<ProxmoxInstance> getInstances() {
        return new ArrayList<>(instances);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        public ListBoxModel doFillApiTokenCredentialIdItems(@QueryParameter String apiTokenCredentialId) {
            Jenkins jenkins = Jenkins.get();
            StandardListBoxModel options = new StandardListBoxModel();

            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return options.includeCurrentValue(apiTokenCredentialId);
            }

            return options.includeEmptyValue()
                    .includeAs(ACL.SYSTEM2, jenkins, StringCredentials.class, Collections.emptyList())
                    .includeCurrentValue(apiTokenCredentialId);
        }

        public FormValidation doCheckApiTokenCredentialId(@QueryParameter String value) {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.ADMINISTER);

            if (value == null || value.isBlank()) {
                return FormValidation.error("Select the secret text credential containing the Proxmox API token");
            }

            StringCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StringCredentials.class, jenkins, ACL.SYSTEM2, Collections.emptyList()),
                    CredentialsMatchers.withId(value));
            if (credentials == null) {
                return FormValidation.error("Credential not found or is not a secret text credential");
            }

            return FormValidation.ok();
        }

        public ListBoxModel doFillLauncherStrategyItems() {
            ListBoxModel items = new ListBoxModel();
            for (ProxmoxAgentTemplate.LauncherStrategy strategy : ProxmoxAgentTemplate.LauncherStrategy.values()) {
                items.add(strategy.getDisplayName(), strategy.name());
            }
            return items;
        }

        @Override
        public String getDisplayName() {
            return "Proxmox Cloud";
        }
    }
}
