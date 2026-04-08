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
}
