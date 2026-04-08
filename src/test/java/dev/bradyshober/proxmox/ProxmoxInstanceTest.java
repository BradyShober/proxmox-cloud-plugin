package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProxmoxInstance} — exercises all getters, setters, states, and toString.
 */
class ProxmoxInstanceTest {

    @Test
    void testConstructorSetsFields() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        assertEquals("100", instance.getVmId());
        assertEquals("agent-1", instance.getAgentName());
        assertEquals(ProxmoxInstance.InstanceState.PROVISIONING, instance.getState());
        assertNull(instance.getIpAddress());
        assertNull(instance.getUpidTaskId());
        // createdTime should be set to a recent timestamp
        long now = System.currentTimeMillis();
        assertTrue(instance.getCreatedTime() <= now, "createdTime should be in the past");
        assertTrue(instance.getCreatedTime() > now - 5000, "createdTime should be recent (within 5s)");
    }

    @Test
    void testSetVmId() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setVmId("999");
        assertEquals("999", instance.getVmId());
    }

    @Test
    void testSetAgentName() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setAgentName("new-agent");
        assertEquals("new-agent", instance.getAgentName());
    }

    @Test
    void testSetIpAddress() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setIpAddress("10.0.0.5");
        assertEquals("10.0.0.5", instance.getIpAddress());
    }

    @Test
    void testSetState() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setState(ProxmoxInstance.InstanceState.RUNNING);
        assertEquals(ProxmoxInstance.InstanceState.RUNNING, instance.getState());
    }

    @Test
    void testSetCreatedTime() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        long t = 1234567890L;
        instance.setCreatedTime(t);
        assertEquals(t, instance.getCreatedTime());
    }

    @Test
    void testSetUpidTaskId() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setUpidTaskId("UPID:pve:001A0F9D:0012345F:clone:100:root@pam:");
        assertEquals("UPID:pve:001A0F9D:0012345F:clone:100:root@pam:", instance.getUpidTaskId());
    }

    @Test
    void testToStringContainsFields() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "agent-1");
        instance.setIpAddress("192.168.1.5");
        String s = instance.toString();
        assertTrue(s.contains("100"), "toString should contain vmId");
        assertTrue(s.contains("agent-1"), "toString should contain agentName");
        assertTrue(s.contains("PROVISIONING"), "toString should contain state");
        assertTrue(s.contains("192.168.1.5"), "toString should contain ipAddress");
    }

    @Test
    void testAllInstanceStatesHaveDisplayName() {
        for (ProxmoxInstance.InstanceState state : ProxmoxInstance.InstanceState.values()) {
            assertNotNull(state.getDisplayName(), "Display name should not be null for " + state);
            assertFalse(state.getDisplayName().isBlank(), "Display name should not be blank for " + state);
        }
    }

    @Test
    void testInstanceStateDisplayNames() {
        assertEquals("Provisioning", ProxmoxInstance.InstanceState.PROVISIONING.getDisplayName());
        assertEquals("Starting", ProxmoxInstance.InstanceState.STARTING.getDisplayName());
        assertEquals("Running", ProxmoxInstance.InstanceState.RUNNING.getDisplayName());
        assertEquals("Stopping", ProxmoxInstance.InstanceState.STOPPING.getDisplayName());
        assertEquals("Stopped", ProxmoxInstance.InstanceState.STOPPED.getDisplayName());
        assertEquals("Failed", ProxmoxInstance.InstanceState.FAILED.getDisplayName());
    }
}
