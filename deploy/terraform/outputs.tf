output "core_public_ip" { value = digitalocean_droplet.core.ipv4_address }
output "core_private_ip" { value = digitalocean_droplet.core.ipv4_address_private }
output "sensor_public_ips" { value = { for name, node in digitalocean_droplet.sensor : name => node.ipv4_address } }
output "dashboard_tunnel" { value = "ssh -L 8088:127.0.0.1:8088 root@${digitalocean_droplet.core.ipv4_address}" }
