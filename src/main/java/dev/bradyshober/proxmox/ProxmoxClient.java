package dev.bradyshober.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import hudson.security.ACL;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jenkins.model.Jenkins;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client wrapper for Proxmox VE REST API operations.
 * Uses OkHttp3 for HTTP communication and Gson for JSON parsing.
 */
public class ProxmoxClient {
    private static final Logger LOGGER = Logger.getLogger(ProxmoxClient.class.getName());
    private static final Gson gson = new Gson();
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String STATUS_FIELD = "status";
    private static final String EXITSTATUS_FIELD = "exitstatus";
    private static final String ENDTIME_FIELD = "endtime";

    private final ProxmoxServerConfig serverConfig;
    private final OkHttpClient httpClient;
    private final String nodeForVm; // MVP: single node assumption
    private String authToken; // Session token from login

    public static final class ProxmoxVmSummary {
        private final String vmId;
        private final String name;
        private final String status;
        private final String tags;

        ProxmoxVmSummary(String vmId, String name, String status, String tags) {
            this.vmId = vmId;
            this.name = name;
            this.status = status;
            this.tags = tags;
        }

        public String getVmId() {
            return vmId;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public String getTags() {
            return tags;
        }
    }

    public ProxmoxClient(ProxmoxServerConfig serverConfig) throws IOException {
        this.serverConfig = serverConfig;
        this.nodeForVm = serverConfig.getNode();

        // Create HTTP client with SSL verification control
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);

        if (!serverConfig.isVerifySsl()) {
            try {
                // Create a trust manager that accepts all certificates
                X509TrustManager trustAllCerts = new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        // Accept all client certificates (not used in OkHttp)
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        // Accept all server certificates
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };

                // Create SSLContext with the trust-all manager
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[] {trustAllCerts}, new SecureRandom());

                // Apply to OkHttp client
                builder.sslSocketFactory(sslContext.getSocketFactory(), trustAllCerts)
                        .hostnameVerifier((hostname, session) -> true);

