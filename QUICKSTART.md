## Quick Start — Docker Hub

The simplest way to run Vork. The pre-built image is published to Docker Hub as `justvork/vork-server`.

### 1. Run the container

Vork runs out of the box with Nitrite (embedded local datastore), so no external database is required for first startup.

```bash
docker run -d \
  --name vork-server \
  -p 8080:8080 \
  -p 8443:8443 \
  -v vork_conf:/app/conf.d \
  justvork/vork-server:latest
```

### 2. Open Vork

Navigate to **https://localhost:8443** (accept the self-signed cert warning on first visit).

On first launch you will be guided through the setup wizard to connect your chosen database backend, configure notification providers, and create the admin account. After setup completes, the AI chat interface is ready.

### 3. Optional: run with external database backends

For compose examples and deployment variants, use the dedicated pages:

- [MongoDB](MONGODB.md)
- [Redis](REDDIS.md)
- [Couchbase](COUCHBASE.md)
