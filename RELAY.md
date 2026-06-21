# Vork Relay

## Why relay exists

One of Vork's core jobs is asking you for things. When the AI needs a password, a decision, or an approval, it needs to reach you wherever you are, without requiring your private Vork instance to expose inbound ports.

The relay solves this by inverting connection direction:

- Vork pushes encrypted prompts out to a relay.
- The user opens a secure link and responds.
- Vork receives the encrypted response.

This allows out-of-band human approval while keeping self-hosted Vork deployments private.

## Security model (zero-knowledge)

The relay is designed to be cryptographically blind:

- **AES-256-GCM end-to-end encryption** for prompt and response payloads.
- **Key in URL fragment** (`#k=...`) so the key is never sent in HTTP requests.
- **Browser-side decryption** using Web Crypto API.
- **Fetch-once semantics** so consumed payloads cannot be replayed later.

If a relay server is compromised, it should only see ciphertext, not plaintext credentials.

## Deployment options

| Option | How | Inbound port needed? |
|---|---|---|
| **Public relay** (default) | Use `https://relay.vork.sh` | No |
| **Self-hosted relay** | Run [vork-relay](https://github.com/vork-ai/vork-relay) and point Vork to it | Yes, on relay host |

The source for the standalone relay server is at [github.com/vork-ai/vork-relay](https://github.com/vork-ai/vork-relay).
