# Architecture at a Glance

```
Browser / Telegram / Slack
         │
         ▼
   Spring Boot (HTTPS :8443)
         │
   ┌─────┴──────────────────────────────┐
   │  Chat / WebSocket (STOMP)          │
   │  AI Orchestration (multi-provider)  │
   │  Tool execution engine             │
   │    ├─ SSH terminals                │
   │    ├─ Notification providers       │
   │    ├─ Java type compiler           │
   │    ├─ Scheduled jobs               │
   │    └─ Database CRUD (any type)     │
   └─────┬──────────────────────────────┘
         │
         ▼
  Nitrite / MongoDB / Redis / Couchbase
```

The agent can only use tools that have been wired into its configuration. Sensitive tools require the authenticated user to approve each invocation before the action runs.
