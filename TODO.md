# TODO

This is a top-level list of requirements that are currently planned for implementation. 

## Surfaces

There are currently 4 main mechanisms for agents to operate: Web Chat, Telegram, Slack and Scheduled Jobs. With the exception of Scheduled Jobs these are generally all interactive interfaces. To make this a true operating system for work we need mechanisms for external input that can be triggered by non-users and machines. 

### Forms

Forms should allow for anonymous and/or logged in users to start background processes. This should be flexible enough to include custom HTML and styles, with the platform rendering the form according to the requirements. Submission would feed the input into a new background task, much like Scheduled Tasks.
- **File Attachment Ingestion:** Provide a mechanism for multi-part form data and file uploads to be parsed and mapped securely into the background task's temporary execution or file context.

### Webhooks

This would allow for external systems to POST forms directly to start background processing.
- **Payload Verification:** Implement native support for API keys, bearer tokens, and HMAC secret signatures (e.g., `X-Hub-Signature`) to validate incoming webhook authenticity before triggering a task.
- **Rate Limiting & Queueing:** Implement threshold limits and an ingestion queue on external webhook endpoints to prevent denial-of-service (DoS) attempts or unexpected surges from flooding the background task runner.

### Events

Agents, skills and tools should be able to generate events that can be further configured to initiate background jobs.
- **Circuit Breaker / Loop Prevention:** Implement an execution depth counter or sliding-window timeout to detect and terminate recursive agent event-trigger loops (e.g., an execution failure loop that continuously triggers itself).
- **Idempotency Key Tracking:** Ensure event payloads can carry unique identifiers to prevent duplicate execution of a background job if an external network retry fires the same event twice.

## Constraints

Skills and tools have variable parameters that could be misused by rogue scripts or end-users. For example, a Google Calendar skill should not be able to use HTTP methods or URLs that are outside of the skill requirements. For example, a read-only calendar profile should not be able to POST. Similarly, when using the HTTP Request tool the calendar skill should only be able to call the specific google URLs required for the skill. It should not be able to post to Facebook, or read your email.

### Skill Constraints

These will be skill level constraints that are hard-coded into the skill. The previous example of a read-only Google Calendar skill would setup something like 

```json
{
  "tool_name": "http_request",
  "argument_constraints": {
    "url": "^https://www\\.googleapis\\.com/calendar/v3/.*$",
    "method": "^(GET)$"
  }
}
```

### Job Constraints

Some skills will be designed to be more generic. Creating SSH connections, we don't want to constrain that at the skill level, instead that would have a separate category where constraints would be defined at the Job level.

```json
{
  "tool_name": "connect_ssh",
  "argument_constraints": {
    "host": "192.168.1.5"
  }
}
```

Similarly, executing commands could be restricted.

```json
{
  "tool_name": "execute_terminal_command",
  "argument_constraints": {
    "command": "^(cat|ls|grep|echo)\\b.*$"
  }
}
```

### Constraint Management Policies

Failure Action Policies: Define what happens when a level-1 or level-2 constraint is violated at runtime. Supported policies include:

    ABORT_TASK: Immediately terminate the execution turn with a hard security violation log.

    RETRY_WITH_CORRECTION: Intercept the error, append a system message back to the LLM context stating "Input X violated security constraint Y. Regenerate arguments within authorized bounds," giving the AI a single attempt to self-correct.

Data Masking & Redaction: Ensure that if a validation check fails on a sensitive argument (such as passwords, private keys, or auth headers), the violating string is completely scrubbed from the public log frame before archiving.