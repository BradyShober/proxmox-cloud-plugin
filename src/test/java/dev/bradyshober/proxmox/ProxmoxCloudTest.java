package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.gson.JsonObject;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
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
                false, // Don't verify SSL for testing
                "pve");

        agentTemplate = new ProxmoxAgentTemplate(
                "100", // Template VM ID
                "proxmox-agent",
                5, // Max instances
                ProxmoxAgentTemplate.LauncherStrategy.SSH,
                "jenkins",
                "/home/jenkins/.ssh/id_rsa",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB jenkins@controller",
                22,
                "proxmox");

        proxmoxCloud = new ProxmoxCloud(
                "Proxmox",
                serverConfig.getHost(),
                serverConfig.getApiTokenCredentialId(),
                serverConfig.isVerifySsl(),
                serverConfig.getNode(),
                agentTemplate.getTemplateVmId(),
                agentTemplate.getAgentNameTemplate(),
                agentTemplate.getMaxInstances(),
                agentTemplate.getLauncherStrategy().name(),
                agentTemplate.getSshUsername(),
                agentTemplate.getSshPrivateKey(),
                agentTemplate.getSshPublicKey(),
                agentTemplate.getSshPort(),
                agentTemplate.getLabels());
    }

    @Test
    public void testProxmoxServerConfigCreation() {
        assertNotNull(serverConfig);
        assertEquals("https://proxmox.example.com:8006", serverConfig.getHost());
        assertEquals("proxmox-api-token", serverConfig.getApiTokenCredentialId());
        assertFalse(serverConfig.isVerifySsl());
    }

    @Test
    public void testProxmoxAgentTemplateCreation() {
        assertNotNull(agentTemplate);
        assertEquals("100", agentTemplate.getTemplateVmId());
        assertEquals("proxmox-agent", agentTemplate.getAgentNameTemplate());
        assertEquals(5, agentTemplate.getMaxInstances());
        assertEquals(ProxmoxAgentTemplate.LauncherStrategy.SSH, agentTemplate.getLauncherStrategy());
        assertEquals("jenkins", agentTemplate.getSshUsername());
        assertEquals("/home/jenkins/.ssh/id_rsa", agentTemplate.getSshPrivateKey());
        assertEquals("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB jenkins@controller", agentTemplate.getSshPublicKey());
        assertEquals(22, agentTemplate.getSshPort());
        assertEquals("proxmox", agentTemplate.getLabels());
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
        // Create cloud with multiple labels
        ProxmoxCloud multiLabelCloud = new ProxmoxCloud(
                "MultiLabel",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                5,
                "SSH",
                "jenkins",
                "/home/jenkins/.ssh/id_rsa",
                null,
                22,
                "proxmox linux docker");

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
        ProxmoxCloud websocketCloud = new ProxmoxCloud(
                "WebSocketCloud",
                "https://proxmox.example.com:8006",
                "proxmox-api-token",
                false,
                "pve",
                "100",
                "proxmox-agent",
                5,
                "WEBSOCKET",
                "jenkins",
                "/home/jenkins/.ssh/id_rsa",
                null,
                22,
                "proxmox");

        DumbSlave slave = buildDumbSlave(websocketCloud, "proxmox-agent-1", "101", null);
        ComputerLauncher launcher = slave.getLauncher();

        assertInstanceOf(ProxmoxWebSocketLauncher.class, launcher);
        assertInstanceOf(JNLPLauncher.class, launcher);
        assertTrue(((JNLPLauncher) launcher).isWebSocket(), "WebSocket launcher must be marked as inbound/WebSocket");
    }

    @Test
    @WithJenkins
    public void testBuildDumbSlaveUsesSshLauncherForSshStrategy(JenkinsRule jenkinsRule) throws Exception {
        DumbSlave slave = buildDumbSlave(proxmoxCloud, "proxmox-agent-1", "101", "192.0.2.10");

        assertInstanceOf(ProxmoxSshLauncher.class, slave.getLauncher());
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

    private void addSecretTextCredential(JenkinsRule jenkinsRule, String id, String secret) throws Exception {
        StringCredentialsImpl credentials =
                new StringCredentialsImpl(CredentialsScope.GLOBAL, id, "test credential", Secret.fromString(secret));
        Objects.requireNonNull(CredentialsProvider.lookupStores(jenkinsRule.jenkins)
                        .iterator()
                        .next())
                .addCredentials(Domain.global(), credentials);
    }

                    private DumbSlave buildDumbSlave(ProxmoxCloud cloud, String agentName, String vmId, String ipAddress) throws Exception {
                        Method method = ProxmoxCloud.class.getDeclaredMethod("buildDumbSlave", String.class, String.class, String.class);
                        method.setAccessible(true);
                        return (DumbSlave) method.invoke(cloud, agentName, vmId, ipAddress);
                    }
}
