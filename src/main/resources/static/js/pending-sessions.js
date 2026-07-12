// ── Pending Sessions page script ─────────────────────────────────────────────

'use strict';

(function () {

    var loadingEl      = document.getElementById('loading-state');
    var emptyEl        = document.getElementById('empty-state');
    var tableWrapper   = document.getElementById('sessions-table-wrapper');
    var tbody          = document.getElementById('sessions-tbody');

    var sessions  = [];   // cached list from the API

    // ── Load ──────────────────────────────────────────────────────────────────

    function load() {
        fetch('/api/chat/sessions/pending-input')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
            .then(function (data) {
                loadingEl.classList.add('hidden');
                sessions = data || [];
                if (sessions.length === 0) {
                    emptyEl.classList.remove('hidden');
                    return;
                }
                renderTable(sessions);
                tableWrapper.classList.remove('hidden');
            })
            .catch(function (err) {
                loadingEl.classList.add('hidden');
                tableWrapper.innerHTML =
                    '<div class="rounded-lg border border-rose-700/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">Failed to load pending sessions: ' + escapeHtml(String(err)) + '</div>';
                tableWrapper.classList.remove('hidden');
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
        tr.className = 'border-b border-zinc-800/80 last:border-0';

        // Session name
        var tdName = document.createElement('td');
        tdName.className = 'px-3 py-2 font-semibold text-zinc-100';
        tdName.textContent = session.sessionName || 'Untitled';
        tdName.title = session.sessionUuid;
        tr.appendChild(tdName);

        // Origin badge
        var tdOrigin = document.createElement('td');
        tdOrigin.className = 'px-3 py-2';
        var badge = document.createElement('span');
        badge.className = 'origin-badge origin-' + (session.originMode || '');
        badge.textContent = session.originMode === 'TELEGRAM' ? '\u2708 Telegram' : '\u25CE Background';
        tdOrigin.appendChild(badge);
        tr.appendChild(tdOrigin);

        // Tool name
        var tdTool = document.createElement('td');
        tdTool.className = 'pending-tool-name px-3 py-2';
        tdTool.textContent = session.toolName || '\u2014';
        tr.appendChild(tdTool);

        // Waiting since
        var tdAge = document.createElement('td');
        tdAge.className = 'px-3 py-2 text-xs text-zinc-400';
        tdAge.textContent = formatAge(session.createdAt);
        tr.appendChild(tdAge);

        // Action
        var tdAction = document.createElement('td');
        tdAction.className = 'px-3 py-2 text-right';
        var actionWrap = document.createElement('div');
        actionWrap.className = 'inline-flex gap-2';

        var inputBtn = document.createElement('button');
        inputBtn.type = 'button';
        inputBtn.className = 'rounded-lg bg-[#fdaa02] px-2.5 py-1.5 text-xs font-semibold text-black transition-colors hover:bg-[#e89a02]';
        inputBtn.innerHTML = '<i class="fa-solid fa-keyboard mr-1"></i>Provide Input';
        inputBtn.addEventListener('click', function () {
            window.location.href = '/job-monitor.html?session=' + encodeURIComponent(session.sessionUuid);
        });
        actionWrap.appendChild(inputBtn);

        var dismissBtn = document.createElement('button');
        dismissBtn.type = 'button';
        dismissBtn.className = 'rounded-lg border border-rose-500/40 px-2.5 py-1.5 text-xs font-medium text-rose-300 transition-colors hover:bg-rose-500/15';
        dismissBtn.innerHTML = '<i class="fa-solid fa-xmark mr-1"></i>Dismiss';
        dismissBtn.addEventListener('click', function () { dismissSession(session, tr, dismissBtn); });
        actionWrap.appendChild(dismissBtn);

        tdAction.appendChild(actionWrap);
        tr.appendChild(tdAction);

        return tr;
    }

    function dismissSession(session, tr, buttonEl) {
        if (!session || !session.sessionUuid) return;
        if (!window.confirm('Dismiss this pending request? This will mark it complete and remove it from the list.')) {
            return;
        }

        if (buttonEl) {
            buttonEl.disabled = true;
            buttonEl.innerHTML = '<i class="fa-solid fa-spinner fa-spin mr-1"></i>Dismissing';
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
                    tableWrapper.classList.add('hidden');
                    emptyEl.classList.remove('hidden');
                }
            })
            .catch(function (err) {
                window.alert('Failed to dismiss pending request: ' + escapeHtml(String(err && err.message ? err.message : err)));
                if (buttonEl) {
                    buttonEl.disabled = false;
                    buttonEl.innerHTML = '<i class="fa-solid fa-xmark mr-1"></i>Dismiss';
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
