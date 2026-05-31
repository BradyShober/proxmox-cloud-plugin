package dev.bradyshober.proxmox;

import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a Proxmox VM instance spawned for a Jenkins agent.
 */
public class ProxmoxInstance implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String vmId;
    private String agentName;
    private String ipAddress;
    private InstanceState state;
    private long createdTime;
    private String upidTaskId;

    public enum InstanceState {
        PROVISIONING("Provisioning"),
        STARTING("Starting"),
        RUNNING("Running"),
        STOPPING("Stopping"),
        STOPPED("Stopped"),
        FAILED("Failed");

        private final String displayName;

        InstanceState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public ProxmoxInstance(String vmId, String agentName) {
        this.vmId = vmId;
        this.agentName = agentName;
        this.state = InstanceState.PROVISIONING;
        this.createdTime = System.currentTimeMillis();
    }

    public String getVmId() {
        return vmId;
    }

    public void setVmId(String vmId) {
        this.vmId = vmId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public InstanceState getState() {
        return state;
    }

    public void setState(InstanceState state) {
        this.state = state;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public String getUpidTaskId() {
        return upidTaskId;
    }

    public void setUpidTaskId(String upidTaskId) {
        this.upidTaskId = upidTaskId;
    }

    @Override
    public String toString() {
        return "ProxmoxInstance{" + "vmId='"
                + vmId + '\'' + ", agentName='"
                + agentName + '\'' + ", state="
                + state + ", ipAddress='"
                + ipAddress + '\'' + '}';
    }
}