                LOGGER.log(
                        Level.WARNING, "SSL verification disabled for Proxmox API - only use in development/testing");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to disable SSL verification", e);
                throw new IOException("Failed to configure SSL bypass", e);
            }
        }

        this.httpClient = builder.build();

        // Authenticate with API token
        authenticate();
    }

    /**
     * Authenticate with Proxmox using API token and verify connectivity.
     */
    private void authenticate() throws IOException {
        String apiToken = resolveApiToken(serverConfig.getApiTokenCredentialId());

        // Trim whitespace/newlines that may have been introduced during copy-paste into credential
        apiToken = apiToken.strip();

        // Guard against the user storing the full "PVEAPIToken=..." value in the credential
        if (apiToken.startsWith("PVEAPIToken=")) {
            LOGGER.log(
                    Level.WARNING,
                    "API token credential appears to already contain the 'PVEAPIToken=' prefix - using as-is");
            this.authToken = apiToken;
        } else {
            // Token format: PVEAPIToken=userid@pam!tokenid=token-secret
            this.authToken = "PVEAPIToken=" + apiToken;
        }

        LOGGER.log(
                Level.FINE,
                "Proxmox client initialized with API token credential ID: " + serverConfig.getApiTokenCredentialId());

        // Verify connectivity and authentication immediately with a lightweight call
        verifyAuthentication();
    }

    /**
     * Verify that the configured API token can actually authenticate against Proxmox
     * by calling the lightweight /api2/json/version endpoint.
     */
    private void verifyAuthentication() throws IOException {
        String versionUrl = serverConfig.getHost() + "/api2/json/version";
        Request request = new Request.Builder()
                .url(versionUrl)
                .get()
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 401) {
                okhttp3.ResponseBody errorBody = response.body();
                String detail = errorBody != null ? errorBody.string() : "";
                throw new IOException("Proxmox API authentication failed (HTTP 401). "
                        + "Check that the Proxmox API Token credential fields are correct "
                        + "(username, realm, token identifier, token secret). "
                        + "Proxmox response: " + detail);
            }
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody errorBody = response.body();
                String detail = errorBody != null ? errorBody.string() : "";
                throw new IOException("Proxmox API connectivity check failed with HTTP " + response.code());
            }
            LOGGER.log(
                    Level.INFO, "Proxmox API authentication verified successfully against " + serverConfig.getHost());
        }
    }

    static String resolveApiToken(String credentialId) throws IOException {
        if (credentialId == null || credentialId.isBlank()) {
            throw new IOException("A Proxmox API token credential must be configured");
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            throw new IOException("Jenkins instance is not available to resolve Proxmox credentials");
        }

        ProxmoxApiTokenCredentials proxmoxCredentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        ProxmoxApiTokenCredentials.class, jenkins, ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialId));

        if (proxmoxCredentials == null) {
            throw new IOException("Unable to find Proxmox API token credential with ID: " + credentialId);
        }

        String tokenSecret = proxmoxCredentials.getTokenSecret() == null
                ? ""
                : proxmoxCredentials.getTokenSecret().getPlainText().strip();
        return proxmoxCredentials.getUsername().strip()
                + "@"
                + proxmoxCredentials.getRealm().strip()
                + "!"
                + proxmoxCredentials.getTokenId().strip()
                + "="
                + tokenSecret;
    }

    /**
     * Get the next available VM ID from Proxmox cluster.
     * Proxmox VM IDs must be positive integers; this avoids conflicts with existing VMs.
     *
     * @return next available VM ID as an integer string
     * @throws Exception if the query fails
     */
    public String getNextVmId() throws Exception {
        String path = serverConfig.getHost() + "/api2/json/cluster/nextid";
        Request request = new Request.Builder()
                .url(path)
                .get()
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody errorBody = response.body();
                String detail = errorBody != null ? errorBody.string() : "";
                throw new IOException("Failed to get next VM ID: HTTP " + response.code());
            }
            okhttp3.ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "{}";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("data")) {
                return json.get("data").getAsString();
            }
            throw new IOException("No data returned from /cluster/nextid");
        }
    }

    /**
     * Clone a VM from a template.
     *
     * <p>Cloud-init user-data (packages, bootstrap script) cannot be injected at clone time via
     * the Proxmox REST API — the {@code /upload} endpoint rejects {@code content=snippets}, and the
     * {@code /content} endpoint is a disk-creation API. The JNLP connection command is therefore
     * written to the guest post-boot via the QEMU guest agent; see
     * {@link #writeFileViaGuestAgent} and {@link #execCommandViaGuestAgent}.
     *
     * @param sourceVmId      Template VM ID to clone from
     * @param newVmId         New VM ID to create
     * @param newVmName       Hostname for the new VM
     * @param cloudInitScript Ignored — kept for API compatibility
     * @return UPID task ID for tracking the operation
     * @throws Exception if the clone operation fails
     */
    public String cloneVmWithCloudInit(String sourceVmId, String newVmId, String newVmName, String cloudInitScript)
            throws Exception {
        String path =
                String.format("%s/api2/json/nodes/%s/qemu/%s/clone", serverConfig.getHost(), nodeForVm, sourceVmId);
        FormBody.Builder bodyBuilder = new FormBody.Builder()
                .add("newid", newVmId)
                .add("name", newVmName)
                .add("full", "1");

        return postRequest(path, bodyBuilder.build());
    }

    // -------------------------------------------------------------------------
    // QEMU guest-agent helpers
    // -------------------------------------------------------------------------

    /**
     * Block until the QEMU guest agent is responsive inside {@code vmId}.
     * Polls the {@code /agent/ping} endpoint every 5 seconds for up to 5 minutes.
     *
     * @throws Exception if the agent does not respond within the timeout
     */
    public void waitForGuestAgent(String vmId) throws Exception {
        String path =
                String.format("%s/api2/json/nodes/%s/qemu/%s/agent/ping", serverConfig.getHost(), nodeForVm, vmId);
        int maxAttempts = 60; // 5 minutes
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url(path)
                    .post(new FormBody.Builder().build())
                    .addHeader(AUTHORIZATION_HEADER, authToken)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    LOGGER.log(Level.FINE, "QEMU guest agent is ready for VM " + vmId);
                    return;
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Guest agent ping attempt " + (attempt + 1) + ": " + e.getMessage());
            }
            LOGGER.log(Level.FINE, "Waiting for guest agent (attempt " + (attempt + 1) + "/" + maxAttempts + ")");
            Thread.sleep(5000);
        }
        throw new Exception("QEMU guest agent not available for VM " + vmId + " within timeout");
    }

    /**
     * Write {@code content} to {@code filePath} inside the guest via the QEMU guest agent.
     * Content is base64-encoded before transmission.
     *
     * @param vmId     target VM
     * @param filePath absolute path inside the guest (e.g. {@code /etc/systemd/system/foo.service})
     * @param content  text content to write
     * @throws Exception if the operation fails
     */
    public void writeFileViaGuestAgent(String vmId, String filePath, String content) throws Exception {
        String path = String.format(
                "%s/api2/json/nodes/%s/qemu/%s/agent/file-write", serverConfig.getHost(), nodeForVm, vmId);
        IOException firstError = null;

        // Some Proxmox/QGA combinations accept plain text directly.
        try {
            writeGuestAgentFile(path, filePath, content, false);
            LOGGER.log(Level.FINE, "Wrote " + filePath + " to VM " + vmId + " via guest agent (plain)");
            return;
        } catch (IOException e) {
            firstError = e;
            LOGGER.log(
                    Level.FINE,
                    "Plain guest-agent file-write failed, retrying with base64 encoding: " + e.getMessage());
        }

        // Fallback for environments that require encoded payloads.
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        try {
            writeGuestAgentFile(path, filePath, encoded, true);
            LOGGER.log(Level.FINE, "Wrote " + filePath + " to VM " + vmId + " via guest agent (base64)");
            return;
        } catch (IOException secondError) {
            throw new IOException(
                    "guest-agent file-write to "
                            + filePath
                            + " failed in plain and base64 modes. first="
                            + firstError.getMessage()
                            + ", second="
                            + secondError.getMessage(),
                    secondError);
        }
    }

    private void writeGuestAgentFile(String path, String filePath, String content, boolean encoded) throws IOException {
        FormBody.Builder form = new FormBody.Builder().add("file", filePath).add("content", content);
        if (encoded) {
            form.add("encode", "1");
        }

        Request request = new Request.Builder()
                .url(path)
                .post(form.build())
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody respBody = response.body();
                String msg = respBody != null ? respBody.string() : "";
                throw new IOException("HTTP " + response.code() + " " + msg);
            }
        }
    }

    /**
     * Execute a command inside the guest via the QEMU guest agent and wait for it to finish.
     * Command and arguments are sent as a JSON array to avoid shell-quoting issues.
     *
     * @param vmId          target VM
     * @param commandAndArgs command followed by its arguments, e.g.
     *                       {@code "systemctl", "enable", "--now", "jenkins-agent.service"}
     * @throws Exception if the request fails or the command exits non-zero
     */
    public void execCommandViaGuestAgent(String vmId, String... commandAndArgs) throws Exception {
        if (commandAndArgs == null
                || commandAndArgs.length == 0
                || commandAndArgs[0] == null
                || commandAndArgs[0].isBlank()) {
            throw new IllegalArgumentException("commandAndArgs must include a non-empty command");
        }

        String path =
                String.format("%s/api2/json/nodes/%s/qemu/%s/agent/exec", serverConfig.getHost(), nodeForVm, vmId);

        int pid = startGuestAgentExec(vmId, path, commandAndArgs);

        // Poll exec-status until the process exits
        String statusPath = String.format(
                "%s/api2/json/nodes/%s/qemu/%s/agent/exec-status?pid=%d", serverConfig.getHost(), nodeForVm, vmId, pid);
        int maxAttempts = 60;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Request statusReq = new Request.Builder()
                    .url(statusPath)
                    .get()
                    .addHeader(AUTHORIZATION_HEADER, authToken)
                    .build();
            try (Response response = httpClient.newCall(statusReq).execute()) {
                if (response.isSuccessful()) {
                    okhttp3.ResponseBody respBody = response.body();
                    String responseBody = respBody != null ? respBody.string() : "{}";
                    JsonObject json = gson.fromJson(responseBody, JsonObject.class);
                    if (json != null && json.has("data")) {
                        JsonObject data = json.getAsJsonObject("data");
                        if (data.has("exited") && data.get("exited").getAsInt() == 1) {
                            int exitCode =
                                    data.has("exitcode") ? data.get("exitcode").getAsInt() : 0;
                            String cmd = String.join(" ", commandAndArgs);
                            if (exitCode != 0) {
                                String stdErr = decodeExecDataField(data, "err-data");
                                String stdOut = decodeExecDataField(data, "out-data");
                                throw new IOException("Guest-agent command failed (exit " + exitCode + "): " + cmd
                                        + (stdErr.isBlank() ? "" : " | stderr: " + stdErr)
                                        + (stdOut.isBlank() ? "" : " | stdout: " + stdOut));
                            }

                            String stdErr = decodeExecDataField(data, "err-data");
                            if (!stdErr.isBlank()) {
                                LOGGER.log(Level.FINE, "Guest-agent stderr for '" + cmd + "': " + stdErr);
                            }
                            LOGGER.log(Level.FINE, "Guest-agent command OK: " + cmd);
                            return;
                        }
                    }
                }
            }
            Thread.sleep(2000);
        }
        LOGGER.log(Level.WARNING, "Guest-agent command timed out: " + String.join(" ", commandAndArgs));
        throw new IOException("Guest-agent command timed out: " + String.join(" ", commandAndArgs));
    }

    private int startGuestAgentExec(String vmId, String path, String... commandAndArgs) throws IOException {
        final int maxAttempts = 4;
        String cmd = toLegacyCommandLine(commandAndArgs);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            com.google.gson.JsonArray commandArray = new com.google.gson.JsonArray();
            for (String part : commandAndArgs) {
                commandArray.add(part);
            }

            JsonObject payload = new JsonObject();
            payload.add("command", commandArray);
            RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(path)
                    .post(body)
                    .addHeader(AUTHORIZATION_HEADER, authToken)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                okhttp3.ResponseBody respBody = response.body();
                String responseBody = respBody != null ? respBody.string() : "";

                if (!response.isSuccessful()) {
                    if (response.code() == 596 && attempt < maxAttempts) {
                        boolean pingOk = isGuestAgentPingSuccessful(vmId);
                        LOGGER.log(
                                Level.FINE,
                                "guest-agent exec returned HTTP 596 for VM " + vmId + " (attempt " + attempt + "/"
                                        + maxAttempts + ", ping=" + pingOk + ") for command: " + cmd);
                        sleepBeforeRetry(attempt);
                        continue;
                    }

                    throw new IOException("guest-agent exec failed (command='" + cmd + "', attempt=" + attempt + "/"
                            + maxAttempts + "): HTTP " + response.code() + " " + responseBody);
                }

                JsonObject json = gson.fromJson(responseBody.isBlank() ? "{}" : responseBody, JsonObject.class);
                if (json == null
                        || !json.has("data")
                        || !json.getAsJsonObject("data").has("pid")) {
                    throw new IOException("No pid returned from guest-agent exec");
                }
                return json.getAsJsonObject("data").get("pid").getAsInt();
            }
        }

        throw new IOException("guest-agent exec failed after retries for command: " + cmd);
    }

    private void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(1000L * attempt * 2L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying guest-agent exec", e);
        }
    }

    private boolean isGuestAgentPingSuccessful(String vmId) {
        String pingPath =
                String.format("%s/api2/json/nodes/%s/qemu/%s/agent/ping", serverConfig.getHost(), nodeForVm, vmId);
        Request request = new Request.Builder()
                .url(pingPath)
                .post(new FormBody.Builder().build())
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "guest-agent ping probe failed for VM " + vmId + ": " + e.getMessage());
            return false;
        }
    }

    private String toLegacyCommandLine(String... commandAndArgs) {
        StringBuilder command = new StringBuilder();
        for (int i = 0; i < commandAndArgs.length; i++) {
            if (i > 0) {
                command.append(' ');
            }
            command.append(commandAndArgs[i]);
        }
        return command.toString();
    }

    private String decodeExecDataField(JsonObject data, String fieldName) {
        if (data == null || !data.has(fieldName) || data.get(fieldName).isJsonNull()) {
            return "";
        }
        String base64 = data.get(fieldName).getAsString();
        if (base64.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8).strip();
        } catch (IllegalArgumentException e) {
            return base64;
        }
    }

    /**
     * Start a VM.
     *
     * @param vmId VM ID to start
     * @return UPID task ID for tracking the operation
     * @throws Exception if the start operation fails
     */
    public String startVm(String vmId) throws Exception {
        String path =
                String.format("%s/api2/json/nodes/%s/qemu/%s/status/start", serverConfig.getHost(), nodeForVm, vmId);

        return postRequest(path, new FormBody.Builder().build());
    }

    /**
     * Stop a VM.
     *
     * @param vmId VM ID to stop
     * @return UPID task ID for tracking the operation
     * @throws Exception if the stop operation fails
     */
    public String stopVm(String vmId) throws Exception {
        String path =
                String.format("%s/api2/json/nodes/%s/qemu/%s/status/stop", serverConfig.getHost(), nodeForVm, vmId);

        return postRequest(path, new FormBody.Builder().build());
    }

    /**
     * Delete a VM.
     *
     * @param vmId VM ID to delete
     * @return UPID task ID for tracking the operation
     * @throws Exception if the delete operation fails
     */
    public String deleteVm(String vmId) throws Exception {
        String path = String.format("%s/api2/json/nodes/%s/qemu/%s", serverConfig.getHost(), nodeForVm, vmId);

        Request request = new Request.Builder()
                .url(path)
                .delete()
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody body = response.body();
                String responseBody = body != null ? body.string() : "";
                throw new IOException("HTTP " + response.code() + ": " + responseBody);
            }

            okhttp3.ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "{}";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse != null && jsonResponse.has("data")) {
                return jsonResponse.get("data").getAsString();
            }
            throw new Exception("No UPID returned from delete operation");
        }
    }

    /**
     * List QEMU VMs on the configured Proxmox node.
     */
    public List<ProxmoxVmSummary> listNodeVms() throws IOException {
        String path = String.format("%s/api2/json/nodes/%s/qemu", serverConfig.getHost(), nodeForVm);
        Request request = new Request.Builder()
                .url(path)
                .get()
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody body = response.body();
                String responseBody = body != null ? body.string() : "";
                throw new IOException("Failed to list VMs: HTTP " + response.code() + " " + responseBody);
            }

            okhttp3.ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "{}";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            List<ProxmoxVmSummary> vms = new ArrayList<>();
            if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
                return vms;
            }

            for (com.google.gson.JsonElement elem : json.getAsJsonArray("data")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject vm = elem.getAsJsonObject();
                String vmId = vm.has("vmid") && !vm.get("vmid").isJsonNull()
                        ? vm.get("vmid").getAsString()
                        : null;
                if (vmId == null || vmId.isBlank()) {
                    continue;
                }
                String name = vm.has("name") && !vm.get("name").isJsonNull()
                        ? vm.get("name").getAsString()
                        : null;
                String status = vm.has(STATUS_FIELD) && !vm.get(STATUS_FIELD).isJsonNull()
                        ? vm.get(STATUS_FIELD).getAsString()
                        : null;
                String tags = vm.has("tags") && !vm.get("tags").isJsonNull()
                        ? vm.get("tags").getAsString()
                        : "";
                vms.add(new ProxmoxVmSummary(vmId, name, status, tags));
            }
            return vms;
        }
    }

    /**
     * Poll a task by UPID to check completion status.
     *
     * @param upid UPID task identifier
     * @return true if task is complete, false if still running
     * @throws Exception if the query fails
     */
    public boolean isTaskComplete(String upid) throws Exception {
        // Parse UPID to extract node and task ID
        // Format: UPID:node:pid:starttime:type:id:user
        String[] parts = upid.split(":");
        if (parts.length < 7) {
            throw new IllegalArgumentException("Invalid UPID format: " + upid);
        }
        String node = parts[1];

        String path = String.format("%s/api2/json/nodes/%s/tasks/%s/status", serverConfig.getHost(), node, upid);

        Request request = new Request.Builder()
                .url(path)
                .get()
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody body = response.body();
                String responseBody = body != null ? body.string() : "";
                LOGGER.log(
                        Level.FINE,
                        "Failed to get task status for " + upid + ": HTTP " + response.code() + " " + responseBody);
                return false;
            }

            okhttp3.ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "{}";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse != null && jsonResponse.has("data")) {
                JsonObject data = jsonResponse.getAsJsonObject("data");
                String status =
                        data.has(STATUS_FIELD) && !data.get(STATUS_FIELD).isJsonNull()
                                ? data.get(STATUS_FIELD).getAsString()
                                : "unknown";
                String exitStatus = data.has(EXITSTATUS_FIELD)
                                && !data.get(EXITSTATUS_FIELD).isJsonNull()
                        ? data.get(EXITSTATUS_FIELD).getAsString()
                        : "null";
                String endTime =
                        data.has(ENDTIME_FIELD) && !data.get(ENDTIME_FIELD).isJsonNull()
                                ? data.get(ENDTIME_FIELD).getAsString()
                                : "null";
                LOGGER.log(Level.FINE, "Task status for {0}: status={1}, exitstatus={2}, endtime={3}", new Object[] {
                    upid, status, exitStatus, endTime
                });
                return isCompletedTaskStatus(data);
            }
            LOGGER.log(Level.FINE, "Task status for " + upid + ": response contained no data block");
            return false;
        }
    }

    /**
     * Determine whether a Proxmox task status payload represents a finished task.
     *
     * <p>Most tasks report completion through a non-null {@code endtime}. Some Proxmox
     * installations instead report terminal success as {@code status=stopped} with
     * {@code exitstatus=OK} while leaving {@code endtime} null. This helper treats both
     * shapes as complete.
     */
    static boolean isCompletedTaskStatus(JsonObject data) {
        if (data == null) {
            return false;
        }

        if (data.has(ENDTIME_FIELD) && !data.get(ENDTIME_FIELD).isJsonNull()) {
            return true;
        }

        String status = data.has(STATUS_FIELD) && !data.get(STATUS_FIELD).isJsonNull()
                ? data.get(STATUS_FIELD).getAsString()
                : null;
        String exitStatus =
                data.has(EXITSTATUS_FIELD) && !data.get(EXITSTATUS_FIELD).isJsonNull()
                        ? data.get(EXITSTATUS_FIELD).getAsString()
                        : null;

        return "stopped".equalsIgnoreCase(status) && "OK".equalsIgnoreCase(exitStatus);
    }

    /**
     * Make a POST request to the Proxmox API.
     */
    private String postRequest(String path, okhttp3.RequestBody body) throws IOException {
        Request request = new Request.Builder()
                .url(path)
                .post(body)
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        LOGGER.log(Level.FINE, "POST request to: " + path);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody errorBody = response.body();
                String errorMessage = errorBody != null ? errorBody.string() : "";
                LOGGER.log(Level.WARNING, "API request failed with HTTP " + response.code() + ": " + errorMessage);
                if (response.code() == 401) {
                    LOGGER.log(
                            Level.WARNING,
                            "Authentication failed (HTTP 401) - Verify Proxmox API Token credentials are correct (username, realm, token identifier, token secret)");
                }
                if (response.code() == 403) {
                    LOGGER.log(
                            Level.WARNING,
                            "Permission denied (HTTP 403) - API tokens in Proxmox have 'Privilege Separation' enabled by default. "
                                    + "Fix: In Proxmox go to Datacenter → Permissions → API Tokens → edit your token → uncheck 'Privilege Separation'. "
                                    + "Alternatively, assign the PVEAdmin role directly to the token under Datacenter → Permissions → Add → API Token Permission.");
                }
                throw new IOException("HTTP " + response.code() + ": " + errorMessage);
            }

            okhttp3.ResponseBody respBody = response.body();
            String responseBody = respBody != null ? respBody.string() : "{}";
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse != null && jsonResponse.has("data")) {
                return jsonResponse.get("data").getAsString();
            }
            throw new IOException("No UPID returned from API call");
        }
    }

    /**
     * Wait for and return the first non-loopback IPv4 address of a running VM via the QEMU
     * guest agent network-get-interfaces API. Polls every 5 seconds for up to 5 minutes.
     *
     * @param vmId VM ID to query
     * @return first non-loopback IPv4 address
     * @throws Exception if the address cannot be resolved within the timeout
     */
    public String getVmIpAddress(String vmId) throws Exception {
        String path = String.format(
                "%s/api2/json/nodes/%s/qemu/%s/agent/network-get-interfaces", serverConfig.getHost(), nodeForVm, vmId);

        int maxAttempts = 60; // 5 minutes with 5-second polls
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Request request = new Request.Builder()
                    .url(path)
                    .get()
                    .addHeader(AUTHORIZATION_HEADER, authToken)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    okhttp3.ResponseBody body = response.body();
                    String responseBody = body != null ? body.string() : "{}";
                    JsonObject json = gson.fromJson(responseBody, JsonObject.class);
                    String ip = extractFirstIpv4(json);
                    if (ip != null) {
                        LOGGER.log(Level.FINE, "VM " + vmId + " IP address resolved: " + ip);
                        return ip;
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Guest agent not ready (attempt " + (attempt + 1) + "): " + e.getMessage());
            }

            LOGGER.log(Level.FINE, "Waiting for VM IP address (attempt " + (attempt + 1) + "/" + maxAttempts + ")");
            Thread.sleep(5000);
        }

        throw new Exception("VM " + vmId + " did not report an IP address within timeout");
    }

    /**
     * Parse the first non-loopback IPv4 address from a Proxmox guest-agent
     * network-get-interfaces response.
     */
    private String extractFirstIpv4(JsonObject json) {
        if (json == null || !json.has("data")) return null;
        JsonObject data = json.getAsJsonObject("data");
        if (!data.has("result")) return null;

        com.google.gson.JsonArray interfaces = data.getAsJsonArray("result");
        for (com.google.gson.JsonElement elem : interfaces) {
            JsonObject iface = elem.getAsJsonObject();
            String name = iface.has("name") ? iface.get("name").getAsString() : "";
            if ("lo".equals(name)) continue;
            if (!iface.has("ip-addresses")) continue;

            for (com.google.gson.JsonElement ipElem : iface.getAsJsonArray("ip-addresses")) {
                JsonObject ipObj = ipElem.getAsJsonObject();
                String type = ipObj.has("ip-address-type")
                        ? ipObj.get("ip-address-type").getAsString()
                        : "";
                if ("ipv4".equals(type) && ipObj.has("ip-address")) {
                    String addr = ipObj.get("ip-address").getAsString();
                    if (!addr.startsWith("127.")) {
                        return addr;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Apply cloud-init parameters to a cloned VM via the Proxmox VM config API.
     *
     * <p>Sets {@code ciuser}, {@code sshkeys}, and {@code ipconfig0=dhcp} in a single
     * {@code PUT .../config} call. Proxmox runs this asynchronously when it needs to regenerate
     * the cloud-init ISO, returning a task UPID and locking the VM. This method waits for that
     * task to finish before returning so that the caller can safely start the VM afterward.
     *
     * @param vmId         VM ID to configure
     * @param ciUser       Linux username to create via cloud-init (e.g. {@code jenkins})
     * @param sshPublicKey SSH public key content (e.g. {@code ssh-rsa AAAA... user@host})
     * @param tags         Proxmox tags (semicolon-delimited), may be blank
     * @throws Exception if the configuration request or task wait fails
     */
    public void configureVmCloudInit(String vmId, String ciUser, String sshPublicKey, String tags) throws Exception {
        String path = String.format("%s/api2/json/nodes/%s/qemu/%s/config", serverConfig.getHost(), nodeForVm, vmId);

        String normalizedSshKey = null;
        if (sshPublicKey != null && !sshPublicKey.isBlank()) {
            normalizedSshKey = normalizeSshPublicKey(sshPublicKey);
        }

        RequestBody requestBody = buildCloudInitRequestBody(ciUser, normalizedSshKey, tags, false);

        // PUT /config is asynchronous when Proxmox regenerates the cloud-init ISO.
        // It returns a task UPID and locks the VM; we must wait for the task before
        // calling startVm(), otherwise the start will fail with "VM is locked".
        String taskUpid;
        try {
            taskUpid = putRequestReturningTask(path, requestBody);
        } catch (IOException firstError) {
            if (normalizedSshKey != null && isInvalidUrlEncodedSshKeyError(firstError)) {
                LOGGER.log(
                        Level.WARNING,
                        "Proxmox rejected sshkeys as invalid urlencoded string; retrying with compatibility encoding for VM "
                                + vmId);
                RequestBody fallbackBody = buildCloudInitRequestBody(ciUser, normalizedSshKey, tags, true);
                taskUpid = putRequestReturningTask(path, fallbackBody);
            } else {
                throw firstError;
            }
        }
        if (taskUpid != null && !taskUpid.isBlank()) {
            LOGGER.log(Level.FINE, "Waiting for cloud-init config task " + taskUpid + " on VM " + vmId);
            waitForTask(taskUpid);
        }
        LOGGER.log(Level.FINE, "Configured VM params (ciuser, sshkeys, ipconfig0, tags) for VM " + vmId);
    }

    private RequestBody buildCloudInitRequestBody(
            String ciUser, String normalizedSshKey, String tags, boolean doubleEncodeSshKeys) {
        StringBuilder form = new StringBuilder();
        if (ciUser != null && !ciUser.isBlank()) {
            appendFormField(form, "ciuser", ciUser.trim());
        }

        if (normalizedSshKey != null && !normalizedSshKey.isBlank()) {
            if (doubleEncodeSshKeys) {
                String onceEncoded = encodeFormComponent(normalizedSshKey);
                appendFormField(form, "sshkeys", onceEncoded);
            } else {
                appendFormField(form, "sshkeys", normalizedSshKey);
            }
        }

        appendFormField(form, "ipconfig0", "ip=dhcp");

        if (tags != null && !tags.isBlank()) {
            appendFormField(form, "tags", tags.trim());
        }

        return RequestBody.create(form.toString(), MediaType.get("application/x-www-form-urlencoded; charset=utf-8"));
    }

    private boolean isInvalidUrlEncodedSshKeyError(IOException error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage();
        return message.contains("sshkeys") && message.contains("invalid urlencoded string");
    }

    private static void appendFormField(StringBuilder form, String key, String value) {
        if (value == null) {
            return;
        }
        if (form.length() > 0) {
            form.append('&');
        }
        form.append(encodeFormComponent(key)).append('=').append(encodeFormComponent(value));
    }

    private static String encodeFormComponent(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /**
     * Normalize pasted SSH public key content so wrapped base64 lines from textareas are repaired.
     * Proxmox expects a single-line OpenSSH key (or newline-delimited keys), not arbitrary wrapped chunks.
     */
    static String normalizeSshPublicKey(String sshPublicKey) {
        if (sshPublicKey == null) {
            return "";
        }

        String compact =
                sshPublicKey.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (compact.isBlank()) {
            return compact;
        }

        String[] parts = compact.split(" ");
        if (parts.length <= 3) {
            return compact;
        }

        String keyType = parts[0];
        if (!isOpenSshKeyType(keyType)) {
            return compact;
        }

        boolean trailingComment = !isLikelyBase64Token(parts[parts.length - 1]);
        int base64EndExclusive = trailingComment ? parts.length - 1 : parts.length;

        StringBuilder base64Builder = new StringBuilder();
        for (int i = 1; i < base64EndExclusive; i++) {
            base64Builder.append(parts[i]);
        }

        String normalized = keyType + " " + base64Builder;
        if (trailingComment) {
            normalized += " " + parts[parts.length - 1];
        }
        return normalized;
    }

    private static boolean isOpenSshKeyType(String keyType) {
        return keyType.startsWith("ssh-")
                || keyType.startsWith("ecdsa-")
                || keyType.startsWith("sk-")
                || "ed25519".equals(keyType)
                || "rsa".equals(keyType);
    }

    private static boolean isLikelyBase64Token(String token) {
        return token != null && token.matches("[A-Za-z0-9+/=]+$");
    }

    /**
     * PUT request that captures and returns the task UPID from the response body (or {@code null}
     * for synchronous responses where {@code data} is {@code null}).
     */
    private String putRequestReturningTask(String path, okhttp3.RequestBody body) throws IOException {
        Request request = new Request.Builder()
                .url(path)
                .put(body)
                .addHeader(AUTHORIZATION_HEADER, authToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                okhttp3.ResponseBody errorBody = response.body();
                String errorMessage = errorBody != null ? errorBody.string() : "";
                throw new IOException("PUT " + path + " failed with HTTP " + response.code() + ": " + errorMessage);
            }
            okhttp3.ResponseBody respBody = response.body();
            String responseBody = respBody != null ? respBody.string() : "{}";
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("data") && !json.get("data").isJsonNull()) {
                return json.get("data").getAsString();
            }
            return null; // synchronous update, no background task
        }
    }

    /**
     * Poll a task UPID until it finishes (up to 5 minutes).
     *
     * @throws Exception if the task does not complete within the timeout
     */
    private void waitForTask(String upid) throws Exception {
        int maxAttempts = 60; // 5 minutes at 5-second intervals
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isTaskComplete(upid)) {
                return;
            }
            Thread.sleep(5000);
        }
        throw new Exception("Timed out waiting for task: " + upid);
    }

    /**
     * Close the client connection.
     */
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
    }
}
