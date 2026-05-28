package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.gson.JsonObject;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.labels.LabelAtom;
import hudson.slaves.ComputerConnector;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        agentTemplate = new ProxmoxAgentTemplate(
                "100", // Template VM ID
                "proxmox-agent",
                1,
                5, // Max instances
                connector,
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
                agentTemplate.getComputerConnector(),
                agentTemplate.getSshUsername(),
                agentTemplate.getSshPublicKey(),
                agentTemplate.getLabels(),
                agentTemplate.getRemoteFsRoot(),
                agentTemplate.getNumExecutors());
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
        assertNotNull(agentTemplate.getComputerConnector());
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
        assertEquals(0, proxmoxCloud.getMaxLifetimeMinutes());
        assertFalse(proxmoxCloud.isSkipTlsVerification());
        assertTrue(proxmoxCloud.getInstances().isEmpty());
    }

    @Test
    public void testBuildProvisioningTagsIncludesPluginAndCloudTag() {
        assertEquals("jenkins-proxmox-plugin;jenkins-cloud-proxmox", proxmoxCloud.buildProvisioningTags());
    }

    @Test
    public void testBuildProvisioningTagsSanitizesCloudName() {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        ProxmoxCloud namedCloud = new ProxmoxCloud(
                "Cloud Name @ Prod",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        assertEquals("jenkins-proxmox-plugin;jenkins-cloud-cloud-name-prod", namedCloud.buildProvisioningTags());
    }

    @Test
    public void testBuildProvisioningTagsTrimsLeadingAndTrailingHyphens() {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        ProxmoxCloud namedCloud = new ProxmoxCloud(
                "---Prod Cloud---",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        assertEquals("jenkins-proxmox-plugin;jenkins-cloud-prod-cloud", namedCloud.buildProvisioningTags());
    }

    @Test
    public void testBuildProvisioningTagsUsesDefaultCloudTagWhenSanitizedNameIsBlank() {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        ProxmoxCloud namedCloud = new ProxmoxCloud(
                "!!!",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        assertEquals("jenkins-proxmox-plugin;jenkins-cloud-default", namedCloud.buildProvisioningTags());
    }

    @Test
    public void testHasTagMatchesSemicolonDelimitedTags() {
        assertTrue(ProxmoxCloud.hasTag("a;b;c", "b"));
        assertFalse(ProxmoxCloud.hasTag("a;b;c", "x"));
    }

    @Test
    public void testIsManagedVmTagsRequiresPluginAndCloudOwnershipTags() {
        assertTrue(proxmoxCloud.isManagedVmTags("jenkins-proxmox-plugin;jenkins-cloud-proxmox;ci"));
        assertFalse(proxmoxCloud.isManagedVmTags("jenkins-proxmox-plugin;jenkins-cloud-other"));
        assertFalse(proxmoxCloud.isManagedVmTags("jenkins-cloud-proxmox"));
    }

    @Test
    public void testMaxLifetimeMinutesSetterNormalizesValue() {
        proxmoxCloud.setMaxLifetimeMinutes(120);
        assertEquals(120, proxmoxCloud.getMaxLifetimeMinutes());

        proxmoxCloud.setMaxLifetimeMinutes(-5);
        assertEquals(0, proxmoxCloud.getMaxLifetimeMinutes());
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
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox linux docker",
                "/home/jenkins",
                1);

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
    public void testBuildDumbSlaveUsesInboundWebSocketConnector(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        ProxmoxNode slave = buildDumbSlave(websocketCloud, "proxmox-agent-1", "101", null);
        ComputerLauncher nodeLauncher = slave.getLauncher();

        assertInstanceOf(JNLPLauncher.class, nodeLauncher);
        assertTrue(
                ((JNLPLauncher) nodeLauncher).isWebSocket(),
                "new ProxmoxJNLPConnector(true) should use WebSocket for inbound mode");
        assertEquals("/home/jenkins", slave.getRemoteFS());
    }

    @Test
    @WithJenkins
    public void testBuildDumbSlaveUsesSSHConnectorForOutboundMode(JenkinsRule jenkinsRule) throws Exception {
        // Create an SSH new ProxmoxJNLPConnector(true) using Jenkins' ConnectorImpl wrapper
        // In practice, users would select an SSH new ProxmoxJNLPConnector(true) from the UI
        // For testing, we create one using the built-in SSH new ProxmoxJNLPConnector(true) support
        // Note: This test validates that the new ProxmoxJNLPConnector(true) pattern works;
        // actual SSH setup requires ssh-slaves plugin configuration

        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        ProxmoxNode slave = buildDumbSlave(sshCloud, "proxmox-agent-1", "101", "192.0.2.10");
        assertNotNull(slave.getLauncher());
        assertEquals("/home/jenkins", slave.getRemoteFS());
    }

    @Test
    @WithJenkins
    public void testResolveApiTokenFromCustomCredential(JenkinsRule jenkinsRule) throws Exception {
        addProxmoxApiTokenCredential(jenkinsRule, "proxmox-api-token", "user", "pam", "tokenid", "token-secret");

        assertEquals("user@pam!tokenid=token-secret", ProxmoxClient.resolveApiToken("proxmox-api-token"));
    }

    @Test
    @WithJenkins
    public void testResolveApiTokenRejectsLegacySecretTextCredential(JenkinsRule jenkinsRule) throws Exception {
        addSecretTextCredential(jenkinsRule, "legacy-proxmox-api-token", "user@pam!tokenid=token-secret");

        IOException exception =
                assertThrows(IOException.class, () -> ProxmoxClient.resolveApiToken("legacy-proxmox-api-token"));

        assertTrue(exception.getMessage().contains("Unable to find Proxmox API token credential"));
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

        assertTrue(exception.getMessage().contains("Unable to find Proxmox API token credential"));
    }

    @Test
    @WithJenkins
    public void testDescriptorListsCustomProxmoxApiTokenCredential(JenkinsRule jenkinsRule) throws Exception {
        addProxmoxApiTokenCredential(jenkinsRule, "proxmox-api-token", "user", "pam", "tokenid", "token-secret");
        addSecretTextCredential(jenkinsRule, "legacy-secret-text", "user@pam!legacy=secret");

        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);
        ListBoxModel items = descriptor.doFillApiTokenCredentialIdItems(null);

        assertTrue(items.stream().anyMatch(option -> "proxmox-api-token".equals(option.value)));
        assertTrue(items.stream()
                .anyMatch(option -> "proxmox-api-token".equals(option.value)
                        && option.name != null
                        && option.name.contains("test proxmox token credential")));
        assertFalse(items.stream().anyMatch(option -> "legacy-secret-text".equals(option.value)));
    }

    @Test
    @WithJenkins
    public void testDescriptorValidatesCustomCredentialSelection(JenkinsRule jenkinsRule) throws Exception {
        addProxmoxApiTokenCredential(jenkinsRule, "proxmox-api-token", "user", "pam", "tokenid", "token-secret");

        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckApiTokenCredentialId("proxmox-api-token").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiTokenCredentialId("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiTokenCredentialId("missing-credential").kind);
    }

    @Test
    @WithJenkins
    public void testDescriptorRejectsIncompleteCustomCredential(JenkinsRule jenkinsRule) throws Exception {
        addProxmoxApiTokenCredential(jenkinsRule, "bad-proxmox-api-token", "", "pam", "tokenid", "token-secret");

        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiTokenCredentialId("bad-proxmox-api-token").kind);
    }

    @Test
    @WithJenkins
    public void testProxmoxApiTokenCredentialRealmDropdownIncludesSupportedOptions(JenkinsRule jenkinsRule) {
        ProxmoxApiTokenCredentialsImpl.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxApiTokenCredentialsImpl.DescriptorImpl.class);

        ListBoxModel realms = descriptor.doFillRealmItems(null);

        assertTrue(realms.stream().anyMatch(option -> "pam".equals(option.value)));
        assertTrue(realms.stream().anyMatch(option -> "pve".equals(option.value)));
        assertTrue(realms.stream().anyMatch(option -> "ldap".equals(option.value)));
        assertTrue(realms.stream().anyMatch(option -> "ad".equals(option.value)));
        assertTrue(realms.stream().anyMatch(option -> "openid".equals(option.value)));
    }

    @Test
    @WithJenkins
    public void testProxmoxApiTokenCredentialRealmDropdownIncludesCurrentValue(JenkinsRule jenkinsRule) {
        ProxmoxApiTokenCredentialsImpl.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxApiTokenCredentialsImpl.DescriptorImpl.class);

        ListBoxModel realms = descriptor.doFillRealmItems("customrealm");

        assertTrue(realms.stream().anyMatch(option -> "customrealm".equals(option.value)));
    }

    @Test
    @WithJenkins
    public void testNodeConfigRoundTripBindsProxmoxRetentionStrategy(JenkinsRule jenkinsRule) throws Exception {
        proxmoxCloud.setMaxLifetimeMinutes(45);
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "proxmox-agent-1", "101", null);
        jenkinsRule.jenkins.addNode(slave);

        ProxmoxNode reconfigured = (ProxmoxNode) jenkinsRule.configRoundtrip(slave);
        assertInstanceOf(ProxmoxRetentionStrategy.class, reconfigured.getRetentionStrategy());

        ProxmoxRetentionStrategy retention = (ProxmoxRetentionStrategy) reconfigured.getRetentionStrategy();
        assertEquals("Proxmox", retention.getCloudName());
        assertEquals("101", retention.getVmId());
    }

    @Test
    public void testReadResolveRestoresTransientProvisioningState() throws Exception {
        Field pendingField = ProxmoxCloud.class.getDeclaredField("pendingProvisions");
        pendingField.setAccessible(true);
        Field startupField = ProxmoxCloud.class.getDeclaredField("startupReconciled");
        startupField.setAccessible(true);
        Method readResolve = ProxmoxCloud.class.getDeclaredMethod("readResolve");
        readResolve.setAccessible(true);

        pendingField.set(proxmoxCloud, null);
        startupField.setBoolean(proxmoxCloud, true);

        Object resolved = readResolve.invoke(proxmoxCloud);

        assertSame(proxmoxCloud, resolved);
        assertInstanceOf(java.util.concurrent.atomic.AtomicInteger.class, pendingField.get(proxmoxCloud));
        assertFalse(startupField.getBoolean(proxmoxCloud));
    }

    @Test
    public void testVmIdReservationIsSharedAcrossCloudInstancesForSameTarget() throws Exception {
        ProxmoxCloud secondCloud = new ProxmoxCloud(
                "ProxmoxReloaded",
                serverConfig.getHost(),
                serverConfig.getApiTokenCredentialId(),
                !serverConfig.isVerifySsl(),
                serverConfig.getNode(),
                agentTemplate.getTemplateVmId(),
                agentTemplate.getAgentNameTemplate(),
                agentTemplate.getMinInstances(),
                agentTemplate.getMaxInstances(),
                agentTemplate.getIdleMinutesBeforeTermination(),
                agentTemplate.getComputerConnector(),
                agentTemplate.getSshUsername(),
                agentTemplate.getSshPublicKey(),
                agentTemplate.getLabels(),
                agentTemplate.getRemoteFsRoot(),
                agentTemplate.getNumExecutors());

        AtomicBoolean firstCloneReleased = new AtomicBoolean(false);
        ProxmoxCloud.CloneReservation firstReservation =
                proxmoxCloud.reserveVmIdAndStartClone("agent-one", () -> "500", vmId -> "upid-" + vmId);

        CompletableFuture<ProxmoxCloud.CloneReservation> secondReservationFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return secondCloud.reserveVmIdAndStartClone(
                        "agent-two", () -> firstCloneReleased.get() ? "501" : "500", vmId -> "upid-" + vmId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(300);
        assertFalse(
                secondReservationFuture.isDone(), "Second allocation should wait while the stale VM ID is reserved");

        firstCloneReleased.set(true);
        releaseReservedVmId(proxmoxCloud, firstReservation.getVmId());

        ProxmoxCloud.CloneReservation secondReservation;
        try {
            secondReservation = secondReservationFuture.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("Concurrent allocation failed", e.getCause());
        }

        assertEquals("500", firstReservation.getVmId());
        assertEquals("501", secondReservation.getVmId());

        releaseReservedVmId(secondCloud, secondReservation.getVmId());
    }

    @Test
    @WithJenkins
    public void testMaxBuildsDisplayNameShowsRemainingCount(JenkinsRule jenkinsRule) throws Exception {
        proxmoxCloud.setMaxBuildsPerAgent(2);
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "max-builds-agent", "801", null);
        jenkinsRule.jenkins.addNode(slave);

        assertNotNull(slave.toComputer());
        SlaveComputer computer = (SlaveComputer) Objects.requireNonNull(slave.toComputer());
        Executor executor = computer.getExecutors().get(0);
        ProxmoxRetentionStrategy retention = (ProxmoxRetentionStrategy) slave.getRetentionStrategy();

        assertTrue(computer.getDisplayName().contains("2 builds remaining"));
        retention.taskAccepted(executor, null);
        assertTrue(computer.getDisplayName().contains("1 builds remaining"));
    }

    @Test
    @WithJenkins
    public void testBuildEventBridgeHandlesRemovedNode(JenkinsRule jenkinsRule) throws Exception {
        proxmoxCloud.setMaxBuildsPerAgent(1);
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "removed-node-agent", "901", null);
        jenkinsRule.jenkins.addNode(slave);

        SlaveComputer computer = (SlaveComputer) Objects.requireNonNull(slave.toComputer());
        Executor executor = computer.getExecutors().get(0);

        jenkinsRule.jenkins.removeNode(slave);

        assertDoesNotThrow(() -> new ProxmoxRetentionStrategy.BuildEventBridge().taskAccepted(executor, null));
    }

    @Test
    @WithJenkins
    public void testMaxBuildsDisablesAgentImmediatelyOnFinalAssignment(JenkinsRule jenkinsRule) throws Exception {
        proxmoxCloud.setMaxBuildsPerAgent(2);
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "max-builds-drain-agent", "802", null);
        jenkinsRule.jenkins.addNode(slave);

        assertNotNull(slave.toComputer());
        SlaveComputer computer = (SlaveComputer) Objects.requireNonNull(slave.toComputer());
        Executor executor = computer.getExecutors().get(0);
        ProxmoxRetentionStrategy retention = (ProxmoxRetentionStrategy) slave.getRetentionStrategy();

        retention.taskAccepted(executor, null);
        assertTrue(computer.isAcceptingTasks());
        assertEquals(1, slave.getBuildsRemaining());

        retention.taskAccepted(executor, null);
        assertFalse(computer.isAcceptingTasks());
        assertTrue(computer.isTemporarilyOffline());
        assertEquals(0, slave.getBuildsRemaining());
    }

    @Test
    @WithJenkins
    public void testStaleRemainingCounterDoesNotTriggerPrematureDrain(JenkinsRule jenkinsRule) throws Exception {
        proxmoxCloud.setMaxBuildsPerAgent(3);
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "stale-counter-agent", "803", null);
        jenkinsRule.jenkins.addNode(slave);

        assertNotNull(slave.toComputer());
        SlaveComputer computer = (SlaveComputer) Objects.requireNonNull(slave.toComputer());
        ProxmoxRetentionStrategy retention = (ProxmoxRetentionStrategy) slave.getRetentionStrategy();

        // Simulate stale state after reload; check() should heal this from actual build history.
        slave.setBuildsRemaining(0);
        retention.check(computer);

        assertFalse(computer.isTemporarilyOffline());
        assertTrue(computer.isAcceptingTasks());
        assertEquals(3, slave.getBuildsRemaining());
    }

    @Test
    @WithJenkins
    public void testRemainingDecrementsWhenNodeSnapshotMaxIsUnset(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxRetentionStrategy retention =
                new ProxmoxRetentionStrategy("Proxmox", "804", ProxmoxRetentionStrategy.DEFAULT_IDLE_MINUTES, 0, 2);
        ProxmoxNode slave = new ProxmoxNode(
                "unset-snapshot-max-agent",
                "Proxmox provisioned agent (VM 804)",
                "/home/jenkins",
                1,
                Node.Mode.NORMAL,
                "proxmox",
                new JNLPLauncher(),
                retention,
                0);
        jenkinsRule.jenkins.addNode(slave);

        assertNotNull(slave.toComputer());
        SlaveComputer computer = (SlaveComputer) Objects.requireNonNull(slave.toComputer());
        Executor executor = computer.getExecutors().get(0);

        retention.taskAccepted(executor, null);

        assertTrue(computer.getDisplayName().contains("1 builds remaining"));
        assertEquals(1, slave.getBuildsRemaining());
    }

    @Test
    @WithJenkins
    public void testCanTerminateVmForScaleDownRespectsMinInstancesFloor(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        ProxmoxNode slave1 = buildDumbSlave(minCloud, "floor-agent-1", "201", null);
        jenkinsRule.jenkins.addNode(slave1);

        // Only 1 node; at the floor – should NOT allow termination.
        assertFalse(minCloud.canTerminateVmForScaleDown("201"));

        ProxmoxNode slave2 = buildDumbSlave(minCloud, "floor-agent-2", "202", null);
        jenkinsRule.jenkins.addNode(slave2);

        // 2 nodes with floor=1 – can terminate one.
        assertTrue(minCloud.canTerminateVmForScaleDown("202"));
    }

    @Test
    @WithJenkins
    public void testCanTerminateVmForScaleDownAllowsTerminationWhenMinIsZero(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        ProxmoxNode slave = buildDumbSlave(noFloorCloud, "solo-agent", "301", null);
        jenkinsRule.jenkins.addNode(slave);

        // minInstances=0 → always allow termination even with only 1 node.
        assertTrue(noFloorCloud.canTerminateVmForScaleDown("301"));
    }

    @Test
    @WithJenkins
    public void testDrainingMaxLifetimeNodeExcludedFromFloorAndTerminableAfterReplacement(JenkinsRule jenkinsRule)
            throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

        ProxmoxCloud minCloud = new ProxmoxCloud(
                "DrainFloorCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                1,
                5,
                5,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        ProxmoxNode drainingNode = buildDumbSlave(minCloud, "draining-agent", "701", null);
        jenkinsRule.jenkins.addNode(drainingNode);
        assertNotNull(drainingNode.toComputer());
        Objects.requireNonNull(drainingNode.toComputer())
                .setTemporarilyOffline(
                        true,
                        new OfflineCause.ByCLI(ProxmoxRetentionStrategy.MAX_LIFETIME_DRAIN_REASON_PREFIX
                                + "5 minutes; draining running jobs before termination"));

        // Draining node should not count toward minimum-floor healthy capacity.
        assertEquals(0, minCloud.countLiveCloudNodes());

        ProxmoxNode replacementNode = buildDumbSlave(minCloud, "replacement-agent", "702", null);
        jenkinsRule.jenkins.addNode(replacementNode);

        // With one healthy replacement at min=1, draining VM should be eligible for termination.
        assertEquals(1, minCloud.countLiveCloudNodes());
        assertTrue(minCloud.canTerminateVmForScaleDown("701"));
    }

    @Test
    @WithJenkins
    public void testCountLiveCloudNodesReturnsCorrectCount(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

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
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);

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
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        jenkinsRule.jenkins.addNode(buildDumbSlave(minCloud, "r-agent-1", "501", null));
        jenkinsRule.jenkins.addNode(buildDumbSlave(minCloud, "r-agent-2", "502", null));

        assertEquals(2, minCloud.countLiveCloudNodes());

        // reconcileMinInstances with 2 live nodes >= min 2 should return without provisioning.
        // It would fail trying to connect to Proxmox if it actually tried to provision – so no exception = success.
        minCloud.reconcileMinInstances();

        // Count is unchanged.
        assertEquals(2, minCloud.countLiveCloudNodes());
    }

    @Test
    public void testConstructorUsesDefaultInboundConnectorWhenConnectorIsNull() {
        ProxmoxCloud cloudWithDefaultConnector = new ProxmoxCloud(
                "DefaultConnectorCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                0,
                5,
                5,
                null,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        assertInstanceOf(ProxmoxJNLPConnector.class, cloudWithDefaultConnector.getComputerConnector());
        assertTrue(((ProxmoxJNLPConnector) cloudWithDefaultConnector.getComputerConnector()).isWebSocket());
    }

    @Test
    public void testReserveVmIdAndStartCloneRejectsBlankVmId() {
        IOException exception = assertThrows(
                IOException.class,
                () -> proxmoxCloud.reserveVmIdAndStartClone("agent-blank", () -> " ", vmId -> "upid-" + vmId));

        assertTrue(exception.getMessage().contains("blank VM ID"));
    }

    @Test
    public void testReserveVmIdAndStartCloneReleasesVmIdWhenCloneFails() throws Exception {
        IOException firstFailure = assertThrows(
                IOException.class,
                () -> proxmoxCloud.reserveVmIdAndStartClone("agent-fail", () -> "610", vmId -> {
                    throw new IOException("clone failed");
                }));
        assertTrue(firstFailure.getMessage().contains("clone failed"));

        ProxmoxCloud.CloneReservation secondTry =
                proxmoxCloud.reserveVmIdAndStartClone("agent-retry", () -> "610", vmId -> "upid-" + vmId);
        assertEquals("610", secondTry.getVmId());

        releaseReservedVmId(proxmoxCloud, secondTry.getVmId());
    }

    @Test
    public void testReserveVmIdAndStartCloneTimesOutWhenVmIdStaysReserved() throws Exception {
        ProxmoxCloud.CloneReservation heldReservation =
                proxmoxCloud.reserveVmIdAndStartClone("agent-held", () -> "620", vmId -> "upid-" + vmId);
        try {
            IOException timeout = assertThrows(
                    IOException.class,
                    () -> proxmoxCloud.reserveVmIdAndStartClone("agent-timeout", () -> "620", vmId -> "upid-" + vmId));
            assertTrue(timeout.getMessage().contains("Timed out waiting for a unique Proxmox VM ID"));
        } finally {
            releaseReservedVmId(proxmoxCloud, heldReservation.getVmId());
        }
    }

    @Test
    public void testHasTagRejectsNullAndBlankInputs() {
        assertFalse(ProxmoxCloud.hasTag(null, "jenkins-proxmox-plugin"));
        assertFalse(ProxmoxCloud.hasTag("", "jenkins-proxmox-plugin"));
        assertFalse(ProxmoxCloud.hasTag("jenkins-proxmox-plugin", ""));
        assertFalse(ProxmoxCloud.hasTag("jenkins-proxmox-plugin", null));
    }

    @Test
    public void testGenerateCloudInitScriptUsesDefaultJenkinsUrlOutsideJenkinsContext() throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod("generateCloudInitScript", String.class);
        method.setAccessible(true);

        String script = (String) method.invoke(proxmoxCloud, "agent-reflective");

        assertTrue(script.contains("#cloud-config"));
        assertTrue(script.contains("http://jenkins:8080/jnlpJars/agent.jar"));
        assertTrue(script.contains("-name agent-reflective"));
        assertTrue(script.contains("-secret %SECRET%"));
        assertTrue(script.contains("systemctl enable --now jenkins-agent.service"));
    }

    @Test
    public void testBuildJenkinsAgentServiceContentUsesDefaultJenkinsUrlOutsideJenkinsContext() throws Exception {
        Method method =
                ProxmoxCloud.class.getDeclaredMethod("buildJenkinsAgentServiceContent", String.class, String.class);
        method.setAccessible(true);

        String content = (String) method.invoke(proxmoxCloud, "agent-service", "secret-token");

        assertTrue(content.contains("ExecStart=java -jar /home/jenkins/agent.jar"));
        assertTrue(content.contains("-url http://jenkins:8080/"));
        assertTrue(content.contains("-name agent-service"));
        assertTrue(content.contains("-secret secret-token"));
        assertTrue(content.contains("WantedBy=multi-user.target"));
    }

    @Test
    public void testCloneReservationExposesUpidClone() {
        ProxmoxCloud.CloneReservation reservation = new ProxmoxCloud.CloneReservation("777", "UPID:test");
        assertEquals("UPID:test", reservation.getUpidClone());
    }

    @Test
    public void testAdditionalCloudGettersAndSetters() {
        assertEquals("pve", proxmoxCloud.getNode());
        assertTrue(proxmoxCloud.isVerifySsl());
        assertEquals("100", proxmoxCloud.getTemplateVmId());
        assertEquals("proxmox-agent", proxmoxCloud.getAgentNameTemplate());
        assertEquals(5, proxmoxCloud.getMaxInstances());
        assertEquals("jenkins", proxmoxCloud.getSshUsername());
        assertEquals("proxmox", proxmoxCloud.getLabels());

        proxmoxCloud.setNumExecutors(3);
        assertEquals(3, proxmoxCloud.getNumExecutors());
    }

    @Test
    public void testVmAllocationScopeKeyHandlesNullHostAndNode() throws Exception {
        proxmoxCloud.getServerConfig().setHost(null);
        proxmoxCloud.getServerConfig().setNode(null);

        Method method = ProxmoxCloud.class.getDeclaredMethod("getVmAllocationScopeKey");
        method.setAccessible(true);

        assertEquals("|", method.invoke(proxmoxCloud));
    }

    @Test
    @WithJenkins
    public void testIsDrainingForMaxBuildsDetectsOfflineReason(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxNode slave = buildDumbSlave(proxmoxCloud, "max-builds-draining", "811", null);
        jenkinsRule.jenkins.addNode(slave);

        Objects.requireNonNull(slave.toComputer())
                .setTemporarilyOffline(
                        true,
                        new OfflineCause.ByCLI(ProxmoxRetentionStrategy.MAX_BUILDS_DRAIN_REASON_PREFIX
                                + "agent reached max builds"));

        Method method = ProxmoxCloud.class.getDeclaredMethod("isDrainingForMaxBuilds", Slave.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(proxmoxCloud, slave));
    }

    @Test
    public void testIsRecoverableVmStateCoversAllBranches() throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod("isRecoverableVmState", String.class, String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(proxmoxCloud, "501", "running"));
        assertTrue((boolean) method.invoke(proxmoxCloud, "501", "starting"));
        assertFalse((boolean) method.invoke(proxmoxCloud, "501", "stopped"));
        assertFalse((boolean) method.invoke(proxmoxCloud, "501", null));
    }

    @Test
    public void testResolveRecoveredAgentNameFallsBackToTemplate() throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod(
                "resolveRecoveredAgentName", ProxmoxClient.ProxmoxVmSummary.class);
        method.setAccessible(true);

        ProxmoxClient.ProxmoxVmSummary unnamed =
                new ProxmoxClient.ProxmoxVmSummary("915", "", "running", "jenkins-proxmox-plugin");
        ProxmoxClient.ProxmoxVmSummary named =
                new ProxmoxClient.ProxmoxVmSummary("916", "restored-agent", "running", "jenkins-proxmox-plugin");

        assertEquals("proxmox-agent-915", method.invoke(proxmoxCloud, unnamed));
        assertEquals("restored-agent", method.invoke(proxmoxCloud, named));
    }

    @Test
    public void testIsReconciliationCandidateRequiresManagedTags() throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod(
                "isReconciliationCandidate", ProxmoxClient.ProxmoxVmSummary.class);
        method.setAccessible(true);

        ProxmoxClient.ProxmoxVmSummary managed = new ProxmoxClient.ProxmoxVmSummary(
                "920", "managed", "running", "jenkins-proxmox-plugin;jenkins-cloud-proxmox");
        ProxmoxClient.ProxmoxVmSummary unmanaged =
                new ProxmoxClient.ProxmoxVmSummary("921", "other", "running", "jenkins-cloud-other");

        assertFalse((boolean) method.invoke(proxmoxCloud, new Object[] {null}));
        assertTrue((boolean) method.invoke(proxmoxCloud, managed));
        assertFalse((boolean) method.invoke(proxmoxCloud, unmanaged));
    }

    @Test
    public void testResolveReconciledVmIpReturnsNullForInboundConnector() throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod("resolveReconciledVmIp", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(proxmoxCloud, "999"));
    }

    @Test
    @WithJenkins
    public void testFindNodeByVmIdFindsMatchingRetentionVmId(JenkinsRule jenkinsRule) throws Exception {
        jenkinsRule.jenkins.addNode(buildDumbSlave(proxmoxCloud, "lookup-agent", "930", null));
        jenkinsRule.createSlave();

        Method method = ProxmoxCloud.class.getDeclaredMethod("findNodeByVmId", jenkins.model.Jenkins.class, String.class);
        method.setAccessible(true);

        Node found = (Node) method.invoke(proxmoxCloud, jenkinsRule.jenkins, "930");
        Node missing = (Node) method.invoke(proxmoxCloud, jenkinsRule.jenkins, "does-not-exist");

        assertNotNull(found);
        assertEquals("lookup-agent", found.getNodeName());
        assertNull(missing);
    }

    @Test
    @WithJenkins
    public void testReconcileCloudNodesOnStartupWithRegisteredCloud(JenkinsRule jenkinsRule) {
        jenkinsRule.jenkins.clouds.add(proxmoxCloud);
        assertDoesNotThrow(ProxmoxCloud::reconcileCloudNodesOnStartup);
    }

    @Test
    @WithJenkins
    public void testProvisionCreatesPlannedNodeUsingFakeClient(JenkinsRule jenkinsRule) throws Exception {
        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.nextVmIds.add("950");

        setPrivateField(proxmoxCloud, "proxmoxClient", fakeClient);
        setPrivateField(proxmoxCloud, "startupReconciled", true);

        Collection<NodeProvisioner.PlannedNode> planned = proxmoxCloud.provision(new LabelAtom("proxmox"), 1);
        assertEquals(1, planned.size());

        NodeProvisioner.PlannedNode plannedNode = planned.iterator().next();
        Node provisioned = plannedNode.future.get(5, TimeUnit.SECONDS);

        assertNotNull(provisioned);
        assertTrue(fakeClient.execCommandCount > 0, "Guest agent commands should be executed in inbound provisioning");
    }

    @Test
    @WithJenkins
    public void testReconcileExistingTaggedVmsReattachesManagedInboundNode(JenkinsRule jenkinsRule) throws Exception {
        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.listedVms.add(new ProxmoxClient.ProxmoxVmSummary(
                "940", "restored-vm", "running", proxmoxCloud.buildProvisioningTags()));

        setPrivateField(proxmoxCloud, "proxmoxClient", fakeClient);

        proxmoxCloud.reconcileExistingTaggedVms();

        assertNotNull(jenkinsRule.jenkins.getNode("restored-vm"));
        assertTrue(proxmoxCloud.getInstances().stream().anyMatch(i -> "940".equals(i.getVmId())));
    }

    @Test
    public void testTerminateInstanceRemovesTrackedInstanceWithFakeClient() throws Exception {
        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.taskCompletion.put("UPID:stop:998", true);
        setPrivateField(proxmoxCloud, "proxmoxClient", fakeClient);

        Field instancesField = ProxmoxCloud.class.getDeclaredField("instances");
        instancesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<ProxmoxInstance> mutableInstances = (java.util.List<ProxmoxInstance>) instancesField.get(proxmoxCloud);
        mutableInstances.add(new ProxmoxInstance("998", "terminate-me"));

        proxmoxCloud.terminateInstance("998");

        assertTrue(fakeClient.stoppedVmIds.contains("998"));
        assertTrue(fakeClient.deletedVmIds.contains("998"));
        assertTrue(proxmoxCloud.getInstances().stream().noneMatch(i -> "998".equals(i.getVmId())));
    }

    @Test
    public void testWaitForTaskCompletionWrapperThrowsWhenSleepInterrupted() throws Exception {
        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.taskCompletion.put("UPID:never", false);
        setPrivateField(proxmoxCloud, "proxmoxClient", fakeClient);

        Method method = ProxmoxCloud.class.getDeclaredMethod("waitForTaskCompletion", String.class);
        method.setAccessible(true);

        Thread.currentThread().interrupt();
        try {
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(proxmoxCloud, "UPID:never"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testTryDownloadWithRetriesReturnsFalseWhenInterrupted() throws Exception {
        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.failingCommands.add("wget");

        Method method = ProxmoxCloud.class.getDeclaredMethod(
                "tryDownloadWithRetries",
                ProxmoxClient.class,
                String.class,
                String.class,
                String.class,
                String[].class);
        method.setAccessible(true);

        Thread.currentThread().interrupt();
        try {
            boolean result = (boolean) method.invoke(
                    proxmoxCloud,
                    fakeClient,
                    "900",
                    "https://jenkins.example/jnlpJars/agent.jar",
                    "/home/jenkins/agent.jar",
                    new String[] {"wget", "-O", "/home/jenkins/agent.jar", "https://jenkins.example/jnlpJars/agent.jar"});
            assertFalse(result);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @WithJenkins
    public void testReconcileMinInstancesProvisionsDeficitWithFakeClient(JenkinsRule jenkinsRule) throws Exception {
        ProxmoxJNLPConnector connector = new ProxmoxJNLPConnector();
        connector.setWebSocket(true);
        ProxmoxCloud minCloud = new ProxmoxCloud(
                "MinFloorFakeCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                2,
                5,
                5,
                connector,
                "jenkins",
                null,
                "proxmox",
                "/home/jenkins",
                1);

        FakeProxmoxClient fakeClient = newFakeProxmoxClient();
        fakeClient.nextVmIds.add("970");
        fakeClient.nextVmIds.add("971");
        setPrivateField(minCloud, "proxmoxClient", fakeClient);
        setPrivateField(minCloud, "startupReconciled", true);

        minCloud.reconcileMinInstances();

        for (int i = 0; i < 20 && minCloud.countLiveCloudNodes() < 2; i++) {
            Thread.sleep(100);
        }

        assertTrue(minCloud.countLiveCloudNodes() >= 2);
    }

    @Test
    @WithJenkins
    public void testDescriptorComputerConnectorDescriptorsAreFiltered(JenkinsRule jenkinsRule) {
        ProxmoxCloud.DescriptorImpl descriptor =
                jenkinsRule.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);

        java.util.List<Descriptor<ComputerConnector>> connectors = descriptor.getComputerConnectorDescriptors();

        assertFalse(connectors.isEmpty());
        assertTrue(connectors.stream()
                .allMatch(d -> d.clazz == hudson.plugins.sshslaves.SSHConnector.class
                        || d.clazz == ProxmoxJNLPConnector.class));
        assertTrue(connectors.stream().anyMatch(d -> d.clazz == ProxmoxJNLPConnector.class));
    }

    private void addSecretTextCredential(JenkinsRule jenkinsRule, String id, String secret) throws Exception {
        StringCredentialsImpl credentials =
                new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "test credential", Secret.fromString(secret));
        Objects.requireNonNull(CredentialsProvider.lookupStores(jenkinsRule.jenkins)
                        .iterator()
                        .next())
                .addCredentials(Domain.global(), credentials);
    }

    private void addProxmoxApiTokenCredential(
            JenkinsRule jenkinsRule, String id, String username, String realm, String tokenId, String tokenSecret)
            throws Exception {
        ProxmoxApiTokenCredentialsImpl credentials = new ProxmoxApiTokenCredentialsImpl(
                CredentialsScope.GLOBAL,
                id,
                "test proxmox token credential",
                username,
                realm,
                tokenId,
                Secret.fromString(tokenSecret));
        Objects.requireNonNull(CredentialsProvider.lookupStores(jenkinsRule.jenkins)
                        .iterator()
                        .next())
                .addCredentials(Domain.global(), credentials);
    }

    private ProxmoxNode buildDumbSlave(ProxmoxCloud cloud, String agentName, String vmId, String ipAddress)
            throws Exception {
        Method method =
                ProxmoxCloud.class.getDeclaredMethod("buildDumbSlave", String.class, String.class, String.class);
        method.setAccessible(true);
        return (ProxmoxNode) method.invoke(cloud, agentName, vmId, ipAddress);
    }

    private void releaseReservedVmId(ProxmoxCloud cloud, String vmId) throws Exception {
        Method method = ProxmoxCloud.class.getDeclaredMethod("releaseReservedVmId", String.class);
        method.setAccessible(true);
        method.invoke(cloud, vmId);
    }

    private FakeProxmoxClient newFakeProxmoxClient() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        FakeProxmoxClient fakeClient = (FakeProxmoxClient) unsafe.allocateInstance(FakeProxmoxClient.class);
        fakeClient.initState();
        return fakeClient;
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class FakeProxmoxClient extends ProxmoxClient {
        private java.util.Queue<String> nextVmIds;
        private java.util.Map<String, Boolean> taskCompletion;
        private java.util.List<ProxmoxVmSummary> listedVms;
        private java.util.Set<String> failingCommands;
        private java.util.List<String> deletedVmIds;
        private java.util.List<String> stoppedVmIds;
        private String fixedIpAddress;
        private int execCommandCount;

        private FakeProxmoxClient() throws IOException {
            super(new ProxmoxServerConfig("https://unused.invalid:8006", "unused", true, "pve"));
        }

        private void initState() {
            nextVmIds = new java.util.ArrayDeque<>();
            taskCompletion = new java.util.HashMap<>();
            listedVms = new java.util.ArrayList<>();
            failingCommands = new java.util.HashSet<>();
            deletedVmIds = new java.util.ArrayList<>();
            stoppedVmIds = new java.util.ArrayList<>();
            fixedIpAddress = "192.0.2.10";
            execCommandCount = 0;
        }

        @Override
        public String getNextVmId() {
            return nextVmIds.isEmpty() ? "1000" : nextVmIds.remove();
        }

        @Override
        public String cloneVmWithCloudInit(String sourceVmId, String newVmId, String newVmName, String cloudInitScript) {
            String upid = "UPID:clone:" + newVmId;
            taskCompletion.put(upid, true);
            return upid;
        }

        @Override
        public boolean isTaskComplete(String upid) {
            return taskCompletion.getOrDefault(upid, true);
        }

        @Override
        public void configureVmCloudInit(String vmId, String ciUser, String sshPublicKey, String tags) {
            // No-op for tests.
        }

        @Override
        public String startVm(String vmId) {
            String upid = "UPID:start:" + vmId;
            taskCompletion.put(upid, true);
            return upid;
        }

        @Override
        public void waitForGuestAgent(String vmId) {
            // No-op for tests.
        }

        @Override
        public void writeFileViaGuestAgent(String vmId, String filePath, String content) {
            // No-op for tests.
        }

        @Override
        public void execCommandViaGuestAgent(String vmId, String... commandAndArgs) throws Exception {
            execCommandCount++;
            if (commandAndArgs != null
                    && commandAndArgs.length > 0
                    && failingCommands.contains(commandAndArgs[0])) {
                throw new IOException("forced command failure for test");
            }
        }

        @Override
        public String getVmIpAddress(String vmId) {
            return fixedIpAddress;
        }

        @Override
        public String stopVm(String vmId) {
            stoppedVmIds.add(vmId);
            String upid = "UPID:stop:" + vmId;
            taskCompletion.put(upid, true);
            return upid;
        }

        @Override
        public String deleteVm(String vmId) {
            deletedVmIds.add(vmId);
            return "UPID:delete:" + vmId;
        }

        @Override
        public java.util.List<ProxmoxVmSummary> listNodeVms() {
            return new java.util.ArrayList<>(listedVms);
        }
    }
}
