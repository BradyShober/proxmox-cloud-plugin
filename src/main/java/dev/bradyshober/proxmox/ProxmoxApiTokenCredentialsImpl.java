package dev.bradyshober.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.Serial;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Concrete Jenkins credential type for Proxmox API token parts.
 */
public class ProxmoxApiTokenCredentialsImpl extends BaseStandardCredentials implements ProxmoxApiTokenCredentials {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String realm;
    private final String tokenId;
    private final Secret tokenSecret;

    @DataBoundConstructor
    public ProxmoxApiTokenCredentialsImpl(
            CredentialsScope scope,
            String id,
            String description,
            String username,
            String realm,
            String tokenId,
            Secret tokenSecret) {
        super(scope, id, description);
        this.username = username == null ? "" : username.trim();
        this.realm = realm == null || realm.isBlank() ? "pam" : realm.trim();
        this.tokenId = tokenId == null ? "" : tokenId.trim();
        this.tokenSecret = tokenSecret;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getRealm() {
        return realm;
    }

    @Override
    public String getTokenId() {
        return tokenId;
    }

    @Override
    public Secret getTokenSecret() {
        return tokenSecret;
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {
        public ListBoxModel doFillRealmItems(@QueryParameter String value) {
            ListBoxModel items = new ListBoxModel();
            items.add("pam", "pam");
            items.add("pve", "pve");
            items.add("ldap", "ldap");
            items.add("ad", "ad");
            items.add("openid", "openid");
            if (value != null
                    && !value.isBlank()
                    && items.stream().noneMatch(option -> value.equals(option.value))) {
                items.add(value, value);
            }
            return items;
        }

        @Override
        public String getDisplayName() {
            return "Proxmox API Token";
        }
    }
}



