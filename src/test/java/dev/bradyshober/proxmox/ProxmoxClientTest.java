package dev.bradyshober.proxmox;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.gson.JsonObject;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
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

    private ProxmoxServerConfig clusterServerConfig(boolean verifySsl) {
        return new ProxmoxServerConfig(baseUrl, CRED_ID, verifySsl, "pve", true);
    }

    private Object invokePrivate(ProxmoxClient client, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = ProxmoxClient.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(client, args);
    }

    private static String bodyToString(RequestBody body) throws IOException {
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
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
    void testCloneVmWithCloudInitUsesClusterAutoPlacementWhenEnabled(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":[{\"type\":\"qemu\",\"vmid\":100,\"node\":\"pve-a\"}]}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":["
                        + "{\"type\":\"node\",\"node\":\"pve-a\",\"status\":\"online\",\"cpu\":0.8},"
                        + "{\"type\":\"node\",\"node\":\"pve-b\",\"status\":\"online\",\"cpu\":0.2}"
                        + "]}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve-b:0004:0004:0004:qmclone:910:root@pam:\"}"));

        ProxmoxClient client = new ProxmoxClient(clusterServerConfig(false));
        String upid = client.cloneVmWithCloudInit("100", "910", "agent-910", null);
        assertTrue(upid.contains("qmclone"));

        mockWebServer.takeRequest(); // version
        RecordedRequest vmDiscover = mockWebServer.takeRequest();
        RecordedRequest nodeDiscover = mockWebServer.takeRequest();
        RecordedRequest cloneRequest = mockWebServer.takeRequest();

        assertEquals("/api2/json/cluster/resources?type=vm", vmDiscover.getPath());
        assertEquals("/api2/json/cluster/resources?type=node", nodeDiscover.getPath());
        assertEquals("/api2/json/nodes/pve-a/qemu/100/clone", cloneRequest.getPath());
        assertTrue(cloneRequest.getBody().readUtf8().contains("target=pve-b"));
    }

    @Test
    void testCloneVmWithCloudInitFallsBackToSourceNodeWhenLocalStorageBlocksTarget(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":[{\"type\":\"qemu\",\"vmid\":100,\"node\":\"pve-a\"}]}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":["
                        + "{\"type\":\"node\",\"node\":\"pve-a\",\"status\":\"online\",\"cpu\":0.8},"
                        + "{\"type\":\"node\",\"node\":\"pve-b\",\"status\":\"online\",\"cpu\":0.2}"
                        + "]}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"data\":null,\"message\":\"can't clone VM to node 'pve-b' (VM uses local storage)\\n\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"UPID:pve-a:0004:0004:0004:qmclone:911:root@pam:\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":\"UPID:start\"}"));

        ProxmoxClient client = new ProxmoxClient(clusterServerConfig(false));
        String upid = client.cloneVmWithCloudInit("100", "911", "agent-911", null);
        assertTrue(upid.contains("qmclone"));
        assertEquals("UPID:start", client.startVm("911"));

        mockWebServer.takeRequest(); // version
        mockWebServer.takeRequest(); // discover template vm node
        mockWebServer.takeRequest(); // discover cluster target node
        RecordedRequest firstCloneRequest = mockWebServer.takeRequest();
        RecordedRequest secondCloneRequest = mockWebServer.takeRequest();
        RecordedRequest startRequest = mockWebServer.takeRequest();

        String firstCloneBody = firstCloneRequest.getBody().readUtf8();
        String secondCloneBody = secondCloneRequest.getBody().readUtf8();
        assertTrue(firstCloneBody.contains("target=pve-b"));
        assertFalse(secondCloneBody.contains("target="));
        assertEquals("/api2/json/nodes/pve-a/qemu/100/clone", firstCloneRequest.getPath());
        assertEquals("/api2/json/nodes/pve-a/qemu/100/clone", secondCloneRequest.getPath());
        assertEquals("/api2/json/nodes/pve-a/qemu/911/status/start", startRequest.getPath());
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
    void testStartVmResolvesVmNodeFromClusterInAutoPlacementMode(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":[{\"type\":\"qemu\",\"vmid\":920,\"node\":\"pve-z\"}]}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\":\"UPID:start\"}"));

        ProxmoxClient client = new ProxmoxClient(clusterServerConfig(false));
        assertEquals("UPID:start", client.startVm("920"));

        mockWebServer.takeRequest(); // version
        RecordedRequest discover = mockWebServer.takeRequest();
        RecordedRequest start = mockWebServer.takeRequest();
        assertEquals("/api2/json/cluster/resources?type=vm", discover.getPath());
        assertEquals("/api2/json/nodes/pve-z/qemu/920/status/start", start.getPath());
    }

    @Test
    void testListNodeVmsReadsClusterResourcesWhenAutoPlacementEnabled(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":["
                        + "{\"type\":\"qemu\",\"vmid\":200,\"name\":\"agent-1\",\"status\":\"running\",\"tags\":\"t1\",\"node\":\"pve-a\"},"
                        + "{\"type\":\"lxc\",\"vmid\":201,\"name\":\"ct-1\",\"status\":\"running\"}"
                        + "]}"));

        ProxmoxClient client = new ProxmoxClient(clusterServerConfig(false));
        java.util.List<ProxmoxClient.ProxmoxVmSummary> vms = client.listNodeVms();

        assertEquals(1, vms.size());
        assertEquals("200", vms.get(0).getVmId());

        mockWebServer.takeRequest(); // version
        RecordedRequest listRequest = mockWebServer.takeRequest();
        assertEquals("/api2/json/cluster/resources?type=vm", listRequest.getPath());
    }

    @Test
    void testStartVmThrowsOnForbiddenResponse(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(403).setBody("permission denied"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IOException ex = assertThrows(IOException.class, () -> client.startVm("921"));

        assertTrue(ex.getMessage().contains("HTTP 403"));
    }

    @Test
    void testStartVmThrowsWhenSuccessfulResponseHasNoData(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        IOException ex = assertThrows(IOException.class, () -> client.startVm("922"));

        assertTrue(ex.getMessage().contains("No UPID returned from API call"));
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
    void testExtractNodeFromUpidReturnsNodeSegment(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertEquals(
                "pve",
                invokePrivate(
                        client,
                        "extractNodeFromUpid",
                        new Class<?>[] {String.class},
                        "UPID:pve:0001:0002:0003:qmclone:900:root@pam:"));
    }

    @Test
    void testParseVmSummariesReturnsEmptyWhenDataIsMissingOrNotAnArray(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        @SuppressWarnings("unchecked")
        java.util.List<ProxmoxClient.ProxmoxVmSummary> noData = (java.util.List<ProxmoxClient.ProxmoxVmSummary>)
                invokePrivate(client, "parseVmSummaries", new Class<?>[] {String.class}, "{}");
        @SuppressWarnings("unchecked")
        java.util.List<ProxmoxClient.ProxmoxVmSummary> objectData = (java.util.List<ProxmoxClient.ProxmoxVmSummary>)
                invokePrivate(client, "parseVmSummaries", new Class<?>[] {String.class}, "{\"data\":{}}");

        assertTrue(noData.isEmpty());
        assertTrue(objectData.isEmpty());
    }

    @Test
    void testDecodeExecDataFieldHandlesBlankAndInvalidBase64(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));
        JsonObject blankField = new JsonObject();
        blankField.addProperty("err-data", "");
        JsonObject invalidField = new JsonObject();
        invalidField.addProperty("err-data", "not-base64!!!");

        assertEquals(
                "",
                invokePrivate(
                        client,
                        "decodeExecDataField",
                        new Class<?>[] {JsonObject.class, String.class},
                        blankField,
                        "err-data"));
        assertEquals(
                "not-base64!!!",
                invokePrivate(
                        client,
                        "decodeExecDataField",
                        new Class<?>[] {JsonObject.class, String.class},
                        invalidField,
                        "err-data"));
    }

    @Test
    void testBuildCloudInitRequestBodySupportsNormalAndCompatibilityEncoding(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        RequestBody standardBody = (RequestBody) invokePrivate(
                client,
                "buildCloudInitRequestBody",
                new Class<?>[] {String.class, String.class, String.class, boolean.class},
                " jenkins ",
                "ssh-rsa AAAA test@host",
                " tag-one ; tag-two ",
                false);
        RequestBody compatibilityBody = (RequestBody) invokePrivate(
                client,
                "buildCloudInitRequestBody",
                new Class<?>[] {String.class, String.class, String.class, boolean.class},
                " ",
                "ssh-rsa AAAA test@host",
                " ",
                true);

        String standard = bodyToString(standardBody);
        String compatibility = bodyToString(compatibilityBody);

        assertTrue(standard.contains("ciuser=jenkins"));
        assertTrue(standard.contains("sshkeys=ssh-rsa%20AAAA%20test%40host"));
        assertTrue(standard.contains("ipconfig0=ip%3Ddhcp"));
        assertTrue(standard.contains("tags=tag-one%20%3B%20tag-two"));

        assertFalse(compatibility.contains("ciuser="));
        assertFalse(compatibility.contains("tags="));
        assertTrue(compatibility.contains("sshkeys=ssh-rsa%2520AAAA%2520test%2540host"));
    }

    @Test
    void testIsInvalidUrlEncodedSshKeyErrorHandlesNullAndMatchingMessages(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertEquals(
                false,
                invokePrivate(
                        client, "isInvalidUrlEncodedSshKeyError", new Class<?>[] {IOException.class}, new Object[] {null
                        }));
        assertEquals(
                false,
                invokePrivate(
                        client,
                        "isInvalidUrlEncodedSshKeyError",
                        new Class<?>[] {IOException.class},
                        new IOException("different error")));
        assertEquals(
                true,
                invokePrivate(
                        client,
                        "isInvalidUrlEncodedSshKeyError",
                        new Class<?>[] {IOException.class},
                        new IOException("sshkeys: invalid urlencoded string")));
    }

    @Test
    void testNormalizeSshPublicKeyCoversBlankUnknownTypeAndRecognizedKeyWithoutComment() {
        assertEquals("", ProxmoxClient.normalizeSshPublicKey("   \n\t  "));
        assertEquals(
                "custom-key AAAA BBBB comment",
                ProxmoxClient.normalizeSshPublicKey("custom-key   AAAA BBBB   comment"));
        assertEquals("ssh-ed25519 AAAABBBBCCCC", ProxmoxClient.normalizeSshPublicKey("ssh-ed25519 AAAA BBBB CCCC"));
    }

    @Test
    void testExtractFirstIpv4SkipsMissingDataLoopbackAndIpv6(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertNull(invokePrivate(client, "extractFirstIpv4", new Class<?>[] {JsonObject.class}, new Object[] {null}));

        JsonObject noResult = new JsonObject();
        noResult.add("data", new JsonObject());
        assertNull(invokePrivate(client, "extractFirstIpv4", new Class<?>[] {JsonObject.class}, noResult));

        JsonObject valid = com.google.gson.JsonParser.parseString("{\"data\":{\"result\":["
                        + "{\"name\":\"lo\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}]},"
                        + "{\"name\":\"eth0\",\"ip-addresses\":[{\"ip-address-type\":\"ipv6\",\"ip-address\":\"::1\"},{\"ip-address-type\":\"ipv4\",\"ip-address\":\"198.51.100.42\"}]}"
                        + "]}}")
                .getAsJsonObject();

        assertEquals(
                "198.51.100.42", invokePrivate(client, "extractFirstIpv4", new Class<?>[] {JsonObject.class}, valid));
    }

    @Test
    void testParseVmSummaryAndReadNullableFieldCoverEdgeCases(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertNull(invokePrivate(
                client, "parseVmSummary", new Class<?>[] {com.google.gson.JsonElement.class}, new Object[] {null}));
        assertNull(invokePrivate(
                client,
                "parseVmSummary",
                new Class<?>[] {com.google.gson.JsonElement.class},
                com.google.gson.JsonParser.parseString("{}").getAsJsonObject()));

        JsonObject vm = com.google.gson.JsonParser.parseString(
                        "{\"vmid\":301,\"name\":null,\"status\":null,\"tags\":null}")
                .getAsJsonObject();
        ProxmoxClient.ProxmoxVmSummary summary = (ProxmoxClient.ProxmoxVmSummary)
                invokePrivate(client, "parseVmSummary", new Class<?>[] {com.google.gson.JsonElement.class}, vm);

        assertNotNull(summary);
        assertEquals("301", summary.getVmId());
        assertNull(summary.getName());
        assertNull(summary.getStatus());
        assertEquals("", summary.getTags());

        assertNull(invokePrivate(
                client, "readNullableField", new Class<?>[] {JsonObject.class, String.class}, vm, "missing"));
        assertNull(invokePrivate(
                client, "readNullableField", new Class<?>[] {JsonObject.class, String.class}, vm, "name"));
        assertEquals(
                "301",
                invokePrivate(
                        client, "readNullableField", new Class<?>[] {JsonObject.class, String.class}, vm, "vmid"));
    }

    @Test
    void testExtractInterfaceAndIpv4AddressHelpersCoverInvalidInputs(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertNull(invokePrivate(
                client, "extractInterfaceIpv4", new Class<?>[] {com.google.gson.JsonElement.class}, new Object[] {null
                }));
        assertNull(invokePrivate(
                client,
                "extractInterfaceIpv4",
                new Class<?>[] {com.google.gson.JsonElement.class},
                com.google.gson.JsonParser.parseString("{\"name\":\"lo\",\"ip-addresses\":[]}")
                        .getAsJsonObject()));

        JsonObject ipv6 = com.google.gson.JsonParser.parseString(
                        "{\"ip-address-type\":\"ipv6\",\"ip-address\":\"::1\"}")
                .getAsJsonObject();
        JsonObject loopback = com.google.gson.JsonParser.parseString(
                        "{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}")
                .getAsJsonObject();
        JsonObject valid = com.google.gson.JsonParser.parseString(
                        "{\"ip-address-type\":\"ipv4\",\"ip-address\":\"203.0.113.7\"}")
                .getAsJsonObject();

        assertNull(
                invokePrivate(client, "extractIpv4Address", new Class<?>[] {com.google.gson.JsonElement.class}, ipv6));
        assertNull(invokePrivate(
                client, "extractIpv4Address", new Class<?>[] {com.google.gson.JsonElement.class}, loopback));
        assertEquals(
                "203.0.113.7",
                invokePrivate(client, "extractIpv4Address", new Class<?>[] {com.google.gson.JsonElement.class}, valid));

        JsonObject iface = com.google.gson.JsonParser.parseString(
                        "{\"name\":\"eth0\",\"ip-addresses\":[{\"ip-address-type\":\"ipv6\",\"ip-address\":\"::1\"},{\"ip-address-type\":\"ipv4\",\"ip-address\":\"203.0.113.8\"}]}")
                .getAsJsonObject();
        assertEquals(
                "203.0.113.8",
                invokePrivate(
                        client, "extractInterfaceIpv4", new Class<?>[] {com.google.gson.JsonElement.class}, iface));
    }

    @Test
    void testCompletedTaskStatusNullAndCloseAreCovered(JenkinsRule j) throws Exception {
        addCredential(j);
        enqueueVersionOk();

        ProxmoxClient client = new ProxmoxClient(serverConfig(false));

        assertFalse(ProxmoxClient.isCompletedTaskStatus(null));
        assertDoesNotThrow(client::close);
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
