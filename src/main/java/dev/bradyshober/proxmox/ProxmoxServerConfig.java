package dev.bradyshober.proxmox;

import java.io.Serial;
import java.io.Serializable;

/**
 * Configuration for a Proxmox VE server connection.
 */
public class ProxmoxServerConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String host;
    private String apiTokenCredentialId;
    private boolean verifySsl;
    private String node;

    public ProxmoxServerConfig(String host, String apiTokenCredentialId, boolean verifySsl, String node) {
        this.host = host;
        this.apiTokenCredentialId = apiTokenCredentialId;
        this.verifySsl = verifySsl;
        this.node = node;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiTokenCredentialId() {
        return apiTokenCredentialId;
    }

    public void setApiTokenCredentialId(String apiTokenCredentialId) {
        this.apiTokenCredentialId = apiTokenCredentialId;
    }

    public boolean isVerifySsl() {
        return verifySsl;
    }

    public void setVerifySsl(boolean verifySsl) {
        this.verifySsl = verifySsl;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }
}
