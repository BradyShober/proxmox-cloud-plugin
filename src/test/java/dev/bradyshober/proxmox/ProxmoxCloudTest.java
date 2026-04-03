package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.gson.JsonObject;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Basic unit tests for ProxmoxCloud plugin.
 */
public class ProxmoxCloudTest {

    private ProxmoxServerConfig serverConfig;
    private ProxmoxAgentTemplate agentTemplate;
    private ProxmoxCloud proxmoxCloud;

    @BeforeEach
    public void setUp() {
        serverConfig = new ProxmoxServerConfig(
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                true, // Validate SSL by default
                "pve");

        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        agentTemplate = new ProxmoxAgentTemplate(
                "100", // Template VM ID
                "proxmox-agent",
                1,
                5, // Max instances
                launcher,
                "jenkins",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB jenkins@controller",
                "proxmox",
                "/home/jenkins",
                7);

        proxmoxCloud = new ProxmoxCloud(
                "Proxmox",
                serverConfig.getHost(),
                serverConfig.getApiTokenCredentialId(),
                !serverConfig.isVerifySsl(),
                serverConfig.getNode(),
                agentTemplate.getTemplateVmId(),
                agentTemplate.getAgentNameTemplate(),
                agentTemplate.getMinInstances(),
                agentTemplate.getMaxInstances(),
                agentTemplate.getIdleMinutesBeforeTermination(),
                agentTemplate.getLauncher(),
                agentTemplate.getSshUsername(),
                agentTemplate.getSshPublicKey(),
                agentTemplate.getLabels(),
                agentTemplate.getRemoteFsRoot());
    }

    @Test
    public void testProxmoxServerConfigCreation() {
        assertNotNull(serverConfig);
        assertEquals("https://proxmox.example.com:8006", serverConfig.getHost());
        assertEquals("proxmox-api-token", serverConfig.getApiTokenCredentialId());
        assertTrue(serverConfig.isVerifySsl());
    }

    @Test
    public void testProxmoxAgentTemplateCreation() {
        assertNotNull(agentTemplate);
        assertEquals("100", agentTemplate.getTemplateVmId());
        assertEquals("proxmox-agent", agentTemplate.getAgentNameTemplate());
        assertEquals(1, agentTemplate.getMinInstances());
        assertEquals(5, agentTemplate.getMaxInstances());
        assertInstanceOf(JNLPLauncher.class, agentTemplate.getLauncher());
        assertEquals("jenkins", agentTemplate.getSshUsername());
        assertEquals("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB jenkins@controller", agentTemplate.getSshPublicKey());
        assertEquals("proxmox", agentTemplate.getLabels());
        assertEquals("/home/jenkins", agentTemplate.getRemoteFsRoot());
        assertEquals(7, agentTemplate.getIdleMinutesBeforeTermination());
    }

    @Test
    public void testProxmoxCloudCreation() {
        assertNotNull(proxmoxCloud);
        assertEquals("Proxmox", proxmoxCloud.getDisplayName());
        assertNotNull(proxmoxCloud.getServerConfig());
        assertNotNull(proxmoxCloud.getAgentTemplate());
        assertEquals("proxmox-api-token", proxmoxCloud.getApiTokenCredentialId());
        assertEquals("https://proxmox.example.com:8006", proxmoxCloud.getHost());
        assertEquals("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB jenkins@controller", proxmoxCloud.getSshPublicKey());
        assertEquals("/home/jenkins", proxmoxCloud.getRemoteFsRoot());
        assertEquals(1, proxmoxCloud.getMinInstances());
        assertEquals(7, proxmoxCloud.getIdleMinutesBeforeTermination());
        assertFalse(proxmoxCloud.isSkipTlsVerification());
        assertTrue(proxmoxCloud.getInstances().isEmpty());
    }

    @Test
    public void testCanProvisionWithMatchingLabel() {
        Label label = new LabelAtom("proxmox");
        assertTrue(proxmoxCloud.canProvision(label), "Cloud should provision for matching label");
    }

    @Test
    public void testCanProvisionWithNonMatchingLabel() {
        Label label = new LabelAtom("docker");
        assertFalse(proxmoxCloud.canProvision(label), "Cloud should not provision for non-matching label");
    }

    @Test
    public void testCanProvisionWithNullLabel() {
        assertTrue(proxmoxCloud.canProvision((Label) null), "Cloud should provision when label is null");
    }

    @Test
    public void testCanProvisionWithMultipleLabels() {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud multiLabelCloud = new ProxmoxCloud(
                "MultiLabel",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox linux docker",
                "/home/jenkins");

        assertTrue(multiLabelCloud.canProvision(new LabelAtom("proxmox")));
        assertTrue(multiLabelCloud.canProvision(new LabelAtom("linux")));
        assertTrue(multiLabelCloud.canProvision(new LabelAtom("docker")));
        assertFalse(multiLabelCloud.canProvision(new LabelAtom("windows")));
    }

