package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Node;
import hudson.slaves.JNLPLauncher;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link ProxmoxNode}.
 * Requires a Jenkins context because {@code ProxmoxNode} extends {@code Slave}.
 */
@WithJenkins
class ProxmoxNodeTest {

    private ProxmoxNode buildNode(JenkinsRule j, String name, int maxBuilds) throws Exception {
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy(null, "vm-" + name, 5, 0, maxBuilds);
        ProxmoxNode node = new ProxmoxNode(
                name,
                "Test node " + name,
                "/home/jenkins",
                1,
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                retention,
                maxBuilds);
        j.jenkins.addNode(node);
        return node;
    }

    @Test
    void testGetDisplayNameNoMaxBuilds(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-no-max", 0);
        // Without max builds, display name is just the node name
        assertEquals("agent-no-max", node.getDisplayName());
    }

    @Test
    void testGetDisplayNameWithMaxBuilds(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-max", 3);
        // With max builds, display name includes remaining count
        assertTrue(
                node.getDisplayName().contains("3 builds remaining"),
                "Expected '3 builds remaining' in: " + node.getDisplayName());
    }

    @Test
    void testGetBuildsRemainingInitialValue(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-remaining", 5);
        assertEquals(5, node.getBuildsRemaining());
    }

    @Test
    void testGetBuildsRemainingNoMaxBuilds(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-unlimited", 0);
        // When no max builds configured, buildsRemaining is -1 (unlimited)
        assertEquals(-1, node.getBuildsRemaining());
    }

    @Test
    void testDecrementBuildsRemaining(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-decr", 3);
        assertEquals(3, node.getBuildsRemaining());

        int after1 = node.decrementBuildsRemaining();
        assertEquals(2, after1);
        assertEquals(2, node.getBuildsRemaining());

        node.decrementBuildsRemaining();
        node.decrementBuildsRemaining();
        // Should not go below zero
        assertEquals(0, node.getBuildsRemaining());

        // Extra decrement stays at 0
        int afterZero = node.decrementBuildsRemaining();
        assertEquals(0, afterZero);
    }

    @Test
    void testSetBuildsRemainingClampedToMax(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-set", 4);
        // Setting above max should be clamped to max
        node.setBuildsRemaining(10);
        assertEquals(4, node.getBuildsRemaining());
    }

    @Test
    void testSetBuildsRemainingClampedToZero(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-set-zero", 4);
        // Setting negative is clamped to 0
        node.setBuildsRemaining(-1);
        assertEquals(0, node.getBuildsRemaining());
    }

    @Test
    void testSetBuildsRemainingNoMaxBuildsSetToUnlimited(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-set-unlimited", 0);
        // When no max builds, setBuildsRemaining always results in -1
        node.setBuildsRemaining(5);
        assertEquals(-1, node.getBuildsRemaining());
    }

    @Test
    void testGetMaxBuildsPerAgent(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-mb", 7);
        assertEquals(7, node.getMaxBuildsPerAgent());
    }

    @Test
    void testCreateComputer(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-computer", 0);
        assertNotNull(node.createComputer());
        assertInstanceOf(ProxmoxNodeComputer.class, node.createComputer());
    }

    @Test
    void testAsNode(JenkinsRule j) throws Exception {
        ProxmoxNode node = buildNode(j, "agent-asnode", 0);
        assertSame(node, node.asNode());
    }

    @Test
    void testGetEffectiveMaxBuildsFromRetentionStrategy(JenkinsRule j) throws Exception {
        // ProxmoxNode with maxBuildsPerAgent=0, but retention strategy has maxBuildsPerAgent=5
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy(null, "vm-effective", 5, 0, 5);
        ProxmoxNode node = new ProxmoxNode(
                "agent-effective",
                "desc",
                "/home/jenkins",
                1,
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                retention,
                0 /* maxBuildsPerAgent from constructor arg */);
        j.jenkins.addNode(node);

        // getEffectiveMaxBuildsPerAgent should take the max of constructor arg (0) and retention strategy (5)
        assertEquals(5, node.getEffectiveMaxBuildsPerAgent());
    }

    @Test
    void testStringExecutorParsing(JenkinsRule j) throws Exception {
        // Test the DataBoundConstructor variant that parses executor count from string
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy();
        ProxmoxNode node = new ProxmoxNode(
                "agent-exec-str",
                "desc",
                "/home/jenkins",
                "2",
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                retention,
                Collections.emptyList());
        j.jenkins.addNode(node);
        assertEquals(2, node.getNumExecutors());
    }

    @Test
    void testStringExecutorParsingInvalid(JenkinsRule j) throws Exception {
        // Invalid executor string should default to 1
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy();
        ProxmoxNode node = new ProxmoxNode(
                "agent-exec-bad",
                "desc",
                "/home/jenkins",
                "not-a-number",
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                retention,
                Collections.emptyList());
        j.jenkins.addNode(node);
        assertEquals(1, node.getNumExecutors());
    }
}



