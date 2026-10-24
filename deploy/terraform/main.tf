resource "digitalocean_vpc" "ids" {
  name     = var.name
  region   = var.region
  ip_range = "10.42.0.0/24"
}

resource "random_password" "postgres" {
  length  = 32
  special = false
}

resource "tls_private_key" "ca" {
  algorithm = "RSA"
  rsa_bits  = 3072
}
resource "tls_self_signed_cert" "ca" {
  private_key_pem       = tls_private_key.ca.private_key_pem
  is_ca_certificate     = true
  validity_period_hours = 720
  allowed_uses          = ["cert_signing", "crl_signing"]
  subject { common_name = "cross-node-test-ca" }
}
resource "tls_private_key" "broker" {
  algorithm = "RSA"
  rsa_bits  = 2048
}
locals {
  sensors = toset([for index in range(var.sensor_count) : "node-${index + 1}"])
}
resource "tls_private_key" "sensor" {
  for_each  = local.sensors
  algorithm = "RSA"
  rsa_bits  = 2048
}
resource "tls_cert_request" "sensor" {
  for_each        = local.sensors
  private_key_pem = tls_private_key.sensor[each.key].private_key_pem
  subject { common_name = each.key }
}
resource "tls_locally_signed_cert" "sensor" {
  for_each              = local.sensors
  cert_request_pem      = tls_cert_request.sensor[each.key].cert_request_pem
  ca_private_key_pem    = tls_private_key.ca.private_key_pem
  ca_cert_pem           = tls_self_signed_cert.ca.cert_pem
  validity_period_hours = 336
  allowed_uses          = ["digital_signature", "key_encipherment", "client_auth"]
}

# Sign the broker certificate once its VPC IP is assigned.
resource "digitalocean_droplet" "core" {
  name       = "${var.name}-core"
  image      = var.os_image
  size       = var.size
  region     = var.region
  vpc_uuid   = digitalocean_vpc.ids.id
  ssh_keys   = var.ssh_key_fingerprints
  monitoring = true
  user_data = "#cloud-config\n${yamlencode({
    package_update = true
    packages       = ["docker.io", "docker-compose-v2", "git", "chrony", "openssl"]
    bootcmd        = ["install -d -m 0700 /opt/ids", "install -d -m 0755 /opt/ids/tls/core"]
    write_files = [
      { path = "/opt/ids/tls/core/ca.crt", permissions = "0444", content = tls_self_signed_cert.ca.cert_pem },
      { path = "/opt/ids/tls/core/server.key", permissions = "0400", content = tls_private_key.broker.private_key_pem },
      { path = "/opt/ids/tls/core/ca.key", permissions = "0400", content = tls_private_key.ca.private_key_pem },
      { path = "/opt/ids/.env", permissions = "0600", content = join("\n", [
        "POSTGRES_PASSWORD=${random_password.postgres.result}", "ENGINE_IMAGE=${var.engine_image}",
        "UI_IMAGE=${var.ui_image}", "TLS_DIR=/opt/ids/tls", ""
      ]) },
      { path = "/opt/ids/start-core.sh", permissions = "0700", content = templatefile("${path.module}/start-core.sh.tftpl", {}) }
    ]
    runcmd = [
      "systemctl enable --now docker chrony",
      "git clone --no-checkout ${var.repository_url} /opt/cross-node-ids",
      "git -C /opt/cross-node-ids checkout --detach ${var.revision}",
      "/opt/ids/start-core.sh"
    ]
  })}"
}

resource "digitalocean_droplet" "sensor" {
  for_each   = local.sensors
  name       = "${var.name}-${each.key}"
  image      = var.os_image
  size       = var.size
  region     = var.region
  vpc_uuid   = digitalocean_vpc.ids.id
  ssh_keys   = var.ssh_key_fingerprints
  monitoring = true
  user_data = "#cloud-config\n${yamlencode({
    package_update = true
    packages       = ["docker.io", "docker-compose-v2", "git", "chrony"]
    bootcmd        = ["install -d -m 0700 /opt/ids", "install -d -m 0755 /opt/ids/tls/${each.key}"]
    write_files = [
      { path = "/opt/ids/tls/${each.key}/ca.crt", permissions = "0400", content = tls_self_signed_cert.ca.cert_pem },
      { path = "/opt/ids/tls/${each.key}/client.crt", permissions = "0400", content = tls_locally_signed_cert.sensor[each.key].cert_pem },
      { path = "/opt/ids/tls/${each.key}/client.key", permissions = "0400", content = tls_private_key.sensor[each.key].private_key_pem },
      { path = "/opt/ids/.env", permissions = "0600", content = join("\n", [
        "NODE_ID=${each.key}", "KAFKA_BOOTSTRAP_SERVERS=${digitalocean_droplet.core.ipv4_address_private}:9094",
        "TLS_DIR=/opt/ids/tls", "AGENT_IMAGE=${var.agent_image}", ""
      ]) }
    ]
    runcmd = [
      "systemctl enable --now docker chrony",
      "git clone --no-checkout ${var.repository_url} /opt/cross-node-ids",
      "git -C /opt/cross-node-ids checkout --detach ${var.revision}",
      "mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing",
      "cd /opt/cross-node-ids && docker compose --env-file /opt/ids/.env --project-directory . -f deploy/compose.node.yml --profile agent pull",
      "cd /opt/cross-node-ids && docker compose --env-file /opt/ids/.env --project-directory . -f deploy/compose.node.yml --profile agent up -d"
    ]
  })}"
}

resource "digitalocean_firewall" "core" {
  name        = "${var.name}-core"
  droplet_ids = [digitalocean_droplet.core.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = var.admin_cidrs
  }
  inbound_rule {
    protocol           = "tcp"
    port_range         = "9094"
    source_droplet_ids = [for node in digitalocean_droplet.sensor : node.id]
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
}
resource "digitalocean_firewall" "sensors" {
  name        = "${var.name}-sensors"
  droplet_ids = [for node in digitalocean_droplet.sensor : node.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = var.admin_cidrs
  }
  inbound_rule {
    protocol           = "tcp"
    port_range         = "22"
    source_droplet_ids = [for node in digitalocean_droplet.sensor : node.id]
  }
  inbound_rule {
    protocol           = "tcp"
    port_range         = "2222"
    source_droplet_ids = [for node in digitalocean_droplet.sensor : node.id]
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
}
