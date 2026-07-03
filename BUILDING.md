## Building from Source

If you want to run the latest code or make changes, build locally instead.

### Repository layout

```
vork-server/
├── Dockerfile
├── docker-compose.yml
└── src/
```

### Clone and build

```bash
# Clone the repo
git clone https://github.com/justvork/vork-server.git

# Start from the vork-server directory
cd vork-server
mvn spring-boot:run
```

For container-based source builds, see [DOCKER.md](DOCKER.md).
