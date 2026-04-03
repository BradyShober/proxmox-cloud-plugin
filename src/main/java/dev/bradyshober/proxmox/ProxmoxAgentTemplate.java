package dev.bradyshober.proxmox;

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
    private int maxInstances;
    private LauncherStrategy launcherStrategy;
    private String sshUsername;
    private String sshPrivateKey;
    private String sshPublicKey;
    private int sshPort;
    private String labels;

    public enum LauncherStrategy {
        SSH("SSH"),
        WEBSOCKET("WebSocket");

        private final String displayName;

        LauncherStrategy(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public ProxmoxAgentTemplate(
            String templateVmId,
            String agentNameTemplate,
            int maxInstances,
            LauncherStrategy launcherStrategy,
            String sshUsername,
            String sshPrivateKey,
            String sshPublicKey,
            int sshPort,
            String labels) {
        this.templateVmId = templateVmId;
        this.agentNameTemplate = agentNameTemplate;
        this.maxInstances = maxInstances;
        this.launcherStrategy = launcherStrategy;
        this.sshUsername = sshUsername;
        this.sshPrivateKey = sshPrivateKey;
        this.sshPublicKey = sshPublicKey;
        this.sshPort = sshPort;
        this.labels = labels;
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

    public void setMaxInstances(int maxInstances) {
        this.maxInstances = maxInstances;
    }

    public LauncherStrategy getLauncherStrategy() {
        return launcherStrategy;
    }

    public void setLauncherStrategy(LauncherStrategy launcherStrategy) {
        this.launcherStrategy = launcherStrategy;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }

    public String getSshPrivateKey() {
        return sshPrivateKey;
    }

    public void setSshPrivateKey(String sshPrivateKey) {
        this.sshPrivateKey = sshPrivateKey;
    }

    public String getSshPublicKey() {
        return sshPublicKey;
    }

    public void setSshPublicKey(String sshPublicKey) {
        this.sshPublicKey = sshPublicKey;
    }

    public int getSshPort() {
        return sshPort;
    }

    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }
}
