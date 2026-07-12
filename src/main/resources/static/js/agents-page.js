/* agents-page.js */

let agentModal;
let allAgents = [];
let allTools = [];
let allSkills = [];
let modalTools = [];
let modalSkills = [];

document.addEventListener('DOMContentLoaded', function () {
    agentModal = new VorkModal(document.getElementById('agentModal'));
    loadData();

    document.getElementById('agentModal').addEventListener('hidden.bs.modal', function () {
        document.getElementById('tool-search').value = '';
        document.getElementById('skill-search').value = '';
        document.getElementById('tool-dropdown').classList.add('hidden');
        document.getElementById('skill-dropdown').classList.add('hidden');
    });

    document.addEventListener('click', function (e) {
        if (!e.target.closest('#tool-search') && !e.target.closest('#tool-dropdown')) {
            document.getElementById('tool-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#skill-search') && !e.target.closest('#skill-dropdown')) {
            document.getElementById('skill-dropdown').classList.add('hidden');
        }
    });
});

async function loadData() {
    try {
        const results = await Promise.all([
            fetch('/api/agents'),
            fetch('/api/management/tools'),
            fetch('/api/skills')
        ]);
        const agentsRes = results[0];
        const toolsRes = results[1];
        const skillsRes = results[2];
        allAgents = agentsRes.ok ? await agentsRes.json() : [];
        allTools = toolsRes.ok ? await toolsRes.json() : [];
        allSkills = skillsRes.ok ? await skillsRes.json() : [];
    } catch (_e) {
        showAlert('Failed to load data.', 'warning');
    }
}

function openCreate() {
    document.getElementById('agentModalLabel').textContent = 'New Agent';
    document.getElementById('agent-id').value = '';
    document.getElementById('agent-name').value = '';
    document.getElementById('agent-prompt').value = '';
    modalTools = [];
    modalSkills = [];
    renderToolPills();
    renderSkillPills();
    agentModal.show();
}

function openEdit(id) {
    const agent = allAgents.find(function (a) { return a.uuid === id; });
    if (!agent) {
        showAlert('Agent not found — reload the page.', 'warning');
        return;
    }

    document.getElementById('agentModalLabel').textContent = 'Edit Agent: ' + agent.name;
    document.getElementById('agent-id').value = agent.uuid;
    document.getElementById('agent-name').value = agent.name;
    document.getElementById('agent-prompt').value = agent.systemPrompt || '';
    modalTools = agent.allowedTools ? agent.allowedTools.slice() : [];
    modalSkills = agent.skillUuids ? agent.skillUuids.slice() : [];
    renderToolPills();
    renderSkillPills();
    agentModal.show();
}

function renderToolPills() {
    const container = document.getElementById('tool-pill-container');
    container.innerHTML = '';
    if (modalTools.length === 0) {
        container.innerHTML = '<span class="text-muted small">No tools assigned — agent will have access to all tools.</span>';
        return;
    }

    modalTools.forEach(function (toolId) {
        const desc = allTools.find(function (t) { return t.id === toolId; });
        const label = desc ? (desc.friendlyName || desc.name || toolId) : toolId;
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML =
            '<i class="fa-solid fa-screwdriver-wrench"></i>' +
            '<span>' + escapeHtml(label) + '</span>' +
            '<span class="remove-tool" onclick="removeTool(\'' + escapeHtml(toolId) + '\')" title="Remove tool">✕</span>';
        container.appendChild(pill);
    });
}

function removeTool(toolId) {
    modalTools = modalTools.filter(function (t) { return t !== toolId; });
    renderToolPills();
    filterTools();
}

