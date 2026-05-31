package dev.bradyshober.proxmox;

import hudson.slaves.ComputerConnector;

/**
 * Configuration for a Proxmox VM agent template.
 * Stores connector configuration WITHOUT a fixed host - the host is resolved
 * at provisioning time from the actual VM, then passed to connector.launch(host, ...).
 */
public class ProxmoxAgentTemplate {
    private String templateVmId;
    private String agentNameTemplate;
    private int minInstances;
    private int maxInstances;
    private ComputerConnector computerConnector;
    private String sshUsername;
    private String sshPublicKey;
    private String labels;
    private String remoteFsRoot;
    private int idleMinutesBeforeTermination;
    private int maxLifetimeMinutes;
    private int maxBuildsPerAgent;
    private int numExecutors;

    public ProxmoxAgentTemplate(
            String templateVmId,
            String agentNameTemplate,
            int minInstances,
            int maxInstances,
            ComputerConnector computerConnector,
            String sshUsername,
            String sshPublicKey,
            String labels,
            String remoteFsRoot,
            int idleMinutesBeforeTermination) {
        this.templateVmId = templateVmId;
        this.agentNameTemplate = agentNameTemplate;
        this.minInstances = minInstances;
        this.maxInstances = maxInstances;
        this.computerConnector = computerConnector;
        this.sshUsername = sshUsername;
        this.sshPublicKey = sshPublicKey;
        this.labels = labels;
        this.remoteFsRoot = remoteFsRoot;
        this.idleMinutesBeforeTermination = idleMinutesBeforeTermination;
        this.maxLifetimeMinutes = 0;
        this.maxBuildsPerAgent = 0;
        this.numExecutors = 1;
    }

    public String getTemplateVmId() {
        return templateVmId;
    }

    public void setTemplateVmId(String templateVmId) {
        this.templateVmId = templateVmId;
    }

    public String getAgentNameTemplate() {
        return agentNameTemplate;
    }

    public void setAgentNameTemplate(String agentNameTemplate) {
        this.agentNameTemplate = agentNameTemplate;
    }

    public int getMaxInstances() {
        return maxInstances;
    }

    public int getMinInstances() {
        return minInstances;
    }

    public void setMinInstances(int minInstances) {
        this.minInstances = minInstances;
    }

    public void setMaxInstances(int maxInstances) {
        this.maxInstances = maxInstances;
    }

    public ComputerConnector getComputerConnector() {
        return computerConnector;
    }

    public void setComputerConnector(ComputerConnector computerConnector) {
        this.computerConnector = computerConnector;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }

    public String getSshPublicKey() {
        return sshPublicKey;
    }

    public void setSshPublicKey(String sshPublicKey) {
        this.sshPublicKey = sshPublicKey;
    }

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    public String getRemoteFsRoot() {
        return remoteFsRoot;
    }

    public void setRemoteFsRoot(String remoteFsRoot) {
        this.remoteFsRoot = remoteFsRoot;
    }

    public int getIdleMinutesBeforeTermination() {
        return idleMinutesBeforeTermination;
    }

    public void setIdleMinutesBeforeTermination(int idleMinutesBeforeTermination) {
        this.idleMinutesBeforeTermination = idleMinutesBeforeTermination;
    }

    public int getMaxLifetimeMinutes() {
        return maxLifetimeMinutes;
    }

    public void setMaxLifetimeMinutes(int maxLifetimeMinutes) {
        this.maxLifetimeMinutes = Math.max(0, maxLifetimeMinutes);
    }

    public int getMaxBuildsPerAgent() {
        return maxBuildsPerAgent;
    }

    public void setMaxBuildsPerAgent(int maxBuildsPerAgent) {
        this.maxBuildsPerAgent = Math.max(0, maxBuildsPerAgent);
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public void setNumExecutors(int numExecutors) {
        this.numExecutors = Math.max(1, numExecutors);
    }
}
