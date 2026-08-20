'use strict';

const bodyEl = document.body;
const agentTemplateId = bodyEl ? bodyEl.getAttribute('data-agent-template-id') : '';

const titleEl = document.getElementById('agent-title');
const statusEl = document.getElementById('agent-prompt-status');
const listEl = document.getElementById('agent-session-list');
const emptyEl = document.getElementById('agent-session-empty');
const emptyHintEl = document.getElementById('agent-session-empty-hint');
const searchInputEl = document.getElementById('session-search');
const startNewBtn = document.getElementById('start-new-btn');

let defaultProvider = 'GEMINI';
let searchTimer = null;
let autoStartAttempted = false;

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\"/g, '&quot;');
}

function formatRelativeTime(timestamp) {
    const created = Number(timestamp || 0);
    if (!created) {
        return 'Unknown date';
    }

    const deltaMs = Date.now() - created;
    const minute = 60 * 1000;
    const hour = 60 * minute;
    const day = 24 * hour;

    if (deltaMs < minute) return 'Just now';
    if (deltaMs < hour) return Math.floor(deltaMs / minute) + 'm ago';
    if (deltaMs < day) return Math.floor(deltaMs / hour) + 'h ago';
    if (deltaMs < day * 30) return Math.floor(deltaMs / day) + 'd ago';

    return new Date(created).toLocaleString();
}

function showEmpty(message) {
    listEl.classList.add('hidden');
    emptyEl.classList.remove('hidden');
    if (message) {
        emptyHintEl.textContent = message;
    }
}

function renderSessions(sessions) {
    listEl.innerHTML = '';
    if (!Array.isArray(sessions) || sessions.length === 0) {
        showEmpty('No sessions match your search.');
        return;
    }

    sessions.forEach(function (session) {
        const sessionUuid = session && session.sessionUuid ? session.sessionUuid : '';
        if (!sessionUuid) {
            return;
        }

        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'agent-session-item';
        item.innerHTML =
            '<span class="agent-session-name">' + escapeHtml(session.sessionName || 'Untitled') + '</span>' +
            '<span class="agent-session-meta">' + escapeHtml(formatRelativeTime(session.createdAt)) + '</span>';
        item.addEventListener('click', function () {
            window.location.href = '/chat?sessionUuid=' + encodeURIComponent(sessionUuid);
        });
        listEl.appendChild(item);
    });

    emptyEl.classList.add('hidden');
    listEl.classList.remove('hidden');
}

function loadSystemDefaults() {
    return fetch('/api/system/settings')
        .then(function (resp) { return resp.ok ? resp.json() : null; })
        .then(function (settings) {
            if (settings && settings.defaultProvider) {
                defaultProvider = settings.defaultProvider;
            }
        })
        .catch(function () {
            defaultProvider = 'GEMINI';
        });
}

function loadAgentTitle() {
    return fetch('/api/chat/agents?type=INTERACTIVE')
        .then(function (resp) { return resp.ok ? resp.json() : []; })
        .then(function (agents) {
            const list = Array.isArray(agents) ? agents : [];
            const selected = list.find(function (item) {
                return item && item.uuid === agentTemplateId;
            });

            if (!selected) {
                titleEl.textContent = 'Unavailable Agent';
                statusEl.textContent = 'This agent is not assigned to your account.';
                showEmpty('Select another agent from Home.');
                if (startNewBtn) {
                    startNewBtn.disabled = true;
                }
                return false;
            }

            titleEl.textContent = selected.name || 'Agent';
            return true;
        });
}

function buildSessionsUrl(search) {
    const trimmedSearch = String(search || '').trim();
    let url = '/api/chat/sessions?agentTemplateId=' + encodeURIComponent(agentTemplateId);
    if (trimmedSearch) {
        url += '&search=' + encodeURIComponent(trimmedSearch) + '&limit=200';
    } else {
        url += '&limit=5';
    }
    return url;
}

function loadSessions(search) {
    const hasSearch = !!String(search || '').trim();
    statusEl.textContent = hasSearch ? 'Searching sessions...' : 'Loading recent sessions...';
    statusEl.classList.remove('hidden');

    fetch(buildSessionsUrl(search))
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('HTTP ' + resp.status + ' - ' + resp.statusText);
            }
            return resp.json();
        })
        .then(function (sessions) {
            const list = Array.isArray(sessions) ? sessions : [];
            if (!hasSearch && list.length === 0 && !autoStartAttempted) {
                autoStartAttempted = true;
                statusEl.classList.remove('hidden');
                statusEl.textContent = 'No previous sessions found. Starting a new chat...';
                startNewSession();
                return;
            }
            statusEl.classList.add('hidden');
            renderSessions(list);
        })
        .catch(function (err) {
            statusEl.classList.remove('hidden');
            statusEl.textContent = 'Failed to load sessions: ' + err.message;
            showEmpty('Try again in a few seconds.');
        });
}

function startNewSession() {
    if (!agentTemplateId) {
        return;
    }

    if (startNewBtn) {
        startNewBtn.disabled = true;
    }

    fetch('/api/chat/session/new?provider=' + encodeURIComponent(defaultProvider)
        + '&agentTemplateId=' + encodeURIComponent(agentTemplateId))
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('HTTP ' + resp.status + ' - ' + resp.statusText);
            }
            return resp.json();
        })
        .then(function (session) {
            if (!session || !session.sessionUuid) {
                throw new Error('Session UUID missing from response');
            }
            window.location.href = '/chat?sessionUuid=' + encodeURIComponent(session.sessionUuid);
        })
        .catch(function (err) {
            if (startNewBtn) {
                startNewBtn.disabled = false;
            }
            statusEl.classList.remove('hidden');
            statusEl.textContent = 'Failed to create session: ' + err.message;
        });
}

if (!agentTemplateId) {
    statusEl.textContent = 'Missing agent identifier.';
    showEmpty('Return to Home and choose an agent again.');
    if (startNewBtn) {
        startNewBtn.disabled = true;
    }
} else {
    Promise.all([loadSystemDefaults(), loadAgentTitle()])
        .then(function (results) {
            const isAssigned = results[1] === true;
            if (!isAssigned) {
                return;
            }
            loadSessions('');
        });
}

if (startNewBtn) {
    startNewBtn.addEventListener('click', startNewSession);
}

if (searchInputEl) {
    searchInputEl.addEventListener('input', function () {
        if (searchTimer) {
            window.clearTimeout(searchTimer);
        }
        const term = searchInputEl.value;
        searchTimer = window.setTimeout(function () {
            loadSessions(term);
        }, 250);
    });
}
