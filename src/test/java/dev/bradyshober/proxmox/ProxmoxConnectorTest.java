package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import org.junit.jupiter.api.Test;

class ProxmoxConnectorTest {

    @Test
    void testSshConnectorNormalizesInvalidPortAndTimeout() throws Exception {
        ProxmoxSSHConnector connector =
                new ProxmoxSSHConnector(0, "cred-id", "-Xmx512m", "/usr/bin/java", "prefix", "suffix", 0, 3, 5);

        assertEquals(22, connector.getPort());
        assertEquals(60, connector.getLaunchTimeoutSeconds());
        assertEquals("cred-id", connector.getCredentialsId());
        assertEquals("-Xmx512m", connector.getJvmOptions());
        assertEquals("/usr/bin/java", connector.getJavaPath());
        assertEquals("prefix", connector.getPrefixStartSlaveCmd());
        assertEquals("suffix", connector.getSuffixStartSlaveCmd());
        assertEquals(3, connector.getMaxNumRetries());
        assertEquals(5, connector.getRetryWaitTime());

        ComputerLauncher launcher = connector.launch("10.0.0.10", TaskListener.NULL);
        assertInstanceOf(SSHLauncher.class, launcher);
    }

    @Test
    void testSshConnectorKeepsExplicitPortAndTimeout() {
        ProxmoxSSHConnector connector = new ProxmoxSSHConnector(2222, "cred-id", null, null, null, null, 30, 1, 2);

        assertEquals(2222, connector.getPort());
        assertEquals(30, connector.getLaunchTimeoutSeconds());
    }

    @Test
    void testSshConnectorDescriptorDisplayName() {
        assertEquals(
                "SSH (Proxmox - host resolved at provisioning time)",
                new ProxmoxSSHConnector.DescriptorImpl().getDisplayName());
    }

    @Test
    void testJnlpConnectorWebSocketFlagAndLauncher() throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        assertFalse(connector.isWebSocket());

        connector.setWebSocket(true);
        assertTrue(connector.isWebSocket());

        ComputerLauncher launcher = connector.launch("ignored-host", TaskListener.NULL);
        assertInstanceOf(JNLPLauncher.class, launcher);
    }

    @Test
    void testJnlpConnectorDescriptorDisplayName() {
        assertEquals("JNLP/WebSocket (Inbound Agent)", new ProxmoxJNLPConnector.DescriptorImpl().getDisplayName());
    }
}
