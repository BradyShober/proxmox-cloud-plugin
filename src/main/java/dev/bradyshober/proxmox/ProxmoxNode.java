package dev.bradyshober.proxmox;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Ephemeral Jenkins node backed by a Proxmox VM.
 */
public class ProxmoxNode extends Slave implements EphemeralNode {

    private final int maxBuildsPerAgent;
    /** -1 means unlimited. */
    private int buildsRemaining;

    @DataBoundConstructor
    public ProxmoxNode(
            String name,
            String nodeDescription,
            String remoteFS,
            String numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties)
            throws IOException, Descriptor.FormException {
        this(
                name,
                nodeDescription,
                remoteFS,
                parseExecutors(numExecutors),
                mode,
                labelString,
                launcher,
                retentionStrategy,
                nodeProperties,
                inferMaxBuilds(retentionStrategy));
    }

    public ProxmoxNode(
            String nodeName,
            String nodeDescription,
            String remoteFs,
            int numExecutors,
            Mode mode,
            String label,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy,
            int maxBuildsPerAgent)
            throws IOException, Descriptor.FormException {
        this(
                nodeName,
                nodeDescription,
                remoteFs,
                numExecutors,
                mode,
                label,
                launcher,
                retentionStrategy,
                Collections.emptyList(),
                maxBuildsPerAgent);
    }

    public ProxmoxNode(
            String nodeName,
            String nodeDescription,
            String remoteFs,
            int numExecutors,
            Mode mode,
            String label,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            int maxBuildsPerAgent)
            throws IOException, Descriptor.FormException {
        //noinspection deprecation
        super(
                nodeName,
                nodeDescription,
                remoteFs,
                numExecutors,
                mode,
                label,
                launcher,
                retentionStrategy,
                nodeProperties);
        this.maxBuildsPerAgent = Math.max(0, maxBuildsPerAgent);
        this.buildsRemaining = this.maxBuildsPerAgent > 0 ? this.maxBuildsPerAgent : -1;
    }

    private static int parseExecutors(String numExecutors) {
        try {
            if (numExecutors == null) {
                return 1;
            }
            return Math.max(1, Integer.parseInt(numExecutors));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static int inferMaxBuilds(RetentionStrategy<?> retentionStrategy) {
        if (retentionStrategy instanceof ProxmoxRetentionStrategy strategy) {
            return strategy.getMaxBuildsPerAgent();
        }
        return 0;
    }

    public int getMaxBuildsPerAgent() {
        return maxBuildsPerAgent;
    }

    public int getEffectiveMaxBuildsPerAgent() {
        int effective = maxBuildsPerAgent;

        RetentionStrategy<?> retention = getRetentionStrategy();
        if (retention instanceof ProxmoxRetentionStrategy proxmoxRetention) {
            effective = Math.max(effective, proxmoxRetention.getMaxBuildsPerAgent());

            if (effective <= 0) {
                String cloudName = proxmoxRetention.getCloudName();
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins != null && cloudName != null && !cloudName.isBlank()) {
                    Cloud cloud = jenkins.clouds.getByName(cloudName);
                    if (cloud instanceof ProxmoxCloud proxmoxCloud) {
                        effective = Math.max(0, proxmoxCloud.getMaxBuildsPerAgent());
                    }
                }
            }
        }

        return Math.max(0, effective);
    }

    public synchronized int getBuildsRemaining() {
        return buildsRemaining;
    }

    public synchronized int decrementBuildsRemaining() {
        if (buildsRemaining > 0) {
            buildsRemaining--;
        }
        return buildsRemaining;
    }

    public synchronized void setBuildsRemaining(int buildsRemaining) {
        int effectiveMaxBuilds = getEffectiveMaxBuildsPerAgent();
        if (effectiveMaxBuilds <= 0) {
            this.buildsRemaining = -1;
            return;
        }
        this.buildsRemaining = Math.max(0, Math.min(effectiveMaxBuilds, buildsRemaining));
    }

    @Override
    public String getDisplayName() {
        int effectiveMaxBuilds = getEffectiveMaxBuildsPerAgent();
        if (effectiveMaxBuilds <= 0) {
            return getNodeName();
        }

        int remaining = getBuildsRemaining();
        if (remaining < 0 || remaining > effectiveMaxBuilds) {
            remaining = effectiveMaxBuilds;
            setBuildsRemaining(remaining);
        }
        return getNodeName() + " (" + remaining + " builds remaining)";
    }

    @Override
    public Computer createComputer() {
        return new ProxmoxNodeComputer(this);
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Override
        public String getDisplayName() {
            return "Proxmox Node";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
