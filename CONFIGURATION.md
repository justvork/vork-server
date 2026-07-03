# Configuration

## SSL certificates

On first startup Vork generates a self-signed certificate in `conf.d/ssl/`. To use a real certificate:

1. Place your `certificate.pem` (full chain) and `private-key.pem` in the cert directory before starting the container, **or**
2. Use the **SSL Certificate** settings page (`/settings/ssl-certificate`) to request a Let's Encrypt certificate once the instance is running and publicly reachable on port 80.

---

## Ports

| Port | Protocol | Purpose |
|---|---|---|
| `8080` | HTTP | Redirects all traffic to HTTPS. Required for Let's Encrypt HTTP-01 challenge. |
| `8443` | HTTPS | Main application port. |
