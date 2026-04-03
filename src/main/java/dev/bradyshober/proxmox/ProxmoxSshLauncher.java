package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSH-based computer launcher for Proxmox-provisioned agents.
 * Agents connect outbound to Jenkins via SSH.
 */
public class ProxmoxSshLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(ProxmoxSshLauncher.class.getName());

    private final String ipAddress;
    private final int sshPort;
    private final String username;
    private final String privateKey;

    public ProxmoxSshLauncher(String ipAddress, int sshPort, String username, String privateKey) {
        this.ipAddress = ipAddress;
        this.sshPort = sshPort;
        this.username = username;
        this.privateKey = privateKey;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        log.println("Launching Proxmox agent via SSH");
        log.println("Target: " + username + "@" + ipAddress + ":" + sshPort);

        try {
            // Wait for SSH to be ready
            waitForSshReady(log);

            // Execute the agent startup script
            String command = String.format(
                    "ssh -i %s -o StrictHostKeyChecking=no -p %d %s@%s '/home/jenkins/start-agent.sh'",
                    privateKey, sshPort, username, ipAddress);

            log.println("Executing: " + command);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            Process process = pb.start();

            // Stream output
            StreamCopier.copy(process.getInputStream(), log);
            StreamCopier.copy(process.getErrorStream(), log);

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.println("Agent launched successfully via SSH");
            } else {
                log.println("SSH launch failed with exit code: " + exitCode);
                throw new IOException("SSH launch failed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to launch SSH agent", e);
            log.println("Failed to launch agent: " + e.getMessage());
            throw new IOException(e);
        }
    }

    /**
     * Wait for SSH service to be ready on the target VM.
     */
    private void waitForSshReady(PrintStream log) throws InterruptedException, IOException {
        int maxAttempts = 60; // 5 minutes
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                // Test SSH connectivity
                ProcessBuilder pb = new ProcessBuilder(
                        "ssh",
                        "-o",
                        "ConnectTimeout=2",
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-p",
                        String.valueOf(sshPort),
                        username + "@" + ipAddress,
                        "echo ok");
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    log.println("SSH is ready");
                    return;
                }
            } catch (IOException e) {
                // SSH not ready yet, continue waiting
                LOGGER.log(Level.FINE, "SSH not ready yet on attempt " + (attempt + 1));
            }

            log.println("Waiting for SSH to be ready (" + (attempt + 1) + "/" + maxAttempts + ")");
            Thread.sleep(5000); // Wait 5 seconds
            attempt++;
        }

        throw new IOException("SSH did not become ready in time");
    }

    // Getters
    public String getIpAddress() {
        return ipAddress;
    }

    public int getSshPort() {
        return sshPort;
    }

    public String getUsername() {
        return username;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "Proxmox SSH Launcher";
        }
    }

    /**
     * Helper to copy stream output.
     */
    private static class StreamCopier {
        static void copy(java.io.InputStream input, PrintStream output) {
            new Thread(() -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.println(line);
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    })
                    .start();
        }
    }
}