    @Test
    public void testProxmoxInstanceCreation() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "proxmox-agent-1");
        assertNotNull(instance);
        assertEquals("100", instance.getVmId());
        assertEquals("proxmox-agent-1", instance.getAgentName());
        assertEquals(ProxmoxInstance.InstanceState.PROVISIONING, instance.getState());
    }

    @Test
    public void testProxmoxInstanceStateTransitions() {
        ProxmoxInstance instance = new ProxmoxInstance("100", "proxmox-agent-1");

        instance.setState(ProxmoxInstance.InstanceState.STARTING);
        assertEquals(ProxmoxInstance.InstanceState.STARTING, instance.getState());

        instance.setState(ProxmoxInstance.InstanceState.RUNNING);
        assertEquals(ProxmoxInstance.InstanceState.RUNNING, instance.getState());

        instance.setState(ProxmoxInstance.InstanceState.STOPPING);
        assertEquals(ProxmoxInstance.InstanceState.STOPPING, instance.getState());
    }

    @Test
    public void testTaskCompletionAcceptsStoppedOkWithoutEndTime() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "stopped");
        data.addProperty("exitstatus", "OK");

        assertTrue(ProxmoxClient.isCompletedTaskStatus(data));
    }

    @Test
    public void testTaskCompletionAcceptsNonNullEndTime() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "running");
        data.addProperty("endtime", "1742907421");

        assertTrue(ProxmoxClient.isCompletedTaskStatus(data));
    }

    @Test
    public void testTaskCompletionRejectsRunningTaskWithoutEndTime() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "running");

        assertFalse(ProxmoxClient.isCompletedTaskStatus(data));
    }

    @Test
    @WithJenkins
    public void testBuildDumbSlaveUsesInboundWebSocketLauncher(JenkinsRule jenkinsRule) throws Exception {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud websocketCloud = new ProxmoxCloud(
                "WebSocketCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        DumbSlave slave = buildDumbSlave(websocketCloud, "proxmox-agent-1", "101", null);
        ComputerLauncher nodeLauncher = slave.getLauncher();

        assertInstanceOf(JNLPLauncher.class, nodeLauncher);
        assertTrue(((JNLPLauncher) nodeLauncher).isWebSocket(), "Launcher should use WebSocket for inbound mode");
        assertEquals("/home/jenkins", slave.getRemoteFS());
    }

    @Test
    @WithJenkins
    public void testBuildDumbSlaveUsesNativeSshLauncherForSshTemplate(JenkinsRule jenkinsRule) throws Exception {
        SSHLauncher launcher = new SSHLauncher("0.0.0.0", 22, "ssh-credential-id");

        ProxmoxCloud sshCloud = new ProxmoxCloud(
                "SshCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        DumbSlave slave = buildDumbSlave(sshCloud, "proxmox-agent-1", "101", "192.0.2.10");

        assertInstanceOf(SSHLauncher.class, slave.getLauncher());
        assertEquals("192.0.2.10", ((SSHLauncher) slave.getLauncher()).getHost());
        assertEquals("/home/jenkins", slave.getRemoteFS());
    }

    @Test
    @WithJenkins
    public void testResolveApiTokenFromSecretTextCredential(JenkinsRule jenkinsRule) throws Exception {
        addSecretTextCredential(jenkinsRule, "proxmox-api-token", "user@pam!tokenid=token-secret");

        assertEquals("user@pam!tokenid=token-secret", ProxmoxClient.resolveApiToken("proxmox-api-token"));
    }

    @Test
    @WithJenkins
    public void testResolveApiTokenRejectsBlankCredentialId(JenkinsRule jenkinsRule) {
        IOException exception = assertThrows(IOException.class, () -> ProxmoxClient.resolveApiToken(" "));

        assertTrue(exception.getMessage().contains("must be configured"));
    }

    @Test
    @WithJenkins
    public void testResolveApiTokenRejectsMissingCredential(JenkinsRule jenkinsRule) {
        IOException exception =
                assertThrows(IOException.class, () -> ProxmoxClient.resolveApiToken("missing-credential"));

        assertTrue(exception.getMessage().contains("Unable to find secret text credential"));
    }

    @Test
    @WithJenkins
    public void testDescriptorListsSecretTextCredential(JenkinsRule jenkinsRule) throws Exception {
        addSecretTextCredential(jenkinsRule, "proxmox-api-token", "user@pam!tokenid=token-secret");

        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);
        ListBoxModel items = descriptor.doFillApiTokenCredentialIdItems(null);

        assertTrue(items.stream().anyMatch(option -> "proxmox-api-token".equals(option.value)));
    }

    @Test
    @WithJenkins
    public void testDescriptorValidatesSecretTextCredentialSelection(JenkinsRule jenkinsRule) throws Exception {
        addSecretTextCredential(jenkinsRule, "proxmox-api-token", "user@pam!tokenid=token-secret");

        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckApiTokenCredentialId("proxmox-api-token").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiTokenCredentialId("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiTokenCredentialId("missing-credential").kind);
    }

    @Test
    @WithJenkins
    public void testNodeConfigRoundTripBindsProxmoxRetentionStrategy(JenkinsRule jenkinsRule) throws Exception {
        DumbSlave slave = buildDumbSlave(proxmoxCloud, "proxmox-agent-1", "101", null);
        jenkinsRule.jenkins.addNode(slave);

        DumbSlave reconfigured = (DumbSlave) jenkinsRule.configRoundtrip(slave);
        assertInstanceOf(ProxmoxRetentionStrategy.class, reconfigured.getRetentionStrategy());

        ProxmoxRetentionStrategy retention = (ProxmoxRetentionStrategy) reconfigured.getRetentionStrategy();
        assertEquals("Proxmox", retention.getCloudName());
        assertEquals("101", retention.getVmId());
        assertEquals(7, retention.getIdleMinutes());
    }

    @Test
    @WithJenkins
    public void testCanTerminateVmForScaleDownRespectsMinInstancesFloor(JenkinsRule jenkinsRule) throws Exception {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud minCloud = new ProxmoxCloud(
                "FloorCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                1,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        DumbSlave slave1 = buildDumbSlave(minCloud, "floor-agent-1", "201", null);
        jenkinsRule.jenkins.addNode(slave1);

        // Only 1 node; at the floor – should NOT allow termination.
        assertFalse(minCloud.canTerminateVmForScaleDown("201"));

        DumbSlave slave2 = buildDumbSlave(minCloud, "floor-agent-2", "202", null);
        jenkinsRule.jenkins.addNode(slave2);

        // 2 nodes with floor=1 – can terminate one.
        assertTrue(minCloud.canTerminateVmForScaleDown("202"));
    }

    @Test
    @WithJenkins
    public void testCanTerminateVmForScaleDownAllowsTerminationWhenMinIsZero(JenkinsRule jenkinsRule) throws Exception {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud noFloorCloud = new ProxmoxCloud(
                "NoFloorCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0, // minInstances = 0 → no floor
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        DumbSlave slave = buildDumbSlave(noFloorCloud, "solo-agent", "301", null);
        jenkinsRule.jenkins.addNode(slave);

        // minInstances=0 → always allow termination even with only 1 node.
        assertTrue(noFloorCloud.canTerminateVmForScaleDown("301"));
    }

    @Test
    @WithJenkins
    public void testCountLiveCloudNodesReturnsCorrectCount(JenkinsRule jenkinsRule) throws Exception {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud cloudA = new ProxmoxCloud(
                "CloudA",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                2,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        ProxmoxCloud cloudB = new ProxmoxCloud(
                "CloudB",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        assertEquals(0, cloudA.countLiveCloudNodes());

        jenkinsRule.jenkins.addNode(buildDumbSlave(cloudA, "a-agent-1", "401", null));
        jenkinsRule.jenkins.addNode(buildDumbSlave(cloudA, "a-agent-2", "402", null));
        jenkinsRule.jenkins.addNode(buildDumbSlave(cloudB, "b-agent-1", "403", null));

        // cloudA has 2 nodes, cloudB has 1; counts should not bleed across clouds.
        assertEquals(2, cloudA.countLiveCloudNodes());
        assertEquals(1, cloudB.countLiveCloudNodes());
    }

    @Test
    @WithJenkins
    public void testReconcileMinInstancesDoesNothingWhenAtOrAboveMinimum(JenkinsRule jenkinsRule) throws Exception {
        JNLPLauncher launcher = new JNLPLauncher();
        launcher.setWebSocket(true);

        ProxmoxCloud minCloud = new ProxmoxCloud(
                "ReconcileCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                2,
                5,
                5,
                launcher,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins");

        jenkinsRule.jenkins.addNode(buildDumbSlave(minCloud, "r-agent-1", "501", null));
        jenkinsRule.jenkins.addNode(buildDumbSlave(minCloud, "r-agent-2", "502", null));

        assertEquals(2, minCloud.countLiveCloudNodes());

        // reconcileMinInstances with 2 live nodes >= min 2 should return without provisioning.
        // It would fail trying to connect to Proxmox if it actually tried to provision – so no exception = success.
        minCloud.reconcileMinInstances();

        // Count is unchanged.
        assertEquals(2, minCloud.countLiveCloudNodes());
    }

    private void addSecretTextCredential(JenkinsRule jenkinsRule, String id, String secret) throws Exception {
        StringCredentialsImpl credentials =
                new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "test credential", Secret.fromString(secret));
        Objects.requireNonNull(CredentialsProvider.lookupStores(jenkinsRule.jenkins)
                        .iterator()
                        .next())
                .addCredentials(Domain.global(), credentials);
    }

    private DumbSlave buildDumbSlave(ProxmoxCloud cloud, String agentName, String vmId, String ipAddress)
            throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod("buildDumbSlave", String.class, String.class, String.class);
        method.setAccessible(true);
        return (DumbSlave) method.invoke(cloud, agentName, vmId, ipAddress);
    }
}
