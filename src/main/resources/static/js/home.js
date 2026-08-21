'use strict';

const statusEl = document.getElementById('home-status');
const gridEl = document.getElementById('agent-app-grid');
const emptyEl = document.getElementById('home-empty');
const appsStatusEl = document.getElementById('my-apps-status');
const appsGridEl = document.getElementById('my-apps-grid');
const appsEmptyEl = document.getElementById('my-apps-empty');

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

function renderApps(apps) {
    appsGridEl.innerHTML = '';
    if (!Array.isArray(apps) || apps.length === 0) {
        appsStatusEl.classList.add('hidden');
        appsGridEl.classList.add('hidden');
        appsEmptyEl.classList.remove('hidden');
        return;
    }

    apps.forEach(function (app) {
        const name = app && app.name ? app.name : 'App';
        const url = app && app.url ? app.url : '#';
        const icon = app && app.navIcon ? app.navIcon : 'fa-solid fa-layer-group';
        const logoDataUrl = app && app.logoDataUrl ? app.logoDataUrl : '';

        const tile = document.createElement('a');
        tile.className = 'agent-app-tile';
        tile.href = url;

        const iconWrap = document.createElement('span');
        iconWrap.className = 'agent-app-icon';

        if (logoDataUrl) {
            const logoImg = document.createElement('img');
            logoImg.className = 'agent-app-logo';
            logoImg.alt = name + ' logo';
            logoImg.src = logoDataUrl;
            iconWrap.appendChild(logoImg);
        } else {
            const iconEl = document.createElement('i');
            iconEl.className = icon;
            iconWrap.appendChild(iconEl);
        }

        const title = document.createElement('span');
        title.className = 'agent-app-title';
        title.textContent = name;

        tile.appendChild(iconWrap);
        tile.appendChild(title);
        appsGridEl.appendChild(tile);
    });

    appsStatusEl.classList.add('hidden');
    appsEmptyEl.classList.add('hidden');
    appsGridEl.classList.remove('hidden');
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

function loadMyApps() {
    fetch('/api/surfaces/my-apps')
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('HTTP ' + resp.status + ' - ' + resp.statusText);
            }
            return resp.json();
        })
        .then(function (apps) {
            renderApps(Array.isArray(apps) ? apps : []);
        })
        .catch(function (err) {
            appsStatusEl.textContent = 'Failed to load apps: ' + err.message;
            appsGridEl.classList.add('hidden');
            appsEmptyEl.classList.remove('hidden');
        });
}

loadMyApps();
loadAssignedAgents();
