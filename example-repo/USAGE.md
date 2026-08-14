# Using This Local Example Repository

This repository is designed for local Hub testing.

## Point Vork to this repository

Set the additional repository environment variable to a `file:///` URL:

```bash
export VORK_ADDITIONAL_REPOSITORIES="Examples=file:///Users/lee/vork/vork-prototype/example-repo"
```

Optional strict validation:

```bash
export VORK_ADDITIONAL_REPOSITORIES_FAIL_FAST=true
```

Then start the app.

## What is included

1. Contribution-layout artifacts:
- `agents/<groupId>/<artifactId>/<version>/agent.json`
- `jobs/<groupId>/<artifactId>/<version>/job.json`
- `surfaces/<groupId>/<artifactId>/<version>/surface.json`
- `skills/<groupId>/<artifactId>/<version>/skills.json`
- `reflections/<groupId>/<artifactId>/<version>/reflections.json`
- `oauth-templates/<clientName>.json`

2. Install API payloads:
- `install/agents/*.json` for `POST /api/agents/import`
- `install/jobs/*.json` for `POST /api/jobs/import`
- `install/surfaces/*.zip` for `POST /api/surfaces/import`
- `install/skills/*.json` for `POST /api/skill-groups/import`
- `install/reflections/*.json` for `POST /api/reflection-groups/import`
- `install/oauth-templates/*.json` for `POST /api/oauth-templates/import`

3. Discovery metadata:
- `hub-index.json`
- `hub-catalog.json`
- `categories.json`

Each artifact also includes placeholder documentation and logo files for display testing.
