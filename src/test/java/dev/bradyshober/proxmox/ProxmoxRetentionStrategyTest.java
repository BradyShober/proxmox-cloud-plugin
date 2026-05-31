package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link ProxmoxRetentionStrategy}.
 * Constructor and getter tests do not require a Jenkins context.
 */
class ProxmoxRetentionStrategyTest {

    private static final class TrackingProxmoxCloud extends ProxmoxCloud {
        private String terminatedVmId;

        private TrackingProxmoxCloud(String name, int minInstances) {
            super(
                    name,
                    "https://proxmox.example.com:8006",
                    "unused-credential",
                    false,
                    "pve",
                    "100",
                    "agent",
                    minInstances,
                    5,
                    5,
                    new ProxmoxJNLPConnector(),
                    "jenkins",
                    null,
                    "proxmox",
                    "/home/jenkins",
                    1);
        }

        @Override
        public void terminateInstance(String vmId) {
            this.terminatedVmId = vmId;
        }
    }

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

    private void invokePrivate(
            ProxmoxRetentionStrategy strategy, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = ProxmoxRetentionStrategy.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(strategy, args);
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

    @Test
    @WithJenkins
    void testCheckTerminatesIdleNodeWhenMaxLifetimeExceeded(JenkinsRule j) throws Exception {
        TrackingProxmoxCloud cloud = new TrackingProxmoxCloud("tracked-cloud", 0);
        cloud.setMaxLifetimeMinutes(1);
        j.jenkins.clouds.add(cloud);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("tracked-cloud", "vm-800", 5, 1, 0);
        ProxmoxNode node = createNode("agent-max-lifetime", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);
        setCreatedAtMillis(strategy, System.currentTimeMillis() - (2L * 60L * 1000L));

        long result = strategy.check(computer);

        assertEquals(0L, result);
        assertEquals("vm-800", cloud.terminatedVmId);
        assertNull(j.jenkins.getNode("agent-max-lifetime"));
    }

    @Test
    @WithJenkins
    void testCheckSkipsTerminationWhenMinInstancesFloorWouldBeViolated(JenkinsRule j) throws Exception {
        TrackingProxmoxCloud cloud = new TrackingProxmoxCloud("floor-cloud", 1);
        cloud.setMaxLifetimeMinutes(1);
        j.jenkins.clouds.add(cloud);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("floor-cloud", "vm-801", 5, 1, 0);
        ProxmoxNode node = createNode("agent-floor-protected", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);
        setCreatedAtMillis(strategy, System.currentTimeMillis() - (2L * 60L * 1000L));

        long result = strategy.check(computer);

        assertEquals(1L, result);
        assertNull(cloud.terminatedVmId);
        assertNotNull(j.jenkins.getNode("agent-floor-protected"));
        assertTrue(computer.isTemporarilyOffline(), "lifetime drain should still mark the node offline");
    }

    @Test
    @WithJenkins
    void testMarkOfflineForMaxBuildsIfNeededMarksComputerOffline(JenkinsRule j) throws Exception {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("cloud", "vm-900", 5, 0, 3);
        ProxmoxNode node = createNode("agent-max-builds", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);

        invokePrivate(
                strategy,
                "markOfflineForMaxBuildsIfNeeded",
                new Class<?>[] {SlaveComputer.class, boolean.class, int.class},
                computer,
                true,
                3);

        assertTrue(computer.isTemporarilyOffline());
        assertTrue(computer.getOfflineCauseReason().contains("Reached max build count of 3 builds"));
    }

    @Test
    @WithJenkins
    void testTaskLifecycleOverloadsAndBridgeMethodsAreCallable(JenkinsRule j) throws Exception {
        TrackingProxmoxCloud cloud = new TrackingProxmoxCloud("bridge-cloud", 0);
        j.jenkins.clouds.add(cloud);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("bridge-cloud", "vm-901", 5, 0, 0);
        ProxmoxNode node = createNode("agent-bridge", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);

        Logger logger = Logger.getLogger(ProxmoxRetentionStrategy.class.getName());
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.FINEST);
        try {
            Queue.Task nullTask = null;
            strategy.taskAccepted(computer.getExecutors().get(0), nullTask);
            strategy.taskCompleted(computer.getExecutors().get(0), nullTask, 25L);
            strategy.taskCompletedWithProblems(
                    computer.getExecutors().get(0), nullTask, 50L, new RuntimeException("boom"));

            ProxmoxRetentionStrategy.BuildEventBridge bridge = new ProxmoxRetentionStrategy.BuildEventBridge();
            bridge.taskAccepted(computer.getExecutors().get(0), nullTask);
            bridge.taskCompleted(computer.getExecutors().get(0), nullTask, 10L);
            bridge.taskCompletedWithProblems(
                    computer.getExecutors().get(0), nullTask, 11L, new IllegalStateException("boom"));
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Test
    @WithJenkins
    void testBuildEventBridgeIgnoresNonProxmoxRetentionStrategy(JenkinsRule j) throws Exception {
        ProxmoxRetentionStrategy.BuildEventBridge bridge = new ProxmoxRetentionStrategy.BuildEventBridge();
        SlaveComputer regularComputer = (SlaveComputer) j.createSlave().toComputer();
        assertNotNull(regularComputer);

        assertDoesNotThrow(
                () -> bridge.taskAccepted(regularComputer.getExecutors().get(0), null));
        assertDoesNotThrow(
                () -> bridge.taskCompleted(regularComputer.getExecutors().get(0), null, 5L));
        assertDoesNotThrow(() ->
                bridge.taskCompletedWithProblems(regularComputer.getExecutors().get(0), null, 5L, null));
    }

    @Test
    void testDescriptorDisplayName() {
        Descriptor<RetentionStrategy<?>> descriptor = new ProxmoxRetentionStrategy.DescriptorImpl();
        assertEquals("Proxmox VM Retention Strategy", descriptor.getDisplayName());
    }

    @Test
    @WithJenkins
    void testResolveCloudReturnsNullWhenNamedCloudIsNotProxmoxCloud(JenkinsRule j) throws Exception {
        Cloud nonProxmoxCloud = new Cloud("plain-cloud") {
            @Override
            public boolean canProvision(hudson.model.Label label) {
                return false;
            }

            @Override
            public java.util.Collection<hudson.slaves.NodeProvisioner.PlannedNode> provision(
                    hudson.model.Label label, int excessWorkload) {
                return java.util.Collections.emptyList();
            }
        };
        j.jenkins.clouds.add(nonProxmoxCloud);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("plain-cloud", "vm-999", 5, 0, 0);
        ProxmoxNode node = createNode("agent-non-proxmox-cloud", strategy);
        j.jenkins.addNode(node);

        SlaveComputer computer = (SlaveComputer) node.toComputer();
        assertNotNull(computer);

        long result = strategy.check(computer);

        assertEquals(1L, result);
        assertNotNull(j.jenkins.getNode("agent-non-proxmox-cloud"));
    }
}
