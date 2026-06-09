# Telegram Integration — Configuration Guide

Vork connects to Telegram via the **Bot API** (long-polling — no public URL
required).  The integration supports two use cases:

- **AI chat sessions** — users message the Vork bot privately and get a full
  AI session.
- **Outbound notifications** — the bot posts alerts to registered groups or
  channels.

---

## 1. Create a Telegram Bot

1. Open Telegram and start a chat with **@BotFather**.
2. Send `/newbot` and follow the prompts (choose a name and username).
3. BotFather replies with your **Bot Token** — a string like
   `123456789:ABCDEFabcdef…`.  Keep this secret.

---

## 2. Add the Provider in Vork

Log in as an admin and go to **Admin → Notification Providers → Add Provider**.

| Field | Value |
|---|---|
| Provider | Telegram |
| Display Name | Any label, e.g. *Vork Telegram* |
| Bot Token | The token from BotFather |

Save the config.  Vork immediately starts long-polling the Telegram Bot API
for updates.

---

## 3. User Registration (Personal Notifications + AI Chat)

Users must link their Telegram account to their Vork profile before they can
receive personal notifications or use AI chat sessions.

### Via the Vork profile UI

1. The user logs into Vork and opens **Profile → Notification Settings**.
2. Click **Add address**, then select the Telegram provider from the dropdown.
3. A QR code appears together with a deep link button.
4. **On mobile:** tap the link — it opens Telegram and pre-fills the
   `/start CODE` message.  Tap **Send**.
5. **On desktop:** scan the QR code with your phone's Telegram camera, then
   tap **Send** on the pre-filled message.
6. The page confirms automatically within seconds.

### Via the API (programmatic)

```http
POST /api/user/notification-media/telegram/register
Content-Type: application/json

{ "providerConfigId": "<config-uuid>", "isDefault": true }
```

Response:
```json
{
  "registrationId": "...",
  "url": "https://t.me/YourBotName?start=ABCDEF12345678AB"
}
```

The user opens the URL (or scans a QR code generated from it) and taps
**Send** on the pre-filled `/start CODE` message in Telegram.

Poll for completion:
```http
GET /api/user/notification-media/telegram/register/{registrationId}
```

Response when complete:
```json
{ "status": "complete", "mediaId": "<media-uuid>" }
```

Cancel if the user dismisses:
```http
DELETE /api/user/notification-media/telegram/register/{registrationId}
```

---

## 4. AI Chat Sessions (Private Chats)

Once registered, the user opens a private chat with the bot and chats normally.

| User message | Effect |
|---|---|
| Any text | Forwarded to the AI; reply appears in the chat |
| `/new` | Starts a fresh session (previous context is discarded) |

### Suspension / approval prompts

When a tool requires user approval, the bot sends an inline keyboard:

```
Approve terminal command?

ls -la /home

[ Allow once ]  [ Allow for session ]  [ Deny ]
```

The user taps a button to respond.

For forms with a single text field (e.g. entering a value), the bot asks
the user to reply with the value directly in the chat.

For complex forms (multiple fields or passwords) the bot sends a link to the
Vork web form, or to the zero-knowledge relay at `relay.vork.sh` if no
`vork.app.base-url` is configured.

---

## 5. Group Registration (Outbound Notifications)

Admins can register Telegram groups or channels as shared notification
destinations (`GlobalAddress` entries).

### Prerequisites

The Vork bot must be a **member** of the group or channel before registration.
For channels, make the bot an **administrator** so it can post.

### Via the Vork admin UI

1. Open **Admin → Notification Providers → [your Telegram config] → Global Addresses**.
2. Click **Add Telegram Group**.
3. Vork displays a one-time code and instructs you to:
   1. Add the Vork bot to the target group (`Add member → @YourBotName`).
   2. Post inside that group:
      ```
      /register <CODE>
      ```
4. The group is registered automatically once the bot receives the command.

### Via the API

```http
POST /api/notification/telegram/{configId}/group-registration/start
```

Response:
```json
{
  "registrationId": "...",
  "code": "ABCDEF12345678AB",
  "instructions": "Add the bot to your Telegram group, then type inside the group:\n/register ABCDEF12345678AB"
}
```

Poll for completion:
```http
GET /api/notification/telegram/{configId}/group-registration/{regId}/status
```

---

## 6. Sending Notifications to Groups

After a group is registered, any Vork notification that targets a
`GlobalAddress` of type `TELEGRAM` is delivered as a formatted message to
that group.

Messages use **Telegram MarkdownV2** formatting — titles are bolded and code
snippets are wrapped in code blocks automatically.

---

## 7. Troubleshooting

| Symptom | Check |
|---|---|
| QR code shows but scan has no effect | Confirm the bot token is correct and the bot is not already receiving updates via a webhook (`deleteWebhook` if needed) |
| Registration code accepted but status stays "pending" | The `/start CODE` message must be sent **to the bot's private chat**, not to a group |
| Bot not responding in private chat | Confirm the user completed registration (step 3); send `/start` to the bot to prompt a re-link suggestion |
| Bot not posting to group | Confirm the bot is a member of the group and the group was registered (step 5) |
| `/register CODE` in group — "invalid or expired" | Codes expire after 15 minutes; start a new group registration |
| Long-polling not starting | Check application logs for `TelegramPollingService`; verify the bot token has no trailing whitespace |

---

## 8. Security Notes

- The **bot token** is stored encrypted in MongoDB via Vork's
  `NotificationProviderConfig` settings.  Never commit it to source control.
- Registration deep links are one-time use and expire after 15 minutes.
- Private-chat AI sessions are scoped to authenticated Vork users via the
  `UserNotificationMedia` link — an unregistered Telegram user cannot start a
  session.
- The bot ignores all messages from groups unless the message is a `/register`
  command from a recognised group registration flow.
