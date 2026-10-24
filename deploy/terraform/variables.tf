variable "name" {
  type    = string
  default = "cross-node-ids"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,30}$", var.name))
    error_message = "Use a short lowercase resource name."
  }
}
variable "region" {
  type    = string
  default = "lon1"
}
variable "size" {
  type    = string
  default = "s-2vcpu-4gb"
}
variable "os_image" {
  type        = string
  description = "Pinned numeric Ubuntu 24.04 image ID for your region, not a moving slug."
  validation {
    condition     = can(regex("^[0-9]+$", var.os_image))
    error_message = "Supply a pinned numeric image ID."
  }
}
variable "ssh_key_fingerprints" {
  type = list(string)
  validation {
    condition     = length(var.ssh_key_fingerprints) > 0
    error_message = "At least one existing DigitalOcean SSH key is required."
  }
}
variable "admin_cidrs" {
  type        = list(string)
  description = "Administrator IPv4 CIDRs allowed to SSH; dashboard uses an SSH tunnel."
  validation {
    condition     = length(var.admin_cidrs) > 0 && alltrue([for cidr in var.admin_cidrs : can(cidrnetmask(cidr)) && cidr != "0.0.0.0/0"])
    error_message = "Use explicit administrator IPv4 CIDRs; unrestricted SSH is not accepted."
  }
}
variable "sensor_count" {
  type    = number
  default = 2
  validation {
    condition     = contains([1, 2], var.sensor_count)
    error_message = "Use one or two sensor VMs plus the core VM."
  }
}
variable "repository_url" {
  type        = string
  description = "Public HTTPS Git repository containing these deployment files."
  validation {
    condition     = can(regex("^https://github.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(\\.git)?$", var.repository_url))
    error_message = "Use a public github.com HTTPS repository URL."
  }
}
variable "revision" {
  type        = string
  description = "Full source commit SHA; cloud-init checks out this exact revision."
  validation {
    condition     = can(regex("^[a-f0-9]{40}$", var.revision))
    error_message = "Supply a full 40-character commit SHA."
  }
}
variable "engine_image" {
  type        = string
  description = "Published engine image pinned by sha256 digest."
  validation {
    condition     = can(regex("^ghcr\\.io/.+@sha256:[a-f0-9]{64}$", var.engine_image))
    error_message = "Pin the GHCR engine image by digest."
  }
}
variable "ui_image" {
  type        = string
  description = "Published dashboard image pinned by sha256 digest."
  validation {
    condition     = can(regex("^ghcr\\.io/.+@sha256:[a-f0-9]{64}$", var.ui_image))
    error_message = "Pin the GHCR UI image by digest."
  }
}

variable "agent_image" {
  type        = string
  description = "Published Linux amd64 agent image pinned by sha256 digest."
  validation {
    condition     = can(regex("^ghcr\\.io/.+@sha256:[a-f0-9]{64}$", var.agent_image))
    error_message = "Pin the GHCR agent image by digest."
  }
}
