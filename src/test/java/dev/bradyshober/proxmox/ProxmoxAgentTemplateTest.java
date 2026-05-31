package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProxmoxAgentTemplate}.
 * No Jenkins context required — pure Java POJO tests.
 */
class ProxmoxAgentTemplateTest {

    private ProxmoxAgentTemplate template;

    @BeforeEach
    void setUp() {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        template = new ProxmoxAgentTemplate(
                "100",
                "agent-{{n}}",
                1,
                5,
                connector,
                "jenkins",
                "ssh-rsa AAAA key",
                "proxmox linux",
                "/home/jenkins",
                7);
    }

    @Test
    void testConstructorSetsAllFields() {
        assertEquals("100", template.getTemplateVmId());
        assertEquals("agent-{{n}}", template.getAgentNameTemplate());
        assertEquals(1, template.getMinInstances());
        assertEquals(5, template.getMaxInstances());
        assertNotNull(template.getComputerConnector());
        assertEquals("jenkins", template.getSshUsername());
        assertEquals("ssh-rsa AAAA key", template.getSshPublicKey());
        assertEquals("proxmox linux", template.getLabels());
        assertEquals("/home/jenkins", template.getRemoteFsRoot());
        assertEquals(7, template.getIdleMinutesBeforeTermination());
    }

    @Test
    void testDefaultOptionalFields() {
        // maxLifetimeMinutes, maxBuildsPerAgent, numExecutors are defaulted in constructor
        assertEquals(0, template.getMaxLifetimeMinutes());
        assertEquals(0, template.getMaxBuildsPerAgent());
        assertEquals(1, template.getNumExecutors());
    }

    @Test
    void testSetTemplateVmId() {
        template.setTemplateVmId("200");
        assertEquals("200", template.getTemplateVmId());
    }

    @Test
    void testSetAgentNameTemplate() {
        template.setAgentNameTemplate("worker-{{n}}");
        assertEquals("worker-{{n}}", template.getAgentNameTemplate());
    }

    @Test
    void testSetMinInstances() {
        template.setMinInstances(3);
        assertEquals(3, template.getMinInstances());
    }

    @Test
    void testSetMaxInstances() {
        template.setMaxInstances(10);
        assertEquals(10, template.getMaxInstances());
    }

    @Test
    void testSetSshUsername() {
        template.setSshUsername("admin");
        assertEquals("admin", template.getSshUsername());
    }

    @Test
    void testSetSshPublicKey() {
        template.setSshPublicKey("ssh-ed25519 AAAA newkey");
        assertEquals("ssh-ed25519 AAAA newkey", template.getSshPublicKey());
    }

    @Test
    void testSetLabels() {
        template.setLabels("docker gpu");
        assertEquals("docker gpu", template.getLabels());
    }

    @Test
    void testSetRemoteFsRoot() {
        template.setRemoteFsRoot("/var/jenkins");
        assertEquals("/var/jenkins", template.getRemoteFsRoot());
    }

    @Test
    void testSetIdleMinutesBeforeTermination() {
        template.setIdleMinutesBeforeTermination(15);
        assertEquals(15, template.getIdleMinutesBeforeTermination());
    }

    @Test
    void testSetMaxLifetimeMinutes() {
        template.setMaxLifetimeMinutes(120);
        assertEquals(120, template.getMaxLifetimeMinutes());
    }

    @Test
    void testSetMaxLifetimeMinutesNegativeNormalizesToZero() {
        template.setMaxLifetimeMinutes(-1);
        assertEquals(0, template.getMaxLifetimeMinutes());
    }

    @Test
    void testSetMaxBuildsPerAgent() {
        template.setMaxBuildsPerAgent(10);
        assertEquals(10, template.getMaxBuildsPerAgent());
    }

    @Test
    void testSetMaxBuildsPerAgentNegativeNormalizesToZero() {
        template.setMaxBuildsPerAgent(-5);
        assertEquals(0, template.getMaxBuildsPerAgent());
    }

    @Test
    void testSetNumExecutors() {
        template.setNumExecutors(4);
        assertEquals(4, template.getNumExecutors());
    }

    @Test
    void testSetNumExecutorsBelowOneNormalizesToOne() {
        template.setNumExecutors(0);
        assertEquals(1, template.getNumExecutors());
        template.setNumExecutors(-3);
        assertEquals(1, template.getNumExecutors());
    }

    @Test
    void testSetLauncher() {
        ProxmoxJNLPConnector newConnector = new ProxmoxJNLPConnector();
        newConnector.setWebSocket(false);
        template.setComputerConnector(newConnector);
        assertSame(newConnector, template.getComputerConnector());
    }
}
