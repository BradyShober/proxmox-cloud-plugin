package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerConnector;
import hudson.slaves.ComputerConnectorDescriptor;
import hudson.slaves.ComputerLauncher;
import java.io.IOException;
import java.io.Serializable;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * SSH connector that creates SSH launchers without a fixed host.
 * The host is resolved at provisioning time and passed to launch().
 */
public class ProxmoxSSHConnector extends ComputerConnector implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int port;
    private final String credentialsId;
    private final String jvmOptions;
    private final String javaPath;
    private final String prefixStartSlaveCmd;
    private final String suffixStartSlaveCmd;
    private final int launchTimeoutSeconds;
    private final int maxNumRetries;
    private final int retryWaitTime;

    @DataBoundConstructor
    public ProxmoxSSHConnector(
            int port,
            String credentialsId,
            String jvmOptions,
            String javaPath,
            String prefixStartSlaveCmd,
            String suffixStartSlaveCmd,
            int launchTimeoutSeconds,
            int maxNumRetries,
            int retryWaitTime) {
        this.port = port > 0 ? port : 22;
        this.credentialsId = credentialsId;
        this.jvmOptions = jvmOptions;
        this.javaPath = javaPath;
        this.prefixStartSlaveCmd = prefixStartSlaveCmd;
        this.suffixStartSlaveCmd = suffixStartSlaveCmd;
        this.launchTimeoutSeconds = launchTimeoutSeconds > 0 ? launchTimeoutSeconds : 60;
        this.maxNumRetries = maxNumRetries;
        this.retryWaitTime = retryWaitTime;
    }

    @Override
    public ComputerLauncher launch(@Nonnull String host, TaskListener listener) throws IOException {
        return new SSHLauncher(
                host,
                port,
                credentialsId,
                jvmOptions,
                javaPath,
                prefixStartSlaveCmd,
                suffixStartSlaveCmd,
                launchTimeoutSeconds,
                maxNumRetries,
                retryWaitTime,
                null); // ssh host key verification strategy
    }

    public int getPort() {
        return port;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public String getPrefixStartSlaveCmd() {
        return prefixStartSlaveCmd;
    }

    public String getSuffixStartSlaveCmd() {
        return suffixStartSlaveCmd;
    }

    public int getLaunchTimeoutSeconds() {
        return launchTimeoutSeconds;
    }

    public int getMaxNumRetries() {
        return maxNumRetries;
    }

    public int getRetryWaitTime() {
        return retryWaitTime;
    }

    @Extension
    public static class DescriptorImpl extends ComputerConnectorDescriptor {
        @Override
        public String getDisplayName() {
            return "SSH (Proxmox - host resolved at provisioning time)";
        }
    }
}
