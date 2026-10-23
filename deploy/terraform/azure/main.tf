variable "name" {
  type    = string
  default = "cross-node-ids"
}
variable "location" {
  type        = string
  description = "Azure region verified against your subscription's availability and budget."
}
variable "kubernetes_version" {
  type        = string
  description = "Exact supported AKS version for the selected region."
}
variable "vm_size" {
  type        = string
  description = "VM size verified against current quota and pricing."
}
variable "registry_name" {
  type        = string
  description = "Globally unique lowercase alphanumeric ACR name."
}
variable "github_repository" {
  type        = string
  description = "OWNER/REPOSITORY allowed to deploy through the azure GitHub environment."
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "Use OWNER/REPOSITORY."
  }
}

data "azurerm_client_config" "current" {}

resource "azurerm_resource_group" "ids" {
  name     = var.name
  location = var.location
}
resource "azurerm_container_registry" "ids" {
  name                = var.registry_name
  resource_group_name = azurerm_resource_group.ids.name
  location            = azurerm_resource_group.ids.location
  sku                 = "Basic"
  admin_enabled       = false
}
resource "azurerm_kubernetes_cluster" "ids" {
  name                              = var.name
  resource_group_name               = azurerm_resource_group.ids.name
  location                          = azurerm_resource_group.ids.location
  dns_prefix                        = var.name
  kubernetes_version                = var.kubernetes_version
  sku_tier                          = "Free"
  oidc_issuer_enabled               = true
  workload_identity_enabled         = true
  local_account_disabled            = true
  role_based_access_control_enabled = true
  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }
  default_node_pool {
    name       = "system"
    node_count = 1
    vm_size    = var.vm_size
    os_sku     = "Ubuntu"
  }
  identity { type = "SystemAssigned" }
  network_profile {
    network_plugin = "azure"
    network_policy = "azure"
  }
}
resource "azurerm_role_assignment" "pull" {
  scope                            = azurerm_container_registry.ids.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.ids.kubelet_identity[0].object_id
  skip_service_principal_aad_check = true
}
resource "azurerm_user_assigned_identity" "github" {
  name                = "${var.name}-github"
  resource_group_name = azurerm_resource_group.ids.name
  location            = azurerm_resource_group.ids.location
}
resource "azurerm_federated_identity_credential" "github" {
  name                = "github-azure-environment"
  resource_group_name = azurerm_resource_group.ids.name
  parent_id           = azurerm_user_assigned_identity.github.id
  issuer              = "https://token.actions.githubusercontent.com"
  audience            = ["api://AzureADTokenExchange"]
  subject             = "repo:${var.github_repository}:environment:azure"
}
resource "azurerm_role_assignment" "push" {
  scope                = azurerm_container_registry.ids.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
}
resource "azurerm_role_assignment" "cluster_user" {
  scope                = azurerm_kubernetes_cluster.ids.id
  role_definition_name = "Azure Kubernetes Service Cluster User Role"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
}
resource "azurerm_role_assignment" "cluster_deployer" {
  scope                = azurerm_kubernetes_cluster.ids.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
}
output "resource_group" { value = azurerm_resource_group.ids.name }
output "cluster_name" { value = azurerm_kubernetes_cluster.ids.name }
output "registry_name" { value = azurerm_container_registry.ids.name }
output "registry_server" { value = azurerm_container_registry.ids.login_server }
output "github_client_id" { value = azurerm_user_assigned_identity.github.client_id }
