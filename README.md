# Proxmox Cloud

## Introduction

This Jenkins plugin dynamically provisions build agents on Proxmox VE by cloning VMs from a template. Agents can connect via SSH or WebSocket, allowing flexible deployment patterns in virtualized environments.

## Getting started

1. Configure Proxmox Cloud in Jenkins **Manage Jenkins** → **Configure System** → **Cloud**
2. Add a Proxmox Cloud instance with:
   - **Proxmox Host**: URL of your Proxmox VE server (e.g., `https://proxmox.example.com:8006`)
   - **API Token Credential**: Select a Jenkins **Secret text** credential containing the Proxmox API token value
   - **Template VM ID**: The source VM template to clone from
   - **Instance Limits**: Maximum concurrent instances
   - **Launcher Strategy**: Choose SSH or WebSocket connectivity
   - **Agent Labels**: Labels to assign to provisioned agents (space-separated, default: `proxmox`)

VMs will be automatically cloned and started when the Jenkins queue has pending builds. Cloud-init is injected at clone time to bootstrap the agent software.

## Connection Strategies

- **SSH**: Agents connect outbound to Jenkins via SSH protocol (traditional)
- **WebSocket**: Agents connect via WebSocket remoting (cloud-native, firewall-friendly)

## Issues

TODO Decide where you're going to host your issues, the default is Jenkins JIRA, but you can also enable GitHub issues,
If you use GitHub issues there's no need for this section; else add the following line:

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

TODO review the default [CONTRIBUTING](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) file and make sure it is appropriate for your plugin, if not then add your own one adapted from the base file

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

