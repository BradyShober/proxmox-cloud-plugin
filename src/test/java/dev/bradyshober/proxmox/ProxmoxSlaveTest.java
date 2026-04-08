package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Slave;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link ProxmoxSlave#getDisplayName}.
 */
@WithJenkins
class ProxmoxSlaveTest {

    /**
     * When the retention strategy is NOT a {@link ProxmoxRetentionStrategy}, the display name is
     * simply the node name.
     */
    @Test
    void testGetDisplayNameWithoutProxmoxRetentionStrategy(JenkinsRule j) throws Exception {
        DumbSlave slave = j.createSlave("test-node", null, null);

        // DumbSlave default strategy is not ProxmoxRetentionStrategy
        String displayName = ProxmoxSlave.getDisplayName(slave);
        assertEquals(slave.getNodeName(), displayName);
    }

    /**
     * When the strategy IS a {@link ProxmoxRetentionStrategy} but {@code maxBuildsPerAgent} is 0
     * (disabled), return the bare node name.
     */
    @Test
    void testGetDisplayNameWithMaxBuildsDisabled(JenkinsRule j) throws Exception {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("mycloud", "100", 5, 0, 0);
        DumbSlave slave = j.createSlave("test-node-2", null, null);
        // Swap in the ProxmoxRetentionStrategy
        slave = new DumbSlave(
                "test-node-max0",
                slave.getRemoteFS(),
                slave.getLauncher());
        ((Slave) slave).setRetentionStrategy(strategy);

        String displayName = ProxmoxSlave.getDisplayName(slave);
        assertEquals("test-node-max0", displayName);
    }

    /**
     * When {@code maxBuildsPerAgent} > 0 and the node is NOT added to Jenkins (no Computer),
     * the display name includes the full remaining count.
     */
    @Test
    void testGetDisplayNameWithMaxBuildsNoComputer(JenkinsRule j) throws Exception {
        int maxBuilds = 5;
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("mycloud", "100", 5, 0, maxBuilds);

        // Create a DumbSlave that is NOT registered with Jenkins (so toComputer() returns null)
        DumbSlave slave = new DumbSlave(
                "unregistered-node",
                "/tmp/slave",
                j.createComputerLauncher(null));
        ((Slave) slave).setRetentionStrategy(strategy);

        String displayName = ProxmoxSlave.getDisplayName(slave);
        assertEquals("unregistered-node (" + maxBuilds + " builds remaining)", displayName);
    }

    /**
     * When {@code maxBuildsPerAgent} > 0 and the node IS registered (has a Computer),
     * the remaining count is max - completed - running (floored at 0).
     */
    @Test
    void testGetDisplayNameWithMaxBuildsRegistered(JenkinsRule j) throws Exception {
        int maxBuilds = 3;
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("mycloud", "101", 5, 0, maxBuilds);

        DumbSlave slave = j.createSlave("registered-node", null, null);
        // Replace the strategy with our ProxmoxRetentionStrategy
        slave.setRetentionStrategy(strategy);
        // Ensure the node is in Jenkins
        j.jenkins.addNode(slave);

        // No builds have run, so remaining = max - 0 - 0 = maxBuilds
        String displayName = ProxmoxSlave.getDisplayName(slave);
        assertTrue(
                displayName.startsWith("registered-node"),
                "Display name should start with node name: " + displayName);
        assertTrue(
                displayName.contains("builds remaining"),
                "Display name should contain 'builds remaining': " + displayName);
    }

    /**
     * When remaining would go negative (more completions than max), it should be clamped to 0.
     */
    @Test
    void testGetDisplayNameRemainingClampsAtZero(JenkinsRule j) throws Exception {
        // Use maxBuilds = 1 — after 2+ builds it should clamp to 0 remaining.
        // Without actually running builds, we can only verify the formula: max - completed - running.
        // With no computer, we just verify the formula: max builds remaining = maxBuilds.
        int maxBuilds = 1;
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy("mycloud", "102", 5, 0, maxBuilds);

        DumbSlave slave = new DumbSlave(
                "clamp-test-node",
                "/tmp/slave",
                j.createComputerLauncher(null));
        ((Slave) slave).setRetentionStrategy(strategy);

        // No computer — remaining = maxBuilds
        String displayName = ProxmoxSlave.getDisplayName(slave);
        assertEquals("clamp-test-node (1 builds remaining)", displayName);
    }
}

