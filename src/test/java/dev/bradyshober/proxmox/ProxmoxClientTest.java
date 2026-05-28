package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.util.Secret;
import java.io.IOException;
import java.util.Objects;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for {@link ProxmoxClient} using OkHttp MockWebServer to simulate the Proxmox API.
 * Requires a Jenkins context for credential resolution.
 */
@WithJenkins
class ProxmoxClientTest {

    private static final String CRED_ID = "proxmox-test-cred";

    private MockWebServer mockWebServer;
    private String baseUrl;

    @BeforeEach
    void startMockServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseUrl = "http://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort();
    }

    @AfterEach
    void stopMockServer() throws IOException {
        mockWebServer.shutdown();
    }

    private void addCredential(JenkinsRule j) throws Exception {
        ProxmoxApiTokenCredentialsImpl cred = new ProxmoxApiTokenCredentialsImpl(
                CredentialsScope.GLOBAL,
                CRED_ID,
                "test proxmox credential",
                "root",
                "pam",
                "mytoken",
                Secret.fromString("my-secret-value"));
        Objects.requireNonNull(
                        CredentialsProvider.lookupStores(j.jenkins).iterator().next())
                .addCredentials(Domain.global(), cred);
    }

    private ProxmoxServerConfig serverConfig(boolean verifySsl) {
        return new ProxmoxServerConfig(baseUrl, CRED_ID, verifySsl, "pve");
    }

    /** Enqueue a successful /api2/json/version response (used by verifyAuthentication). */
    private void enqueueVersionOk() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"version\":\"7.4-13\",\"release\":\"7.4\",\"repoid\":\"abc123\"}}"));
    }

    @Test
    void testConstructorAuthenticatesWithValidCredential(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        assertNotNull(client);

        // Verify the auth request was made with the correct header
        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("/api2/json/version", request.getPath());
        String authHeader = request.getHeader("Authorization");
        assertNotNull(authHeader);
        assertTrue(authHeader.startsWith("PVEAPIToken="), "Auth header should start with PVEAPIToken=");
        assertTrue(authHeader.contains("root@pam!mytoken=my-secret-value"));
    }

    @Test
    void testConstructorThrowsOn401(JenkinsRule j) throws Exception {
        addCredential(j);
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"errors\":{\"permission\":\"Permission check failed\"}}"));

        IOException ex = assertThrows(IOException.class, () -> new ProxmoxClient(serverConfig(false)));
        assertTrue(
                ex.getMessage().contains("authentication failed")
                        || ex.getMessage().contains("401"),
                "Exception message should mention auth failure: " + ex.getMessage());
    }

    @Test
    void testConstructorThrowsOnNonSuccessResponse(JenkinsRule j) throws Exception {
        addCredential(j);
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        IOException ex = assertThrows(IOException.class, () -> new ProxmoxClient(serverConfig(false)));
        assertTrue(
                ex.getMessage().contains("503") || ex.getMessage().contains("connectivity"),
                "Exception message should mention connectivity failure: " + ex.getMessage());
    }

    @Test
    void testGetNextVmId(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk(); // for constructor
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":\"300\"}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String vmId = client.getNextVmId();
        assertEquals("300", vmId);

        RecordedRequest req = mockWebServer.takeRequest(); // version check
        req = mockWebServer.takeRequest(); // getNextVmId
        assertEquals("/api2/json/cluster/nextid", req.getPath());
    }

    @Test
    void testGetNextVmIdThrowsOnHttpError(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        assertThrows(Exception.class, client::getNextVmId);
    }

    @Test
    void testResolveApiTokenThrowsWhenCredentialMissing(JenkinsRule j) {
        // No credential added — resolveApiToken should fail
        ProxmoxServerConfig config = new ProxmoxServerConfig(baseUrl, "non-existent-cred", false, "pve");
        assertThrows(IOException.class, () -> new ProxmoxClient(config));
    }

    @Test
    void testResolveApiTokenThrowsForBlankCredentialId(JenkinsRule j) {
        ProxmoxServerConfig config = new ProxmoxServerConfig(baseUrl, "", false, "pve");
        assertThrows(IOException.class, () -> new ProxmoxClient(config));
    }

    @Test
    void testResolveApiTokenThrowsForNullCredentialId(JenkinsRule j) {
        ProxmoxServerConfig config = new ProxmoxServerConfig(baseUrl, null, false, "pve");
        assertThrows(IOException.class, () -> new ProxmoxClient(config));
    }

    @Test
    void testListNodeVms(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":["
                        + "{\"vmid\":100,\"name\":\"template-vm\",\"status\":\"stopped\",\"tags\":\"jenkins-proxmox-plugin\"},"
                        + "{\"vmid\":200,\"name\":\"agent-1\",\"status\":\"running\",\"tags\":\"jenkins-proxmox-plugin;jenkins-cloud-mycloud\"}"
                        + "]}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        java.util.List<ProxmoxClient.ProxmoxVmSummary> vms = client.listNodeVms();

        assertNotNull(vms);
        assertEquals(2, vms.size());

        ProxmoxClient.ProxmoxVmSummary first = vms.get(0);
        assertEquals("100", first.getVmId());
        assertEquals("template-vm", first.getName());
        assertEquals("stopped", first.getStatus());

        ProxmoxClient.ProxmoxVmSummary second = vms.get(1);
        assertEquals("200", second.getVmId());
        assertEquals("running", second.getStatus());
    }

    @Test
    void testListNodeVmsEmptyResponse(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":[]}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        java.util.List<ProxmoxClient.ProxmoxVmSummary> vms = client.listNodeVms();
        assertNotNull(vms);
        assertTrue(vms.isEmpty());
    }

    @Test
    void testConfigureVmCloudInitDoesNotDoubleEncodeSshKey(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve:00000001:00000001:00000001:qmconfig:900:root@pam:\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String sshKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCtest user@host";
        client.configureVmCloudInit("900", "jenkins", sshKey, "jenkins-proxmox-plugin");

        mockWebServer.takeRequest(); // version check
        RecordedRequest putConfigRequest = mockWebServer.takeRequest();
        assertEquals("PUT", putConfigRequest.getMethod());
        assertEquals("/api2/json/nodes/pve/qemu/900/config", putConfigRequest.getPath());

        String body = putConfigRequest.getBody().readUtf8();
        assertTrue(body.contains("sshkeys="), "Expected sshkeys field in request body");
        assertTrue(body.contains("ssh-rsa%20"), "Expected ssh key to be URL-encoded once: " + body);
        assertFalse(body.contains("%2520"), "Request should not contain double-encoded spaces: " + body);
        assertFalse(body.contains("%2540"), "Request should not contain double-encoded @: " + body);
    }

    @Test
    void testConfigureVmCloudInitNormalizesWrappedSshKey(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve:00000002:00000002:00000002:qmconfig:901:root@pam:\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String wrappedKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCt+abc/def\n" + "ghi+jkl/mno+pqr== brady@jenkins\n";
        client.configureVmCloudInit("901", "jenkins", wrappedKey, "jenkins-proxmox-plugin");

        mockWebServer.takeRequest(); // version check
        RecordedRequest putConfigRequest = mockWebServer.takeRequest();
        String body = putConfigRequest.getBody().readUtf8();

        assertFalse(body.contains("%0A"), "Normalized sshkeys should not include encoded newlines: " + body);
        assertTrue(body.contains("%2B"), "Base64 plus signs must be preserved as %2B: " + body);
        assertTrue(body.contains("brady%40jenkins"), "Comment should remain URL-encoded: " + body);
    }

    @Test
    void testIsTaskCompleteThrowsForInvalidUpidFormat(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> client.isTaskComplete("UPID:bad"));
        assertTrue(ex.getMessage().contains("Invalid UPID format"));
    }

    @Test
    void testIsTaskCompleteRecognizesStoppedOkWithoutEndtime(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        boolean complete = client.isTaskComplete("UPID:pve:0001:0002:0003:qmconfig:900:root@pam:");

        assertTrue(complete);
    }

    @Test
    void testIsTaskCompleteReturnsFalseWhenDataMissing(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        boolean complete = client.isTaskComplete("UPID:pve:0001:0002:0003:qmconfig:900:root@pam:");

        assertFalse(complete);
    }

    @Test
    void testDeleteVmReturnsTaskUpid(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve:0003:0003:0003:qmdelete:900:root@pam:\"}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String upid = client.deleteVm("900");

        assertTrue(upid.contains("qmdelete"));
        mockWebServer.takeRequest();
        RecordedRequest deleteRequest = mockWebServer.takeRequest();
        assertEquals("DELETE", deleteRequest.getMethod());
        assertEquals("/api2/json/nodes/pve/qemu/900", deleteRequest.getPath());
    }

    @Test
    void testDeleteVmThrowsWhenResponseHasNoData(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        assertThrows(Exception.class, () -> client.deleteVm("901"));
    }

    @Test
    void testDeleteVmThrowsOnHttpError(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("delete failed"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IOException ex = assertThrows(IOException.class, () -> client.deleteVm("902"));
        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    @Test
    void testCloneVmWithCloudInitPostsExpectedRequest(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve:0004:0004:0004:qmclone:910:root@pam:\"}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String upid = client.cloneVmWithCloudInit("100", "910", "agent-910", null);

        assertTrue(upid.contains("qmclone"));
        mockWebServer.takeRequest(); // version
        RecordedRequest cloneRequest = mockWebServer.takeRequest();
        assertEquals("POST", cloneRequest.getMethod());
        assertEquals("/api2/json/nodes/pve/qemu/100/clone", cloneRequest.getPath());
        String body = cloneRequest.getBody().readUtf8();
        assertTrue(body.contains("newid=910"));
        assertTrue(body.contains("name=agent-910"));
        assertTrue(body.contains("full=1"));
    }

    @Test
    void testStartVmAndStopVmReturnTaskUpids(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":\"UPID:start\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":\"UPID:stop\"}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        assertEquals("UPID:start", client.startVm("920"));
        assertEquals("UPID:stop", client.stopVm("920"));

        mockWebServer.takeRequest(); // version
        RecordedRequest startRequest = mockWebServer.takeRequest();
        RecordedRequest stopRequest = mockWebServer.takeRequest();
        assertEquals("/api2/json/nodes/pve/qemu/920/status/start", startRequest.getPath());
        assertEquals("/api2/json/nodes/pve/qemu/920/status/stop", stopRequest.getPath());
    }

    @Test
    void testWriteFileViaGuestAgentFallsBackToBase64Encoding(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("plain write failed"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":null}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        client.writeFileViaGuestAgent("930", "/tmp/test.txt", "hello world");

        mockWebServer.takeRequest(); // version
        RecordedRequest firstWrite = mockWebServer.takeRequest();
        RecordedRequest secondWrite = mockWebServer.takeRequest();
        assertEquals("POST", firstWrite.getMethod());
        assertEquals("POST", secondWrite.getMethod());
        assertTrue(firstWrite.getBody().readUtf8().contains("content=hello%20world"));
        String secondBody = secondWrite.getBody().readUtf8();
        assertTrue(secondBody.contains("encode=1"));
        assertTrue(secondBody.contains("content=aGVsbG8gd29ybGQ%3D"));
    }

    @Test
    void testExecCommandViaGuestAgentRetriesOn596AndCompletes(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(596).setBody("temporary guest agent issue"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":{\"result\":\"pong\"}}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":{\"pid\":42}}"));
        mockWebServer.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"data\":{\"exited\":1,\"exitcode\":0}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        assertDoesNotThrow(() -> client.execCommandViaGuestAgent("940", "systemctl", "daemon-reload"));

        mockWebServer.takeRequest(); // version
        RecordedRequest firstExec = mockWebServer.takeRequest();
        RecordedRequest pingProbe = mockWebServer.takeRequest();
        RecordedRequest secondExec = mockWebServer.takeRequest();
        RecordedRequest statusPoll = mockWebServer.takeRequest();

        assertTrue(firstExec.getPath().contains("/agent/exec"));
        assertTrue(pingProbe.getPath().contains("/agent/ping"));
        assertTrue(secondExec.getPath().contains("/agent/exec"));
        assertTrue(statusPoll.getPath().contains("/agent/exec-status?pid=42"));
    }

    @Test
    void testExecCommandViaGuestAgentThrowsWithDecodedStderr(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":{\"pid\":7}}"));
        String stderr = java.util.Base64.getEncoder()
                .encodeToString("permission denied".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"exited\":1,\"exitcode\":1,\"err-data\":\"" + stderr + "\"}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IOException ex = assertThrows(
                IOException.class, () -> client.execCommandViaGuestAgent("950", "systemctl", "start", "jenkins-agent"));
        assertTrue(ex.getMessage().contains("permission denied"));
    }

    @Test
    void testGetVmIpAddressReturnsFirstNonLoopbackIpv4(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"result\":["
                        + "{\"name\":\"lo\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}]},"
                        + "{\"name\":\"eth0\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"192.0.2.55\"}]}"
                        + "]}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        String ip = client.getVmIpAddress("960");

        assertEquals("192.0.2.55", ip);
    }

    @Test
    void testConfigureVmCloudInitRetriesWithCompatibilityEncoding(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("parameter verification failed - sshkeys: invalid urlencoded string"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve:0005:0005:0005:qmconfig:970:root@pam:\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        client.configureVmCloudInit("970", "jenkins", "ssh-rsa AAAA test@host", "jenkins-proxmox-plugin");

        mockWebServer.takeRequest(); // version
        RecordedRequest firstPut = mockWebServer.takeRequest();
        RecordedRequest secondPut = mockWebServer.takeRequest();

        assertEquals("PUT", firstPut.getMethod());
        assertEquals("PUT", secondPut.getMethod());
        String firstBody = firstPut.getBody().readUtf8();
        String secondBody = secondPut.getBody().readUtf8();
        assertTrue(firstBody.contains("sshkeys=ssh-rsa%20AAAA%20test%40host"));
        assertTrue(secondBody.contains("sshkeys=ssh-rsa%2520AAAA%2520test%2540host"));
    }

    @Test
    void testListNodeVmsSkipsInvalidEntries(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":[null,{},"
                        + "{\"name\":\"missing-id\"},"
                        + "{\"vmid\":300,\"name\":\"agent-300\",\"status\":\"running\"}] }"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        java.util.List<ProxmoxClient.ProxmoxVmSummary> vms = client.listNodeVms();

        assertEquals(1, vms.size());
        assertEquals("300", vms.get(0).getVmId());
        assertEquals("", vms.get(0).getTags());
    }

    @Test
    void testExecCommandViaGuestAgentValidatesEmptyCommand(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> client.execCommandViaGuestAgent("900"));
        assertTrue(ex.getMessage().contains("non-empty command"));
    }

    @Test
    void testNormalizeSshPublicKeyCompactsWhitespaceForUnknownType() {
        String normalized = ProxmoxClient.normalizeSshPublicKey("custom-key   AAAA\n   BBBB\t comment");
        assertEquals("custom-key AAAA BBBB comment", normalized);
    }

    @Test
    void testNormalizeSshPublicKeyRepairsWrappedOpenSshKey() {
        String normalized = ProxmoxClient.normalizeSshPublicKey("ssh-rsa AAAA BBBB CCCC user@host");
        assertEquals("ssh-rsa AAAABBBBCCCC user@host", normalized);
    }

    @Test
    void testNormalizeSshPublicKeyNullInput() {
        assertEquals("", ProxmoxClient.normalizeSshPublicKey(null));
    }
}
