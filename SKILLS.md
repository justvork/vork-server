# Skills and Sub-skills

Vork agents can execute reusable skills and nested sub-skills to break larger tasks into controlled units of work. Skills are wrapped in a group container, for example `Gmail Client` might have a `Connect`, `List`, `Read`, `Send` skills.

## Current model

- Skills can be attached to agents and invoked during normal chat execution.
- Sub-skills allow a parent skill to delegate focused work without exposing unrelated tools.
- OAuth client management can be used inside these workflows when external APIs require authorization.

## Visibility and API contract

- Each skill has `visibility`: `PUBLIC` or `PRIVATE`.
- `PUBLIC` skills are visible to end users and are assignable to agents/jobs.
- `PRIVATE` skills are hidden from end-user skill listings and cannot be attached to agents/jobs.
- Skills are auto-shared within their group for skill-to-skill composition.

### REST behavior

- `GET /api/skills` returns only public skills.
- `GET /api/skills?includePrivate=true` includes private skills only for skill managers (`SKILLS_WRITE`).
- Agent and job APIs reject private skill UUIDs on create/update.

### Tool behavior

- `createSkill` accepts `visibility` (`PUBLIC` by default).
- `designSkillFromRequest` accepts optional `visibility` override for the generated draft request.

## Why this matters

- Better reuse of proven automation patterns.
- Clear tool scoping and reduced blast radius.
- Easier composition of complex multi-step workflows with human approval boundaries.

## Next steps

This page will be expanded with:

- Skill structure and file layout
- How to create and register a new skill
- How to compose sub-skills safely
- Testing and rollout guidance
- Security best practices for skill inputs and tool calls
