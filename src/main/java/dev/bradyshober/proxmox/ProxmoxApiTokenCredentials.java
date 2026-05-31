package dev.bradyshober.proxmox;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.util.Secret;

/**
 * Structured credential for Proxmox API token authentication.
 */
public interface ProxmoxApiTokenCredentials extends StandardCredentials {
    String getUsername();

    String getRealm();

    String getTokenId();

    Secret getTokenSecret();
}
