// ── Pending Sessions page script ─────────────────────────────────────────────

'use strict';

(function () {

    var loadingEl      = document.getElementById('loading-state');
    var emptyEl        = document.getElementById('empty-state');
    var tableWrapper   = document.getElementById('sessions-table-wrapper');
    var tbody          = document.getElementById('sessions-tbody');

    // Initialise the shared auth modal
    AuthModal.init(document.getElementById('auth-modal'));

    var sessions  = [];   // cached list from the API

    // ── Load ──────────────────────────────────────────────────────────────────

    function load() {
        fetch('/api/chat/sessions/pending-input')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
            .then(function (data) {
                loadingEl.classList.add('d-none');
                sessions = data || [];
                if (sessions.length === 0) {
                    emptyEl.classList.remove('d-none');
                    return;
                }
                renderTable(sessions);
                tableWrapper.classList.remove('d-none');
            })
            .catch(function (err) {
                loadingEl.classList.add('d-none');
                tableWrapper.innerHTML =
                    '<div class="alert alert-danger">Failed to load pending sessions: ' + escapeHtml(String(err)) + '</div>';
                tableWrapper.classList.remove('d-none');
            });
    }

    // ── Table ─────────────────────────────────────────────────────────────────

    function renderTable(list) {
        tbody.innerHTML = '';
        list.forEach(function (session) {
            tbody.appendChild(buildRow(session));
        });
    }

    function buildRow(session) {
        var tr = document.createElement('tr');
        tr.id = 'row-' + session.sessionUuid;

        // Session name
        var tdName = document.createElement('td');
        tdName.className = 'fw-semibold';
        tdName.textContent = session.sessionName || 'Untitled';
        tdName.title = session.sessionUuid;
        tr.appendChild(tdName);

        // Origin badge
        var tdOrigin = document.createElement('td');
        var badge = document.createElement('span');
        badge.className = 'origin-badge origin-' + (session.originMode || '');
        badge.textContent = session.originMode === 'TELEGRAM' ? '\u2708 Telegram' : '\u25CE Background';
        tdOrigin.appendChild(badge);
        tr.appendChild(tdOrigin);

        // Tool name
        var tdTool = document.createElement('td');
        tdTool.className = 'pending-tool-name';
        tdTool.textContent = session.toolName || '\u2014';
        tr.appendChild(tdTool);

        // Waiting since
        var tdAge = document.createElement('td');
        tdAge.className = 'text-muted small';
        tdAge.textContent = formatAge(session.createdAt);
        tr.appendChild(tdAge);

        // Action
        var tdAction = document.createElement('td');
        tdAction.className = 'text-end';
        var actionWrap = document.createElement('div');
        actionWrap.className = 'd-inline-flex gap-2';

        var inputBtn = document.createElement('button');
        inputBtn.type = 'button';
        inputBtn.className = 'btn btn-sm btn-primary';
        inputBtn.innerHTML = '<i class="fa-solid fa-keyboard me-1"></i>Provide Input';
        inputBtn.addEventListener('click', function () { openModal(session, tr); });
        actionWrap.appendChild(inputBtn);

        var dismissBtn = document.createElement('button');
        dismissBtn.type = 'button';
        dismissBtn.className = 'btn btn-sm btn-outline-danger';
        dismissBtn.innerHTML = '<i class="fa-solid fa-xmark me-1"></i>Dismiss';
        dismissBtn.addEventListener('click', function () { dismissSession(session, tr, dismissBtn); });
        actionWrap.appendChild(dismissBtn);

        tdAction.appendChild(actionWrap);
        tr.appendChild(tdAction);

        return tr;
    }

    // ── Modal (delegated to AuthModal) ────────────────────────────────────────

    function openModal(session, tr) {
        AuthModal.show({
            title      : (session.sessionName || 'Session') +
                         (session.toolName ? ' \u2014 ' + session.toolName : ''),
            reasoning  : session.reasoning,
            formSchema : session.formSchema,
            sessionUuid: session.sessionUuid,
            eventId    : session.eventId,
            onDone     : function (action, data, err) {
                if (tr && tr.parentNode) tr.parentNode.removeChild(tr);
                if (tbody.querySelectorAll('tr').length === 0) {
                    tableWrapper.classList.add('d-none');
                    emptyEl.classList.remove('d-none');
                }
                if (err || (data && data.status === 'AWAITING_INPUT')) {
                    // Error or session suspended again — reload to surface state
                    setTimeout(function () { window.location.reload(); }, err ? 600 : 400);
                }
                // BACKGROUND_RESUMED / WEB_RESUMED: row already removed, nothing to do
            }
        });
    }

    function dismissSession(session, tr, buttonEl) {
        if (!session || !session.sessionUuid) return;
        if (!window.confirm('Dismiss this pending request? This will mark it complete and remove it from the list.')) {
            return;
        }

        if (buttonEl) {
            buttonEl.disabled = true;
            buttonEl.innerHTML = '<i class="fa-solid fa-spinner fa-spin me-1"></i>Dismissing';
        }

        fetch('/api/chat/sessions/pending-input/' + encodeURIComponent(session.sessionUuid), {
            method: 'DELETE'
        })
            .then(function (r) {
                if (!r.ok) {
                    return r.json().then(function (body) {
                        var msg = (body && body.message) ? body.message : ('HTTP ' + r.status);
                        throw new Error(msg);
                    }).catch(function () {
                        throw new Error('HTTP ' + r.status);
                    });
                }
                return r.json();
            })
            .then(function () {
                if (tr && tr.parentNode) tr.parentNode.removeChild(tr);
                if (tbody.querySelectorAll('tr').length === 0) {
                    tableWrapper.classList.add('d-none');
                    emptyEl.classList.remove('d-none');
                }
            })
            .catch(function (err) {
                window.alert('Failed to dismiss pending request: ' + escapeHtml(String(err && err.message ? err.message : err)));
                if (buttonEl) {
                    buttonEl.disabled = false;
                    buttonEl.innerHTML = '<i class="fa-solid fa-xmark me-1"></i>Dismiss';
                }
            });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    function formatAge(epochMs) {
        if (!epochMs) return '';
        var diff = Date.now() - epochMs;
        var mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm ago';
        var hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h ago';
        return Math.floor(hrs / 24) + 'd ago';
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    load();

}());
