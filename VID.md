# VID Reference Pattern

## Purpose
This document is the reference implementation for applying VID-style identity and lifecycle controls across entities.

It is based on the completed Agent implementation and should be treated as the rollout template for other entities.

## Critical Scope Note
The field `systemAgent` is Agent-specific runtime behavior. It is not part of the generic VID model and should not be copied to other entities unless a new entity explicitly introduces its own system-managed concept.

Use this split when applying the pattern:

- Generic VID rules: always reusable
- Agent-only rules (`systemAgent`): do not reuse by default

## Generic VID Model (Reusable)

### Identity fields
- `groupId`
- `artifactId`
- `version`

### Lifecycle field
- `artifactStatus`

### Deterministic ID format
`uuid = groupId + "-" + artifactId + "-" + version`

Reference implementation:
- [src/main/java/sh/vork/ai/controller/AgentController.java](src/main/java/sh/vork/ai/controller/AgentController.java)

### Validation rules (current implementation)
- `groupId` required
- `artifactId` required
- `version` required
- `groupId` length: 3..64
- `artifactId` length: 3..64
- `version` max length: 16
- `groupId` regex: `^[A-Za-z0-9]+$`
- `artifactId` regex: `^[A-Za-z0-9]+$`
- current flow supports only `version = SNAPSHOT`

Validation helper:
- `validateArtifactIdentity(...)` in [src/main/java/sh/vork/ai/controller/AgentController.java](src/main/java/sh/vork/ai/controller/AgentController.java)

### Lifecycle states
Current enum values:
- `SNAPSHOT`
- `SUBMITTED`
- `STAGED`
- `PUBLISHED`

Reference enum:
- [src/main/java/sh/vork/ai/agent/ArtifactStatus.java](src/main/java/sh/vork/ai/agent/ArtifactStatus.java)

### Mutability rule (generic)
In this flow, only `SNAPSHOT` artifacts are mutable.

Reusable expectation for other entities:
- mutable: `artifactStatus = SNAPSHOT`
- immutable: `SUBMITTED`, `STAGED`, `PUBLISHED`

## Agent Implementation Details (Reference Source)

### Data model wiring
Reference record:
- [src/main/java/sh/vork/ai/agent/AgentTemplate.java](src/main/java/sh/vork/ai/agent/AgentTemplate.java)

What AgentTemplate currently does:
- normalizes `groupId` and `artifactId`
- defaults blank/null `version` to `SNAPSHOT`
- defaults null `artifactStatus` to `SNAPSHOT`
- provides `isSnapshotMutable()` helper
- uses `@JsonIgnoreProperties(ignoreUnknown = true)` for backward/forward compatibility
- uses `@JsonIgnore` on computed mutability getter to avoid persistence/serialization drift

### Agent-only behavior (do not copy by default)
`systemAgent` behavior in [src/main/java/sh/vork/ai/agent/AgentTemplate.java](src/main/java/sh/vork/ai/agent/AgentTemplate.java):
- system agents null-out VID/lifecycle fields in constructor
- system agents are blocked from update/delete/export/import API paths

This is not a generic VID requirement. For other entities, omit this unless a separate system-managed mode is intentionally designed.

## API Enforcement Pattern

Reference controller:
- [src/main/java/sh/vork/ai/controller/AgentController.java](src/main/java/sh/vork/ai/controller/AgentController.java)

### Create
- validate identity fields
- build deterministic `uuid` from VID
- reject duplicate VID
- persist with `version = SNAPSHOT`, `artifactStatus = SNAPSHOT`

### Update
- enforce snapshot-only mutability
- enforce identity immutability after create:
	- `groupId` cannot change
	- `artifactId` cannot change
- preserve identity/lifecycle fields on update

### Delete
- enforce snapshot-only mutability

### Export
- standard path-style endpoint is sufficient with hyphen VID IDs
- return pretty-printed JSON

### Import
- validate VID fields
- enforce deterministic uuid match if incoming uuid is present
- enforce snapshot-only importability/mutability
- allow create-or-update on same deterministic VID

