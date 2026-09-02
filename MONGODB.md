# Vork with MongoDB

Use this guide when you want Vork to run with MongoDB instead of the default embedded Nitrite backend.

## When to use MongoDB

- Multi-container deployment
- Shared datastore across environments
- External backup/replication strategy
- Managed MongoDB services such as MongoDB Atlas

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
      DB_BACKEND: mongo
      MONGO_URI: mongodb://mongodb:27017/vork
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
4. Enter your MongoDB connection URI, for example:
   - Local container: `mongodb://mongodb:27017/vork`
   - MongoDB Atlas: `mongodb+srv://user:password@cluster0.xxxxx.mongodb.net/vork`
5. Save and continue

Database choice is made during setup. If you want to use MongoDB, start a fresh setup flow and choose MongoDB there.

## Notes

- Keep conf.d mounted as a volume so your settings persist.
- The connection URI can include username, password, database, replica sets, and TLS options.
- For MongoDB Atlas, make sure your cluster's network access list allows connections from your Vork host.
