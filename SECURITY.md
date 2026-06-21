# Vork Security Model

Vork is designed so the AI can orchestrate credential-dependent actions without exposing credential values in normal model-visible conversation state.

## 1. Secret storage and encryption at rest

- Secrets are persisted in the secure credential store as encrypted payloads, scoped by user and key name.
- Encryption is handled by the platform encryption service (AES-GCM envelope with provider-backed key handling).
- Secret records are never stored as plaintext.
- OAuth client credentials and tokens are also stored encrypted (client secret, access token, refresh token).

## 2. Secrets are kept out of AI conversation context

- When a prompt field is marked as `SECRET`, the submitted value is written directly to secure storage.
- Secret fields are not added to the conversation payload that is persisted for model history.
- The AI sees secret identifiers (for example `API_KEY`) and control/status events, not raw secret values.
- Runtime logs avoid printing resolved secret values.

## 3. If a skill needs a secret input parameter

Skill secret flow is explicit and user-gated:

1. A skill declares required secrets.
2. On invocation, Vork performs a secret preflight check for the authenticated user.
3. If any secret is missing, execution is suspended and the user receives a secure credential form.
4. The submitted values are stored as secrets (not chat fields).
5. The skill resumes with secrets available via runtime substitution.

## 4. If a tool needs secrets

Tool secret flow uses placeholder substitution at execution time:

1. The AI/tool call uses placeholders, e.g. `{{MY_API_KEY}}`.
2. Just before executing the target tool callback, Vork resolves placeholders from the current user's secure store.
3. The actual tool receives resolved values only at runtime boundary.
4. The conversation/history retains placeholders or non-secret metadata, not resolved secret values.

This is why tools can authenticate to external systems without requiring the model to hold or remember credential material.

## 5. OAuth token security

OAuth flow is secured in three layers:

- **Encrypted storage**: OAuth client secret, access token, and refresh token are stored encrypted per user/client.
- **PKCE handshake protection**: connect-session code verifiers are encrypted and short-lived.
- **Just-in-time usage**: access tokens are decrypted only when needed for outbound calls or header placeholder resolution, and refreshed when near expiry.

Operationally:

1. User initiates OAuth connect for a named client.
2. Vork stores connect-session state (including encrypted PKCE verifier).
3. Callback exchanges code for tokens and stores encrypted token material.
4. Future tool/API calls resolve tokens just in time; refresh is attempted automatically when needed.
5. OAuth reset clears stored client state and pending connect-session records for that user/client.

## Practical guarantee

Security and human oversight are embedded into the architecture of Vork.

Vork keeps secrets and OAuth credentials encrypted at rest and out of standard AI conversation history. The AI can request, route, and use credentials through controlled runtime boundaries, while human approval/suspension flows govern collection and high-risk actions.
