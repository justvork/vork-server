# Vork with Redis

Use this guide when you want Vork to run with Redis instead of the default embedded Nitrite backend.

## When to use Redis

- Existing Redis infrastructure
- Fast key/value access patterns
- Centralized external datastore

## Docker Compose Example

Create a compose file and run Vork with Redis:

```yaml
services:
  redis:
    image: redis:7
    restart: unless-stopped
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  vork:
    image: justvork/vork-server:latest
    restart: unless-stopped
    depends_on:
      redis:
        condition: service_healthy
    ports:
      - "8080:8080"
      - "8443:8443"
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      # Optional auth
      # REDIS_PASSWORD: your-password
    volumes:
      - vork_conf:/app/conf.d

volumes:
  redis_data:
  vork_conf:
```

Start:

```bash
docker compose up -d
```

## Setup Wizard Configuration

During first-run setup:

1. Open https://localhost:8443
2. Complete admin user creation
3. In the setup wizard database step, select Redis
4. Enter host, port, and optional password
5. Save and continue

Database choice is made during setup. If you want to use Redis, start a fresh setup flow and choose Redis there.

## Notes

- Keep conf.d mounted as a volume so your settings persist.
- If Redis auth is enabled, configure password in both Redis and Vork settings.
