/* job-monitor.js — Job Monitor page logic.
 *
 * Reads ?session=<uuid> from the URL, loads the job session, renders history,
 * connects to the STOMP WebSocket for live events, and provides a Terminate
 * button that calls POST /api/jobs/sessions/{uuid}/terminate.
 *
 * Depends on: chat-render.js (ChatRender), StompJs, SockJS, marked
 */

/* global ChatRender, StompJs, SockJS, marked */

'use strict';

(function () {

    // ── Configuration ─────────────────────────────────────────────────────────
    const STATUS_POLL_INTERVAL_MS = 4000;
    const PROMPT_RESUME_GRACE_MS = 12000;
    const TERMINAL_STATUSES = ['COMPLETED', 'FAILED_MAX_ROUNDS'];

    // ── DOM refs ──────────────────────────────────────────────────────────────
    const messagesArea  = document.getElementById('messages-area');
    const typingEl      = document.getElementById('typing-indicator');
    const jobNameEl     = document.getElementById('job-name');
    const statusBadgeEl = document.getElementById('status-badge');
    const terminateBtn  = document.getElementById('terminate-btn');

    // ── State ─────────────────────────────────────────────────────────────────
    const sessionUuid = new URLSearchParams(window.location.search).get('session');
    let stomp = null;
    let statusPollTimer = null;
    let sessionEnded = false;
    let promptResumeGraceUntil = 0;

    // ── marked config ─────────────────────────────────────────────────────────
    if (typeof marked !== 'undefined' && typeof marked.use === 'function') {
        marked.use({ breaks: true, gfm: true });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    function updateStatusBadge(status) {
        if (!statusBadgeEl) return;
        const prev = statusBadgeEl.className.match(/badge-[A-Z_]+/);
        if (prev) statusBadgeEl.classList.remove(prev[0]);
        statusBadgeEl.classList.add('badge-' + status);
        statusBadgeEl.textContent = status.replace(/_/g, ' ');
    }

    function markEnded(status) {
        if (sessionEnded) return;
        sessionEnded = true;
        updateStatusBadge(status || 'COMPLETED');
        if (terminateBtn) {
            terminateBtn.disabled = true;
            terminateBtn.title = 'Job has already finished';
        }
        stopPoll();
        disconnectStomp();
        ChatRender.showTyping(false);
    }

    function isTerminalStatus(s) {
        return s && TERMINAL_STATUSES.indexOf(s) !== -1;
    }

    function inPromptResumeGraceWindow() {
        return promptResumeGraceUntil > Date.now();
    }

    // ── STOMP ─────────────────────────────────────────────────────────────────

    function connectStomp() {
        if (!sessionUuid) return;
        stomp = new StompJs.Client({
            webSocketFactory: function () { return new SockJS('/ws'); },
            reconnectDelay: 5000
        });

        stomp.onConnect = function () {
            stomp.subscribe('/topic/chat/' + sessionUuid, function (message) {
                let frame;
                try { frame = JSON.parse(message.body); } catch (_) { return; }
                if (!frame) return;
                if (frame.type === 'PROMPT_REQUIRED') {
                    if (!inPromptResumeGraceWindow()) {
                        updateStatusBadge('AWAITING_INPUT');
                    }
                } else if (frame.type === 'TEXT_RESPONSE' || frame.type === 'AGENT_TRANSITION' || frame.type === 'SKILL_TRANSITION') {
                    promptResumeGraceUntil = 0;
                    updateStatusBadge('RUNNING');
                }
                ChatRender.handleIncomingUiFrame(frame);
            });
        };

        stomp.activate();
    }

    function disconnectStomp() {
        if (stomp) {
            try { stomp.deactivate(); } catch (_) {}
            stomp = null;
        }
    }

    // ── Status polling ────────────────────────────────────────────────────────

    function startPoll() {
        if (statusPollTimer) return;
        statusPollTimer = setInterval(pollStatus, STATUS_POLL_INTERVAL_MS);
    }

    function stopPoll() {
        if (statusPollTimer) { clearInterval(statusPollTimer); statusPollTimer = null; }
    }

    function pollStatus() {
        if (!sessionUuid || sessionEnded) { stopPoll(); return; }
        fetch('/api/chat/session?sessionUuid=' + encodeURIComponent(sessionUuid))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data && data.status) {
                    const status = data.status;
                    if (isTerminalStatus(status)) {
                        promptResumeGraceUntil = 0;
                        updateStatusBadge(status);
                        markEnded(status);
                        return;
                    }

                    if (status === 'AWAITING_INPUT' && inPromptResumeGraceWindow()) {
                        // Resume has been acknowledged by the user; avoid snapping
                        // back to AWAITING_INPUT while backend state propagation catches up.
                        updateStatusBadge('RUNNING');
                    } else {
                        if (status === 'RUNNING') promptResumeGraceUntil = 0;
                        updateStatusBadge(status);
                    }
                }
            })
            .catch(function (err) {
                console.warn('[job-monitor] status poll error:', err);
            });
    }

    // ── Terminate button ──────────────────────────────────────────────────────

    function onTerminate() {
        if (!sessionUuid || sessionEnded) return;
        if (!confirm('Terminate this job?')) return;
        terminateBtn.disabled = true;
        fetch('/api/jobs/sessions/' + encodeURIComponent(sessionUuid) + '/terminate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data && data.ok) {
                markEnded('COMPLETED');
            } else {
                terminateBtn.disabled = false;
                console.warn('[job-monitor] terminate response:', data);
            }
        })
        .catch(function (err) {
            terminateBtn.disabled = false;
            console.error('[job-monitor] terminate error:', err);
        });
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    function init() {
        if (!sessionUuid) {
            if (jobNameEl) jobNameEl.textContent = 'No session specified';
            if (terminateBtn) terminateBtn.disabled = true;
            return;
        }

        // Wire up ChatRender
        ChatRender.init({
            messagesArea    : messagesArea,
            typingEl        : typingEl,
            getSessionUuid  : function () { return sessionUuid; },
            promptMode      : 'inline',
            readOnly        : true,
            onInputStateChange: function () {}, // no input area in monitor
            onAwaitingTerminal: function (on) {
                // show/hide typing indicator via ChatRender.showTyping
                // (ChatRender handles this internally)
            },
            onPromptSubmitted: function () {
                // Optimistic status flip; polling and stream events will reconcile if needed.
                promptResumeGraceUntil = Date.now() + PROMPT_RESUME_GRACE_MS;
                updateStatusBadge('RUNNING');
            }
        });

        terminateBtn.addEventListener('click', onTerminate);

        // Load session
        fetch('/api/chat/session?sessionUuid=' + encodeURIComponent(sessionUuid))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                // Update header
                if (jobNameEl) jobNameEl.textContent = data.sessionName || sessionUuid;
                document.title = (data.sessionName || 'Job') + ' — Vork Monitor';

                const status = data.status || 'RUNNING';
                updateStatusBadge(status);

                if (terminateBtn) {
                    const isBackground = data.originMode === 'BACKGROUND';
                    terminateBtn.classList.toggle('d-none', !isBackground);
                    terminateBtn.disabled = !isBackground || isTerminalStatus(status);
                }

                // Render history
                if (data.messages && data.messages.length > 0) {
                    ChatRender.renderSessionHistory(data.messages);
                }

                if (isTerminalStatus(status)) {
                    markEnded(status);
                } else {
                    connectStomp();
                    startPoll();
                    // Show the typing spinner whenever the job is actively running.
                    // It is cleared automatically when TEXT_RESPONSE / ERROR / PROMPT_REQUIRED
                    // arrives, and re-shown after each AGENT_TRANSITION / SKILL_TRANSITION.
                    if (status === 'RUNNING') {
                        ChatRender.showTyping(true);
                    }
                }
            })
            .catch(function (err) {
                if (jobNameEl) jobNameEl.textContent = 'Session not found';
                if (terminateBtn) terminateBtn.disabled = true;
                console.error('[job-monitor] failed to load session:', err);
            });
    }

    document.addEventListener('DOMContentLoaded', init);

}());
