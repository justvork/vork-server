# Vork with MongoDB

Use this guide when you want Vork to run with MongoDB instead of the default embedded Nitrite backend.

## When to use MongoDB

- Multi-container deployment
- Shared datastore across environments
- External backup/replication strategy

## Docker Compose Example

Create a compose file and run Vork with MongoDB:

```yaml
services:
  mongodb:
    image: mongo:8
    restart: unless-stopped
    volumes:
      - mongodb_data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
      interval: 10s
      timeout: 5s
      retries: 5

  vork:
    image: justvork/vork-server:latest
    restart: unless-stopped
    depends_on:
      mongodb:
        condition: service_healthy
    ports:
      - "8080:8080"
      - "8443:8443"
    environment:
      MONGO_HOST: mongodb
      MONGO_PORT: 27017
      MONGO_DATABASE: vork
      # Optional auth
      # MONGO_USERNAME: your-user
      # MONGO_PASSWORD: your-password
    volumes:
      - vork_conf:/app/conf.d

volumes:
  mongodb_data:
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
3. In the setup wizard database step, select MongoDB
4. Enter host, port, database, and optional credentials
5. Save and continue

If you already completed setup, you can switch database settings from the settings UI and restart the container.

## Notes

- Keep conf.d mounted as a volume so your settings persist.
- If MongoDB auth is enabled, set both username and password.