## Delegation Security Pattern (Agent-Specific)

Reference implementation:
- [src/main/java/sh/vork/ai/controller/AgentController.java](src/main/java/sh/vork/ai/controller/AgentController.java)
- [src/main/java/sh/vork/ai/config/AiConfig.java](src/main/java/sh/vork/ai/config/AiConfig.java)
- [src/main/java/sh/vork/ai/tool/DelegateTaskRequest.java](src/main/java/sh/vork/ai/tool/DelegateTaskRequest.java)
- [src/main/resources/static/js/agents-page.js](src/main/resources/static/js/agents-page.js)

### Assignment model
- each agent declares an explicit allowlist of delegated job uuids (`jobUuids`)
- delegated execution is allowed only for jobs present in that allowlist
- assignment validation rejects duplicate versions of the same delegated job artifact (same groupId + artifactId)
- assigned delegated jobs must exist and be consistent with persisted job metadata

### Runtime authorization for delegateTask
- request must include `jobUuid` in addition to agent identity and prompt
- the target agent must be active in the current session
- the requested `jobUuid` must be assigned to that agent
- the resolved delegated job must exist and be schedulable
- runtime execution uses the assigned delegated job template and applies the caller prompt as the one-time execution prompt

### API and UI enforcement
- create/update/import validations enforce assigned-job consistency and one-version-per-artifact assignment rules
- agents UI only allows selecting valid delegated jobs and preserves assignment uniqueness by group/artifact
- server-side checks remain authoritative even if client-side validation is bypassed

### Regression test coverage
- controller policy tests in [src/test/java/sh/vork/ai/controller/AgentControllerTest.java](src/test/java/sh/vork/ai/controller/AgentControllerTest.java)
- delegate tool authorization tests in [src/test/java/sh/vork/ai/config/AiConfigDelegateTaskToolTest.java](src/test/java/sh/vork/ai/config/AiConfigDelegateTaskToolTest.java)

## UI Enforcement Pattern

Reference files:
- [src/main/resources/templates/agents.html](src/main/resources/templates/agents.html)
- [src/main/resources/static/js/agents-page.js](src/main/resources/static/js/agents-page.js)

Reusable UI expectations:
- inputs for `groupId` and `artifactId`
- min/max length constraints in form and script validation
- alphanumeric-only validation
- lock edits/deletes when artifact is not `SNAPSHOT`

Client-side validation is UX only; server-side validation is authoritative.

## Test Pattern To Reuse

Reference tests:
- [src/test/java/sh/vork/ai/controller/AgentControllerTest.java](src/test/java/sh/vork/ai/controller/AgentControllerTest.java)

Minimum reusable test set for each entity:
- create uses deterministic VID
- update blocked when status is non-SNAPSHOT
- delete blocked when status is non-SNAPSHOT
- import blocked when status is non-SNAPSHOT
- import blocked when incoming uuid does not match deterministic VID
- identity immutability checks on update
- export path works with normal path variable routing

Agent-only tests for system-managed records are optional and should only be added if an entity introduces its own `system*` concept.

## Rollout Checklist For Other Entities

1. Add VID fields and lifecycle field to record/model.
2. Add normalization/defaulting for VID and lifecycle.
3. Add mutability helper (`isSnapshotMutable()` or equivalent).
4. Add deterministic VID generation helper.
5. Add identity validation helper with the same constraints unless intentionally changed.
6. Update create/update/delete/import/export endpoints to enforce rules.
7. Keep path-style APIs; query-style ID fallbacks are unnecessary with hyphen IDs.
8. Add UI form validation for VID fields.
9. Add focused controller tests covering mutability and deterministic identity.
10. Do not copy Agent `systemAgent` behavior unless explicitly required.

## One-line Reuse Rule
For non-Agent entities: copy VID identity + lifecycle + snapshot mutability enforcement, but exclude `systemAgent` behavior unless you intentionally add an equivalent system-managed mode.
