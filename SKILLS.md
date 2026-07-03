# Skills and Sub-skills

Vork agents can execute reusable skills and nested sub-skills to break larger tasks into controlled units of work. Skills are wrapped in a group container, for example `Gmail Client` might have a `Connect`, `List`, `Read`, `Send` skills.

## Current model

- Skills can be attached to agents and invoked during normal chat execution.
- Sub-skills allow a parent skill to delegate focused work without exposing unrelated tools.
- OAuth client management can be used inside these workflows when external APIs require authorization.

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
