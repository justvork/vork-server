# Notifications

Vork includes AI tools for direct notification delivery, ledger inspection, and aggregate delivery health reporting.

## Tool Flow

1. Call `listNotificationProviders`.
2. Select one provider configuration UUID (`providerConfigId`) that supports the address type.
3. Call `sendNotification`.
4. For delivery audit and debugging, call:
- `listNotificationLedgerEntries` for row-level details.
- `summarizeNotificationLedger` for aggregate health metrics.

## listNotificationProviders

Returns configured providers that support direct (unregistered) addresses.

### Request

```json
{}
```

### Response (example)

```json
[
  {
    "configId": "f70c949f-47ea-459f-bf5e-145d2c1f17f0",
    "displayName": "SendGrid Email",
    "providerKey": "sendgrid",
    "mediaTypes": ["EMAIL_ADDRESS"]
  },
  {
    "configId": "eb9e2d2f-6a9c-4de0-90d1-08be2c9814f4",
    "displayName": "Twilio SMS",
    "providerKey": "twilio-sms",
    "mediaTypes": ["PHONE_NUMBER"]
  }
]
```

## sendNotification

Sends a message to a direct address and records a ledger entry.

### Request (email, idempotent)

```json
{
  "providerConfigId": "f70c949f-47ea-459f-bf5e-145d2c1f17f0",
  "title": "Quarterly Promotions",
  "body": "Hello from Vork.",
  "bodyContentType": "text/plain",
  "idempotencyGroup": "sales-campaign-28-08-2026",
  "originatingAgent": "Concierge",
  "originatingSkill": "marketing-broadcast",
  "address": "user@example.com"
}
```

### Response (successful send)

```json
{
  "status": "ok",
  "ledgerEntryId": "e4db5dc8-c0f3-4239-b8e4-0f288b4202ac",
  "idempotencyKey": "sales-campaign-28-08-2026:email_address:user@example.com"
}
```

### Response (suppressed duplicate)

```json
{
  "status": "already sent",
  "message": "Notification already sent for idempotency key.",
  "ledgerEntryId": "2d6beea8-3a36-4d6a-92d8-58336f42746a",
  "idempotencyKey": "sales-campaign-28-08-2026:email_address:user@example.com"
}
```

### Response (delivery failure)

```json
{
  "status": "error",
  "message": "Provider send failed: API down",
  "ledgerEntryId": "4f5365c6-e0fa-4f34-b10f-25e94f43867b",
  "idempotencyKey": "sales-campaign-28-08-2026:email_address:user@example.com"
}
```

Notes:
- Failed attempts are logged but do not block future retries for the same idempotency key.
- Idempotency key format: `<idempotencyGroup>:<media_type>:<normalized_address>`.

## listNotificationLedgerEntries

Returns paged ledger rows for detailed troubleshooting.

### Request (example)

```json
{
  "page": 0,
  "pageSize": 50,
  "finalState": "FAILED",
  "providerConfigId": "f70c949f-47ea-459f-bf5e-145d2c1f17f0"
}
```

### Response (shape)

```json
{
  "status": "ok",
  "total": 3,
  "page": 0,
  "pageSize": 50,
  "entries": [
    {
      "uuid": "...",
      "finalState": "FAILED",
      "destination": "user@example.com",
      "createdAt": 1787062460552,
      "providerKey": "sendgrid"
    }
  ]
}
```

## summarizeNotificationLedger

Returns aggregate delivery metrics without returning full ledger rows.

### Request (example)

```json
{
  "sinceEpochMillis": 1787000000000,
  "idempotencyGroup": "sales-campaign-28-08-2026"
}
```

### Response (example)

```json
{
  "status": "ok",
  "total": 125,
  "duplicateSuppressedCount": 19,
  "uniqueDestinationCount": 96,
  "byFinalState": {
    "SENT": 102,
    "FAILED": 4,
    "ALREADY_SENT": 19
  },
  "byProviderKey": {
    "sendgrid": 88,
    "twilio-sms": 37
  },
  "byMediaType": {
    "EMAIL_ADDRESS": 88,
    "PHONE_NUMBER": 37
  },
  "appliedFilters": {
    "sinceEpochMillis": 1787000000000,
    "providerConfigId": null,
    "idempotencyGroup": "sales-campaign-28-08-2026",
    "destination": null
  }
}
```

## Operator Playbook

Use this section during incidents or post-send verification.

## 1. Spike in FAILED deliveries

Goal: identify whether failures are isolated, provider-specific, or widespread.

Steps:
1. Run `summarizeNotificationLedger` with a recent time window.
2. Inspect `byFinalState` and `byProviderKey`.
3. If one provider dominates failures, isolate impact by filtering that `providerConfigId` in `listNotificationLedgerEntries`.
4. Sample error messages from the newest failed entries.

Example summary request:

```json
{
  "sinceEpochMillis": 1787060000000
}
```

Example detail request:

```json
{
  "page": 0,
  "pageSize": 100,
  "finalState": "FAILED",
  "providerConfigId": "f70c949f-47ea-459f-bf5e-145d2c1f17f0"
}
```

Expected action:
- Provider outage or quota issue: pause non-critical sends to that provider and retry later.
- Address-format errors: correct upstream address normalization/validation and replay failed sends.

## 2. Unexpected ALREADY_SENT growth

Goal: confirm idempotency is preventing duplicates (expected) vs over-suppressing distinct sends (unexpected).

Steps:
1. Run `summarizeNotificationLedger` for the impacted `idempotencyGroup`.
2. Compare `ALREADY_SENT` ratio to `SENT`.
3. Pull sample rows via `listNotificationLedgerEntries` filtered by the same group.
4. Confirm each suppressed row has the expected key shape:
   `<group>:<media_type>:<normalized_address>`.

Example summary request:

```json
{
  "sinceEpochMillis": 1787060000000,
  "idempotencyGroup": "sales-campaign-28-08-2026"
}
```

Expected action:
- If suppression is correct: no change required.
- If suppression is too high: split campaign batches by distinct `idempotencyGroup` values.

## 3. Provider outage and retry strategy

Goal: safely retry failed sends without creating duplicates.

Facts:
- `FAILED` entries do not block future sends with the same idempotency key.
- Only prior `SENT` entries cause `already sent` suppression.

Retry pattern:
1. Identify a failed cohort (`providerConfigId`, time window, optional group).
2. Re-run the same logical send operation with the same `idempotencyGroup`.
3. Successful retries transition new entries to `SENT`; repeated attempts after success return `already sent`.

Recommended safety checks:
- Verify provider health first (credentials, quota, API availability).
- Start with a small retry sample before broad replay.
- Re-run `summarizeNotificationLedger` after replay and confirm `FAILED` trend declines.

## 4. Quick health checklist

Use this before and after high-volume sends.

Pre-send:
1. Confirm provider availability with `listNotificationProviders`.
2. Choose one consistent `idempotencyGroup` for the campaign.
3. Confirm address type matches provider media type.

Post-send:
1. Run `summarizeNotificationLedger` for campaign window/group.
2. Investigate any non-trivial `FAILED` count.
3. Confirm duplicate suppression is within expected range.
