package dev.bradyshober.proxmox;

import hudson.slaves.ComputerLauncher;
import java.io.Serial;
import java.io.Serializable;

/**
 * Configuration for a Proxmox VM agent template.
 */
public class ProxmoxAgentTemplate implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String templateVmId;
    private String agentNameTemplate;
    private int minInstances;
    private int maxInstances;
    private ComputerLauncher launcher;
    private String sshUsername;
    private String sshPublicKey;
    private String labels;
    private String remoteFsRoot;
    private int idleMinutesBeforeTermination;
    private int maxLifetimeMinutes;
    private int numExecutors;

    public ProxmoxAgentTemplate(
            String templateVmId,
            String agentNameTemplate,
            int minInstances,
            int maxInstances,
            ComputerLauncher launcher,
            String sshUsername,
            String sshPublicKey,
            String labels,
            String remoteFsRoot,
            int idleMinutesBeforeTermination) {
        this.templateVmId = templateVmId;
        this.agentNameTemplate = agentNameTemplate;
        this.minInstances = minInstances;
        this.maxInstances = maxInstances;
        this.launcher = launcher;
        this.sshUsername = sshUsername;
        this.sshPublicKey = sshPublicKey;
        this.labels = labels;
        this.remoteFsRoot = remoteFsRoot;
        this.idleMinutesBeforeTermination = idleMinutesBeforeTermination;
        this.maxLifetimeMinutes = 0;
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

    public ComputerLauncher getLauncher() {
        return launcher;
    }

    public void setLauncher(ComputerLauncher launcher) {
        this.launcher = launcher;
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

    public int getNumExecutors() {
        return numExecutors;
    }

    public void setNumExecutors(int numExecutors) {
        this.numExecutors = Math.max(1, numExecutors);
    }
}
