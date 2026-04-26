package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Node;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link ProxmoxRetentionStrategy}.
 * Constructor and getter tests do not require a Jenkins context.
 */
class ProxmoxRetentionStrategyTest {

    private ProxmoxNode createNode(String nodeName, ProxmoxRetentionStrategy strategy) throws Exception {
        return new ProxmoxNode(
                nodeName,
                "test node",
                "/home/jenkins",
                1,
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                strategy,
                strategy.getMaxBuildsPerAgent());
    }

    private void setCreatedAtMillis(ProxmoxRetentionStrategy strategy, long value) throws Exception {
        Field field = ProxmoxRetentionStrategy.class.getDeclaredField("createdAtMillis");
        field.setAccessible(true);
        field.setLong(strategy, value);
    }

    @Test
    void testDefaultConstructorUsesDefaultValues() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();

        assertNull(strategy.getCloudName());
        assertNull(strategy.getVmId());
        assertEquals(ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES, strategy.getIdleMinutes());
        assertEquals(ProxmoxRetentionStrategy.DEFAULT_MAX_LIFETIME_MINUTES, strategy.getMaxLifetimeMinutes());
        assertEquals(0, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testThreeArgConstructor() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("myCloud", "vm-100", 10);

        assertEquals("myCloud", strategy.getCloudName());
        assertEquals("vm-100", strategy.getVmId());
        assertEquals(10, strategy.getIdleMinutes());
        assertEquals(0, strategy.getMaxLifetimeMinutes());
        assertEquals(0, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testFourArgConstructor() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "vm-200", 8, 60);

        assertEquals("cloud", strategy.getCloudName());
        assertEquals("vm-200", strategy.getVmId());
        assertEquals(8, strategy.getIdleMinutes());
        assertEquals(60, strategy.getMaxLifetimeMinutes());
        assertEquals(0, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testFiveArgConstructor() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "vm-300", 5, 120, 10);

        assertEquals("cloud", strategy.getCloudName());
        assertEquals("vm-300", strategy.getVmId());
        assertEquals(5, strategy.getIdleMinutes());
        assertEquals(120, strategy.getMaxLifetimeMinutes());
        assertEquals(10, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testIdleMinutesZeroOrNegativeUsesDefault() {
        ProxmoxRetentionStrategy zeroIdle = new ProxmoxRetentionStrategy("c", "v", 0);
        assertEquals(ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES, zeroIdle.getIdleMinutes());

        ProxmoxRetentionStrategy negativeIdle = new ProxmoxRetentionStrategy("c", "v", -5);
        assertEquals(ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES, negativeIdle.getIdleMinutes());
    }

    @Test
    void testNegativeMaxLifetimeNormalizesToZero() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "vm", 5, -10);
        assertEquals(0, strategy.getMaxLifetimeMinutes());
    }

    @Test
    void testNegativeMaxBuildsNormalizesToZero() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "vm", 5, 0, -1);
        assertEquals(0, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testSetCloudName() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("original", "vm", 5);
        strategy.setCloudName("updated");
        assertEquals("updated", strategy.getCloudName());
    }

    @Test
    void testSetVmId() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "old-vm", 5);
        strategy.setVmId("new-vm");
        assertEquals("new-vm", strategy.getVmId());
    }

    @Test
    void testSetIdleMinutes() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setIdleMinutes(15);
        assertEquals(15, strategy.getIdleMinutes());
    }

    @Test
    void testSetIdleMinutesZeroUsesDefault() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setIdleMinutes(0);
        assertEquals(ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES, strategy.getIdleMinutes());
    }

    @Test
    void testSetMaxLifetimeMinutes() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setMaxLifetimeMinutes(90);
        assertEquals(90, strategy.getMaxLifetimeMinutes());
    }

    @Test
    void testSetMaxLifetimeMinutesNegativeNormalizesToZero() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setMaxLifetimeMinutes(-1);
        assertEquals(0, strategy.getMaxLifetimeMinutes());
    }

    @Test
    void testSetMaxBuildsPerAgent() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setMaxBuildsPerAgent(5);
        assertEquals(5, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testSetMaxBuildsPerAgentNegativeNormalizesToZero() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        strategy.setMaxBuildsPerAgent(-3);
        assertEquals(0, strategy.getMaxBuildsPerAgent());
    }

    @Test
    void testConstantValues() {
        assertEquals(5, ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES);
        assertEquals(0, ProxmoxRetentionStrategy.DEFAULT_MAX_LIFETIME_MINUTES);
        assertTrue(ProxmoxRetentionStrategy.MAX_LIFETIME_DRAIN_REASON_PREFIX.contains("lifetime"));
        assertTrue(ProxmoxRetentionStrategy.MAX_BUILDS_DRAIN_REASON_PREFIX.contains("build"));
    }

    @Test
    @WithJenkins
    void testCheckSkipsTerminationWhenCloudOrVmIdMissing(JenkinsRule j) throws Exception {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy(null, null, 5, 1, 0);
        ProxmoxNode node = createNode("agent-missing-identity", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);

        setCreatedAtMillis(strategy, System.currentTimeMillis() - (2L * 60L * 1000L));

        long result = strategy.check(computer);

        assertEquals(1L, result);
        assertTrue(computer.isTemporarilyOffline(), "max-lifetime drain should mark computer offline");
    }

    @Test
    @WithJenkins
    void testCheckSkipsTerminationWhenCloudCannotBeResolved(JenkinsRule j) throws Exception {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("missing-cloud", "vm-501", 5, 0, 0);
        ProxmoxNode node = createNode("agent-missing-cloud", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);

        long result = strategy.check(computer);

        assertEquals(1L, result);
    }

    @Test
    @WithJenkins
    void testCheckDefersWhenComputerIsNotIdle(JenkinsRule j) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        ProxmoxCloud cloud = new ProxmoxCloud(
                "proxmox-retention",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "agent",
                0,
                5,
                1,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);
        j.jenkins.clouds.add(cloud);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("proxmox-retention", "vm-700", 1, 1, 0);
        ProxmoxNode node = createNode("agent-idle-terminate", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);
        setCreatedAtMillis(strategy, System.currentTimeMillis() - (2L * 60L * 1000L));

        long result = strategy.check(computer);

        assertEquals(1L, result);
        assertNotNull(j.jenkins.getNode("agent-idle-terminate"));
    }
}
