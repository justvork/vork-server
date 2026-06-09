# Slack Integration — Configuration Guide

Vork connects to Slack via **Socket Mode** (no public URL required).  The
integration supports two use cases:

- **AI chat sessions** — users DM the Vork bot and get a full AI session.
- **Outbound notifications** — the bot posts alerts to registered channels.

---

## 1. Create a Slack App

1. Go to <https://api.slack.com/apps> and click **Create New App → From scratch**.
2. Name the app (e.g. *Vork*) and pick your workspace.

---

## 2. Enable Socket Mode

1. In the app settings sidebar click **Socket Mode**.
2. Toggle **Enable Socket Mode** on.
3. When prompted, create an **App-Level Token**:
   - Name: `vork-socket`
   - Scope: `connections:write`
4. Copy the generated token — it starts with `xapp-`.  This is your **App Token**.

---

## 3. Add Bot OAuth Scopes

1. Click **OAuth & Permissions** in the sidebar.
2. Under **Bot Token Scopes** add:

   | Scope | Purpose |
   |---|---|
   | `chat:write` | Post messages to channels and DMs |
   | `channels:read` | Resolve channel names during registration |
   | `groups:read` | Resolve private channel names |
   | `im:read` | Read direct-message metadata |
   | `mpim:read` | Read group-DM metadata (optional) |

3. Click **Install to Workspace** (or **Reinstall** if updating scopes).
4. Copy the **Bot User OAuth Token** — it starts with `xoxb-`.  This is your **Bot Token**.

---

## 4. Subscribe to Bot Events

1. Click **Event Subscriptions** in the sidebar.
2. Toggle **Enable Events** on.
3. Under **Subscribe to bot events** add:

   | Event | Purpose |
   |---|---|
   | `message.im` | DM messages (AI sessions + user registration) |
   | `message.channels` | Public channel messages (channel registration) |
   | `message.groups` | Private channel messages (channel registration) |

4. Click **Save Changes**.

> **Note:** Socket Mode delivers events over WebSocket — there is no
> Request URL to configure.

---

## 5. Enable Direct Messages (App Home)

Without this step users will see *"Sending messages to this app has been
turned off"* when they try to DM the bot.

1. Click **App Home** in the sidebar.
2. Scroll to **Show Tabs**.
3. Under **Messages Tab**, enable
   **Allow users to send Slash commands and messages from the messages tab**.
4. Click **Save Changes**.

---

## 6. Add the Provider in Vork

Log in as an admin and go to **Admin → Notification Providers → Add Provider**.

| Field | Value |
|---|---|
| Provider | Slack |
| Display Name | Any label, e.g. *Vork Slack* |
| Bot Token (xoxb-…) | The Bot User OAuth Token from step 3 |
| App-Level Token (xapp-…) | The App-Level Token from step 2 |

Save the config.  Vork immediately opens a Socket Mode connection and starts
receiving events.

---

## 7. User DM Registration

Users must link their Slack account to their Vork profile before they can use
AI chat sessions.

### Option A — via the Vork profile UI

1. The user logs into Vork and opens **Profile → Notification Settings**.
2. Click **Add address**, then select the Slack provider from the dropdown.
3. Vork generates a one-time code and displays it with the instruction:
   ```
   register ABCDEF12345678AB
   ```
4. The user opens a **direct message** with the Vork bot in Slack and sends
   that exact message.
5. The page confirms automatically — no further action needed.

### Option B — API (programmatic)

```http
POST /api/user/notification-media/slack/register
Content-Type: application/json

{ "providerConfigId": "<config-uuid>", "isDefault": true }
```

Response:
```json
{
  "registrationId": "...",
  "instructions": "Open a DM with the Vork bot in Slack and send:\nregister ABCDEF12345678AB\n\nThis code expires in 15 minutes."
}
```

Poll for completion:
```http
GET /api/user/notification-media/slack/register/{registrationId}
```

