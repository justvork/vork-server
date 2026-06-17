/* job-monitor.js — Job Monitor page logic.
 *
 * Reads ?session=<uuid> from the URL, loads the job session, renders history,
 * connects to the STOMP WebSocket for live events, and provides a Terminate
 * button that calls POST /api/jobs/sessions/{uuid}/terminate.
 *
 * Depends on: chat-render.js (ChatRender), auth-modal.js (AuthModal),
 *             vork-modal.js (VorkModal), StompJs, SockJS, marked
 */

/* global ChatRender, AuthModal, StompJs, SockJS, marked */

'use strict';

(function () {

    // ── Configuration ─────────────────────────────────────────────────────────
    const STATUS_POLL_INTERVAL_MS = 4000;
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
                    updateStatusBadge(data.status);
                    if (isTerminalStatus(data.status)) markEnded(data.status);
                }
            })
            .catch(function (err) {
                console.warn('[job-monitor] status poll error:', err);
            });
    }

    // ── PROMPT_REQUIRED handler ───────────────────────────────────────────────

    function handlePromptRequired(frame) {
        updateStatusBadge('AWAITING_INPUT');

        // Add a persistent notification row so the user can re-open the modal
        // if they accidentally dismiss it.
        const existing = messagesArea.querySelector('.auth-notification-row');
        if (!existing) {
            const row = document.createElement('div');
            row.className = 'prompt-notification-row auth-notification-row';
            row.innerHTML =
                '<i class="fa-solid fa-lock-open" aria-hidden="true"></i>' +
                '<span>This job requires authorisation.</span>';
            const reOpenBtn = document.createElement('button');
            reOpenBtn.type = 'button';
            reOpenBtn.className = 'btn btn-warning btn-sm ms-2';
            reOpenBtn.textContent = 'Authorise';
            reOpenBtn.addEventListener('click', function () { openAuthModal(frame); });
            row.appendChild(reOpenBtn);
            messagesArea.insertBefore(row, typingEl);
            ChatRender.scrollBottom();
        }

        // Open the modal immediately
        openAuthModal(frame);
    }

    function openAuthModal(frame) {
        AuthModal.show({
            title      : (frame.formSchema && frame.formSchema.title) || 'Authorise Action',
            reasoning  : frame.textResponse,
            formSchema : frame.formSchema,
            sessionUuid: sessionUuid,
            eventId    : frame.eventId,
            onDone     : function (action, data, err) {
                // Remove the notification row — the action was taken
                const row = messagesArea.querySelector('.auth-notification-row');
                if (row) row.remove();
                if (err) {
                    console.warn('[job-monitor] authorization submit error:', err);
                    return;
                }
                // Session will resume; WebSocket + status poll update the badge
                updateStatusBadge('RUNNING');
            }
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

        // Initialise the shared authorization modal
        AuthModal.init(document.getElementById('auth-modal'));

        // Wire up ChatRender
        ChatRender.init({
            messagesArea    : messagesArea,
            typingEl        : typingEl,
            getSessionUuid  : function () { return sessionUuid; },
            onPromptRequired: handlePromptRequired,
            onInputStateChange: function () {}, // no input area in monitor
            onAwaitingTerminal: function (on) {
                // show/hide typing indicator via ChatRender.showTyping
                // (ChatRender handles this internally)
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
