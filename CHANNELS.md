# Channels Model Notes

## Purpose
This document records channel-routing decisions for request-information campaigns, especially for Slack and Telegram delivery targets.

## Agreed Rules
1. Channel-first routing: delivery preference lives on the channel, not on the user.
2. One counted response per channel: only one accepted response slot per target channel.
3. Policy counts per channel, not per person:
4. FIRST means first responding channel wins.
5. QUORUM means N distinct channels must respond.
6. ALL means all target channels must respond.
7. Extra replies from the same channel are acknowledged but do not advance progress.

## Current Behavior Snapshot
1. Campaign targets are channel names.
2. Request-response gating already deduplicates by campaign plus responder channel.
3. Child request sessions are currently created as web-origin sessions.
4. Resume flow uses campaign satisfaction and parent-session resume.

## What Would Change
1. Child session origin should be selected from channel provider metadata, not hardcoded to web.
2. Channel records should carry provider routing fields:
3. providerKey: slack, telegram, web, or future provider.
4. targetType: direct or shared.
5. externalTargetId: provider-native id, for example Slack channel id or Telegram chat id.
6. responseCountMode: per_channel (default).
7. On campaign creation, one child context is created per target channel.
8. For shared channels, a single response slot is tracked, regardless of how many individuals can reply.
9. Responder actor identity should still be stored for audit.
10. Alert and notification links should always point back to chat session continuation.

## Slack Requirements
1. Direct user targets:
2. Map Vork user to Slack user id and DM channel id.
3. Ensure bot can open or reuse DM and send prompt.
4. Shared channel targets:
5. Store Slack channel id as externalTargetId.
6. Ensure bot is a member of the channel.
7. Required scopes typically include chat:write and read access needed for replies.
8. Inbound processing must capture Slack event user id as actor and channel id as response channel key.
9. Count exactly one accepted response for the channel.

## Telegram Requirements
1. Direct user targets:
2. Store Telegram chat id for the user.
3. Send prompt to that chat and collect replies as actor plus channel key.
4. Shared targets:
5. Use Telegram groups or supergroups for multi-user discussion.
6. Telegram broadcast channels are usually one-way and may not be suitable for direct reply collection.
7. If using channels, reply collection may require linked discussion groups.
8. Inbound processing must capture sender id plus chat id; count by chat id only.

## Shared Channel Semantics
1. Shared channel is one campaign slot.
2. First valid reply locks that channel slot as responded.
3. Later replies in same channel can be logged as non-counting supplemental context.
4. Campaign progress uses distinct responded channel count only.

## Data Model Additions To Consider
1. Channel entity:
2. providerKey
3. targetType
4. externalTargetId
5. canAcceptReplies
6. RequestInformationResponse entity:
7. responderActorId
8. responderActorDisplayName
9. responderProviderMetadata map

## Processing Rules To Keep Stable
1. requiredResponses must never exceed number of target channels.
2. Resume trigger must stay single-winner and idempotent.
3. Parent session resumes only once when channel threshold is met.
4. If channel is unreachable, keep campaign open and surface clear attention state.

## Migration Approach
1. Keep existing web-based channels working as default fallback.
2. Add provider-backed channels incrementally, starting with Slack direct and shared channels.
3. Add Telegram direct next, then Telegram group or supergroup support.
4. Add tests for per-channel dedupe and quorum semantics across mixed provider channels.

## Open Questions
1. Should late same-channel replies be visible to the parent AI as supplemental notes.
2. Should shared-channel campaigns support manual channel-slot reset.
3. What timeout or escalation policy should apply for unreachable channels.
4. Should actor identity be exposed in parent prompt context by default.
