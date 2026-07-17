# Docker

## Quick start (local)

```bash
cd vork-server
docker compose up --build
```

The app is available at http://localhost:8080.  
MongoDB data is persisted in the `mongodb_data` Docker volume.

AI provider selection and credentials are configured in the app setup flow/UI.
You can choose any supported provider there; no provider-specific API key
environment variable is required in this Docker guide.

### Optional `.env` overrides

If you want to override infrastructure settings for local compose, create a `.env`
file in `vork-server/` (next to `docker-compose.yml`) with values such as:

```
MONGO_HOST=mongodb
MONGO_PORT=27017
MONGO_DATABASE=vork
```

---

## Building for Docker Hub (multi-platform)

### One-time setup

Create a buildx builder that supports multi-platform emulation:

```bash
docker buildx create --name multi --driver docker-container --bootstrap --use
```

### Build and push

Run from inside `vork-server/`:

```bash
cd vork-server

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --file Dockerfile \
  --pull \
  --no-cache \
  --tag yourusername/vork-server:latest \
  --tag yourusername/vork-server:{version} \
  --push \
  .
```

Log in first if needed:

```bash
docker login
```

### Load a local copy (Apple Silicon)

`--push` bypasses the local Docker daemon. To also have a runnable local image:

```bash
docker buildx build \
  --platform linux/arm64 \
  --file Dockerfile \
  --tag yourusername/vork:latest \
  --load \
  .
```

### Verify the published manifest

```bash
docker buildx imagetools inspect yourusername/vork:latest
```

### Ensure deployments pick up the new image

If a host keeps running old layers under a mutable tag like `latest`, force pull
before recreate:

```bash
docker pull yourusername/vork-server:latest
docker compose up -d --force-recreate
```

For production, prefer immutable tags (for example `0.0.3-20260713-1`) and deploy
that exact tag.

---

## Notes

- **Full JDK required at runtime** — the app uses `javax.tools.JavaCompiler` to compile
  user-defined types on the fly, so a JRE-only image will not work.
- **First multi-platform build is slow** — `amd64` runs under QEMU on Apple Silicon;
  expect roughly twice the normal build time.
- **MongoDB connection** — `MONGO_HOST`, `MONGO_PORT`, and `MONGO_DATABASE` environment
  variables override `conf.d/database.properties`. The compose file sets these
  automatically to point at the bundled `mongodb` service.
- **Custom config** — mount your local `conf.d/` as a volume (see `docker-compose.yml`)
  to preserve settings across container rebuilds.
