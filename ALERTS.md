# ALERTS.md

## Purpose
Track current usage of native browser dialogs and define the migration direction.

## Policy Direction
- Do not use native browser dialogs in page code:
  - `window.alert(...)`
  - `window.confirm(...)`
  - `window.prompt(...)`
- For status/outcome messaging (success, warning, error), use page-top alerts.
- For collecting user input or decision prompts, use custom modals.

## Audit Snapshot (2026-08-21)
Audit scope:
- `src/main/resources/static/js/**/*.js`
- `src/main/resources/templates/**/*.html`

Native dialog totals in static JS:
- alerts: 11
- confirms: 25
- prompts: 4
- total: 40

Template-level native dialog calls:
- total: 3 matches by pattern
- 1 real inline confirm in `src/main/resources/templates/settings/oauth-clients.html`
- 2 are comments only (non-runtime mentions)

## Highest-Volume Files (by native dialog occurrences)
1. `src/main/resources/static/js/surface-editor.js` (5)
2. `src/main/resources/static/js/users.js` (4)
3. `src/main/resources/static/js/reflections.js` (4)
4. `src/main/resources/static/js/skills.js` (3)
5. `src/main/resources/static/js/jobs-page.js` (3)
6. `src/main/resources/static/js/agents-page.js` (3)

## Notable Patterns
- Delete confirmations are heavily implemented via `confirm(...)`.
- Input collection still uses `prompt(...)` in multiple places.
- Some operational errors still use `alert(...)` instead of page-level alert banners.
- One utility fallback in `github-device-flow-ui.js` defaults to `window.alert` when no alert renderer is passed.

## Migration Plan (Deferred)
Phase 1 (highest UX impact)
- Replace all `prompt(...)` usages with custom modal input dialogs.
- Replace inline template `confirm(...)` in OAuth clients with custom modal confirmation.
- Replace direct `alert(...)` in user-facing pages with page-top alerts.

Phase 2 (consistency and cleanup)
- Replace all remaining `confirm(...)` calls with custom modal confirmations.
- Remove fallback usage of `window.alert` in helper/utility code.

Phase 3 (guardrail)
- Add CI/lint rule to fail on native dialog usage in:
  - `src/main/resources/static/js/**/*.js`
  - inline template event handlers invoking native dialogs.

## Acceptance Criteria
- No runtime use of `alert/confirm/prompt` in page JS.
- No inline native-dialog event handlers in templates.
- All status messaging uses page alert areas.
- All user-input and confirmation flows use custom modals.

## Notes
- This document captures current state and plan only.
- Implementation intentionally deferred for follow-up work.