Response when complete:
```json
{ "status": "complete", "mediaId": "<media-uuid>" }
```

Cancel if the user dismisses:
```http
DELETE /api/user/notification-media/slack/register/{registrationId}
```

---

## 8. AI Chat Sessions (DMs)

Once registered, the user opens a Slack DM with the bot and chats normally.

| User message | Effect |
|---|---|
| Any text | Forwarded to the AI; reply appears in the DM |
| `/new` | Starts a fresh session (previous context is discarded) |
| A single digit (e.g. `1`) | Selects an action when the AI presents a numbered choice |
| A text reply | Provides a field value when the AI asks for a single input |

### Suspension / approval prompts

When a tool requires user approval, the bot sends a numbered list:

```
Approve terminal command?

ls -la /home

Choose an option by replying with the number:
1. Allow once
2. Allow for this session
3. Deny
```

The user replies with `1`, `2`, or `3`.

For forms with a single text field (e.g. entering a password), the bot asks
the user to reply with the value directly.

For complex forms (multiple fields or passwords) the bot sends a link to
the Vork web form, or to the zero-knowledge relay at `relay.vork.sh` if no
`vork.app.base-url` is configured.

---

## 9. Channel Registration (Outbound Notifications)

Admins can register Slack channels as shared notification destinations
(`GlobalAddress` entries).

### Via the Vork admin UI

1. Open **Admin → Notification Providers → [your Slack config] → Global Addresses**.
2. Click **Register Slack Channel**.
3. Vork displays a one-time code and instructs you to:
   1. Add the Vork bot to the target channel (`/invite @VorkBot`).
   2. Post inside that channel:
      ```
      register <CODE>
      ```
4. The channel is registered automatically once the bot receives the message.

### Via the API

```http
POST /api/notification/slack/{configId}/channel-registration/start
```

Response:
```json
{
  "registrationId": "...",
  "code": "ABCDEF12345678AB",
  "instructions": "Add the bot to your Slack channel, then type inside the channel:\nregister ABCDEF12345678AB"
}
```

Poll for completion:
```http
GET /api/notification/slack/{configId}/channel-registration/{registrationId}/status
```

---

## 10. Sending Notifications to Channels

After a channel is registered, any Vork notification that targets a
`GlobalAddress` of type `SLACK` is delivered as a plain-text message to
that channel.

Notification providers (jobs, alerts, etc.) pick up registered global
addresses automatically — no additional configuration is required.

---

## 11. Troubleshooting

| Symptom | Check |
|---|---|
| "Sending messages to this app has been turned off." | In your app settings at api.slack.com/apps → **App Home → Messages Tab**, enable *Allow users to send Slash commands and messages from the messages tab* |
| Bot not responding to DMs | Confirm `message.im` event subscription is enabled and the user is registered (step 6) |
| Socket Mode not connecting | Verify the `xapp-` token has `connections:write` scope; check application logs for `SlackSocketModeService` |
| "Registration code is invalid or has expired" | Codes expire after 15 minutes — start a new registration |
| Bot posts to the wrong channel | Each `NotificationProviderConfig` has its own bot token; confirm the bot is a member of the target channel |
| Channel registration fails | Make sure the Vork bot was invited to the channel (`/invite @VorkBot`) before posting the code |

---

## 12. Security Notes

- **Bot tokens** (`xoxb-`) are stored encrypted in MongoDB via Vork's
  `NotificationProviderConfig` settings.  Never commit them to source control.
- **App-level tokens** (`xapp-`) are scoped to `connections:write` only —
  they cannot read messages or act on behalf of users.
- The Slack provider validates that incoming member IDs match the pattern
  `^[UCWDG][A-Z0-9]{6,}$` before saving any registration.
- All Slack DM sessions are scoped to authenticated Vork users via the
  `UserNotificationMedia` link — an unregistered Slack user cannot start a
  session.
