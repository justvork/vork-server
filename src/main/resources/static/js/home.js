'use strict';

const statusEl = document.getElementById('home-status');
const gridEl = document.getElementById('agent-app-grid');
const emptyEl = document.getElementById('home-empty');

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\"/g, '&quot;');
}

function renderAgents(agents) {
    gridEl.innerHTML = '';
    if (!Array.isArray(agents) || agents.length === 0) {
        statusEl.classList.add('hidden');
        gridEl.classList.add('hidden');
        emptyEl.classList.remove('hidden');
        return;
    }

    agents.forEach(function (agent) {
        const name = agent && agent.name ? agent.name : 'Agent';
        const uuid = agent && agent.uuid ? agent.uuid : '';
        if (!uuid) {
            return;
        }

        const tile = document.createElement('a');
        tile.className = 'agent-app-tile';
        tile.href = '/agent/' + encodeURIComponent(uuid);
        tile.innerHTML =
            '<span class="agent-app-icon"><i class="fa-solid fa-comments"></i></span>' +
            '<span class="agent-app-title">' + escapeHtml(name) + '</span>';
        gridEl.appendChild(tile);
    });

    statusEl.classList.add('hidden');
    emptyEl.classList.add('hidden');
    gridEl.classList.remove('hidden');
}

function loadAssignedAgents() {
    fetch('/api/chat/agents?type=INTERACTIVE')
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('HTTP ' + resp.status + ' - ' + resp.statusText);
            }
            return resp.json();
        })
        .then(function (agents) {
            renderAgents(Array.isArray(agents) ? agents : []);
        })
        .catch(function (err) {
            statusEl.textContent = 'Failed to load agents: ' + err.message;
            gridEl.classList.add('hidden');
            emptyEl.classList.remove('hidden');
        });
}

loadAssignedAgents();