function filterTools() {
    const query = document.getElementById('tool-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('tool-dropdown');
    const list = document.getElementById('tool-list');

    const matches = allTools.filter(function (t) {
        if (modalTools.includes(t.id)) return false;
        if (!query) return true;
        const label = ((t.friendlyName || '') + ' ' + (t.name || '') + ' ' + (t.id || '') + ' ' + (t.category || '')).toLowerCase();
        return label.includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (t) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML =
            '<div class="d-flex align-items-center gap-2">' +
            '  <i class="fa-solid fa-screwdriver-wrench fa-xs text-secondary"></i>' +
            '  <span class="fw-semibold small">' + escapeHtml(t.friendlyName || t.name || t.id) + '</span>' +
            (t.category ? '  <span class="badge bg-dark border border-secondary text-secondary tool-meta-badge">' + escapeHtml(t.category) + '</span>' : '') +
            '</div>' +
            (t.description ? '<div class="text-muted tool-description">' + escapeHtml(t.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addTool(t.id);
            document.getElementById('tool-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });
    dropdown.classList.remove('hidden');
}

function addTool(toolId) {
    if (!modalTools.includes(toolId)) {
        modalTools.push(toolId);
        renderToolPills();
    }
}

function renderSkillPills() {
    const container = document.getElementById('skill-pill-container');
    container.innerHTML = '';
    if (modalSkills.length === 0) {
        container.innerHTML = '<span class="text-muted small">No skills assigned.</span>';
        return;
    }

    modalSkills.forEach(function (uuid) {
        const skill = allSkills.find(function (s) { return s.uuid === uuid; });
        const label = skill ? skill.name : uuid;
        const pill = document.createElement('span');
        pill.className = 'tool-pill skill-pill';
        pill.innerHTML =
            '<i class="fa-solid fa-bolt"></i>' +
            '<span>' + escapeHtml(label) + '</span>' +
            '<span class="remove-tool" title="Remove skill">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeSkill(uuid);
        });
        container.appendChild(pill);
    });
}

function removeSkill(uuid) {
    modalSkills = modalSkills.filter(function (s) { return s !== uuid; });
    renderSkillPills();
    filterSkills();
}

function filterSkills() {
    const query = document.getElementById('skill-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('skill-dropdown');
    const list = document.getElementById('skill-list');

    const matches = allSkills.filter(function (s) {
        if (modalSkills.includes(s.uuid)) return false;
        if (!query) return true;
        return ((s.name || '') + ' ' + (s.description || '')).toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (s) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML =
            '<div class="d-flex align-items-center gap-2">' +
            '  <i class="fa-solid fa-bolt fa-xs text-secondary"></i>' +
            '  <span class="fw-semibold small">' + escapeHtml(s.name) + '</span>' +
            '  <span class="badge bg-dark border border-secondary text-secondary tool-meta-badge">v' + (s.version || 1) + '</span>' +
            '</div>' +
            (s.description ? '<div class="text-muted tool-description">' + escapeHtml(s.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addSkill(s.uuid);
            document.getElementById('skill-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });
    dropdown.classList.remove('hidden');
}

function addSkill(uuid) {
    if (!modalSkills.includes(uuid)) {
        modalSkills.push(uuid);
        renderSkillPills();
    }
}

async function saveAgent() {
    const id = document.getElementById('agent-id').value.trim();
    const name = document.getElementById('agent-name').value.trim();
    if (!name) {
        showAlert('Name is required.', 'warning');
        return;
    }

    const body = {
        name: name,
        systemPrompt: document.getElementById('agent-prompt').value,
        allowedTools: modalTools.slice(),
        skillUuids: modalSkills.slice()
    };

    const btn = document.getElementById('btn-save-agent');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving…';

    try {
        const url = id ? '/api/agents/' + id : '/api/agents';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        agentModal.hide();
        showAlert(id ? 'Agent updated.' : 'Agent created.', 'success');
        setTimeout(function () { location.reload(); }, 800);
    } catch (_e) {
        showAlert('Network error saving agent.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save me-1"></i>Save Agent';
    }
}

async function deleteAgent(id) {
    if (!confirm('Delete this agent? This cannot be undone.')) return;
    try {
        const res = await fetch('/api/agents/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Agent deleted.', 'success');
        const row = document.getElementById('row-' + id);
        if (row) row.remove();
        allAgents = allAgents.filter(function (a) { return a.uuid !== id; });
    } catch (_e) {
        showAlert('Network error deleting agent.', 'danger');
    }
}

function showAlert(msg, type) {
    const area = document.getElementById('alert-area');
    area.innerHTML =
        '<div class="alert alert-' + type + ' alert-dismissible fade show" role="alert">' +
        escapeHtml(msg) +
        '<button type="button" class="btn-close" data-bs-dismiss="alert"></button>' +
        '</div>';
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
