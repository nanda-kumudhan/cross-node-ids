terraform {
  required_version = ">= 1.9.0, < 2.0.0"
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "2.67.0" }
    tls          = { source = "hashicorp/tls", version = "4.1.0" }
    random       = { source = "hashicorp/random", version = "3.7.2" }
  }
}

provider "digitalocean" {} # DIGITALOCEAN_TOKEN from the caller's environment.
