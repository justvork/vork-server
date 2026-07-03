# Vork

> **Behaviour is the new software.**
>
> Vork is an open community project exploring a future where organisations build and share behaviours instead of buying more applications.

---

## What is Vork?

Vork is not an AI chatbot.

It is not another workflow engine.

It is not an application builder.

Vork is an operating system for organisational behaviour.

Modern AI gives computers something they have never really had before: the ability to understand human language and intent. Rather than building another application for every business problem, Vork asks a different question:

> **What if software became a collection of reusable behaviours that people could simply talk to?**

A behaviour might approve an invoice, prepare release notes, investigate a support ticket, publish documentation, deploy infrastructure or onboard a new employee.

The AI understands intent.

The framework provides security, permissions, records, execution and auditability.

Together they allow organisations to automate work without surrendering control.

---

## The Philosophy

Vork is built around a few simple principles.

- AI should understand intent, not own credentials.
- Deterministic execution is more important than autonomous execution.
- Humans remain part of important decisions.
- Behaviours should be reusable, composable and shareable.
- Good software removes work rather than redistributing it.

The goal isn't to replace people.

The goal is to remove unnecessary software.

---

## What Can Vork Do?

Today's platform already includes:

- Conversational assistants across Web, Slack and Telegram.
- Secure tool execution with human approval.
- Skills composed from reusable behaviours.
- OAuth client management.
- SSH access and remote administration.
- Runtime Java code generation.
- Background jobs and scheduling.
- Voice transcription.
- Notification providers.
- Secure secret management.
- Docker-first deployment.

These are capabilities.

The real value comes from the behaviours built on top of them.

---

## Skills

Everything in Vork is ultimately expressed as behaviours.

Skills package behaviours into reusable building blocks.

They can call REST APIs, execute Java code, use OAuth providers, query records, invoke other skills and interact with users while respecting security boundaries.

Skills are intended to be shared.

Some will be written by the Vork community.

Some by organisations.

Some by commercial vendors.

The platform benefits regardless of who authors them.

---

## Security First

AI should never become your security boundary.

Vork keeps security deterministic.

- Sensitive tools require explicit approval.
- Credentials remain encrypted and managed by the framework.
- Permissions are enforced independently of the language model.
- Every action is auditable.

The AI decides *what* to do.

The framework decides *whether it is allowed*.

---

## Built By Using It

Vork is developed using Vork.

New skills, engineering workflows and operational processes are increasingly built within the platform itself.

We believe the best way to discover better abstractions is to use them every day.

---

## Community

Vork is an open community project.

The objective is not to build every behaviour ourselves.

The objective is to create a platform where engineers, organisations and communities can publish, improve and share behaviours with one another.

If someone builds the definitive accounting behaviour, fantastic.

If another team builds the best support workflow, even better.

The platform succeeds when the community succeeds.

---

## The Workshop

Vork didn't begin with code. It began with observations. Many of those observations are explored publicly through our Founder's Workshop before they become software.

The Workshop documents observations about engineering, AI, software and organisational behaviour.

https://leepainter.com

---

## Contributing

Contributions are welcome.

Whether you're fixing bugs, building behaviours, improving documentation or challenging assumptions, we'd love to have you involved.

Vork is not simply a software project.

It's an exploration of what software becomes when computers can finally understand us.

---

# Documentation

The README introduces the philosophy behind Vork. The detailed documentation below explains how to deploy, configure, secure and extend the platform.

## Getting Started

- [Quick Start](QUICKSTART.md)
- [Docker Deployment](DOCKER.md)
- [Configuration](CONFIGURATION.md)

## Core Concepts

- [Skills & Behaviours](SKILLS.md)
- [Security Model](SECURITY.md)
- [Vork Relay](RELAY.md)

## Databases

- Nitrite (Embedded NoSQL)
- [MongoDB](MONGODB.md)
- [Redis](REDDIS.md)
- [Couchbase](COUCHBASE.md)

## AI Providers

Configure one or more reasoning engines.

- Google Gemini
- OpenAI ChatGPT
- Anthropic Claude
- Ollama
- Groq

(See Configuration Guide.)

## Development

Interested in contributing?

- [Building from Source](BUILDING.md)
- [Architecture](ARCHITECTURE.md)
- [Contributing](CONTRIBUTING.md)

## Community

- 🌍 Website: https://vork.sh
- 💬 Discussions: https://reddit.com/r/vork
- 📚 The Workshop: https://leepainter.com
- 💻 GitHub: https://github.com/justvork

Many of the ideas behind Vork are explored publicly before they become software through **The Workshop**, where founder Lee Painter documents the observations, experiments and engineering principles that shape the project.
