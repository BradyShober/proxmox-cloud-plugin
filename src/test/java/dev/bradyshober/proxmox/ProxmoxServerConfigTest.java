package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProxmoxServerConfig}.
 * No Jenkins context required — pure Java POJO tests.
 */
class ProxmoxServerConfigTest {

    @Test
    void testConstructorSetsAllFields() {
        ProxmoxServerConfig config =
                new ProxmoxServerConfig("https://proxmox.example.com:8006", "my-token-cred", true, "pve");

        assertEquals("https://proxmox.example.com:8006", config.getHost());
        assertEquals("my-token-cred", config.getApiTokenCredentialId());
        assertTrue(config.isVerifySsl());
        assertEquals("pve", config.getNode());
        assertFalse(config.isClusterWidePlacement());
    }

    @Test
    void testConstructorCanEnableClusterWidePlacement() {
        ProxmoxServerConfig config =
                new ProxmoxServerConfig("https://proxmox.example.com:8006", "my-token-cred", true, "pve", true);

        assertTrue(config.isClusterWidePlacement());
    }

    @Test
    void testConstructorWithSslDisabled() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://proxmox.local:8006", "cred-id", false, "node1");

        assertFalse(config.isVerifySsl());
    }

    @Test
    void testSetHost() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://old.host", "cred", true, "pve");
        config.setHost("https://new.host:8006");
        assertEquals("https://new.host:8006", config.getHost());
    }

    @Test
    void testSetApiTokenCredentialId() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://host", "old-cred", true, "pve");
        config.setApiTokenCredentialId("new-cred");
        assertEquals("new-cred", config.getApiTokenCredentialId());
    }

    @Test
    void testSetVerifySsl() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://host", "cred", true, "pve");
        config.setVerifySsl(false);
        assertFalse(config.isVerifySsl());
        config.setVerifySsl(true);
        assertTrue(config.isVerifySsl());
    }

    @Test
    void testSetNode() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://host", "cred", true, "pve");
        config.setNode("node2");
        assertEquals("node2", config.getNode());
    }

    @Test
    void testNullValuesAreStoredAsIs() {
        ProxmoxServerConfig config = new ProxmoxServerConfig(null, null, true, null);
        assertNull(config.getHost());
        assertNull(config.getApiTokenCredentialId());
        assertNull(config.getNode());
    }

    @Test
    void testSetClusterWidePlacement() {
        ProxmoxServerConfig config = new ProxmoxServerConfig("https://host", "cred", true, "pve");
        config.setClusterWidePlacement(true);
        assertTrue(config.isClusterWidePlacement());
    }
}
