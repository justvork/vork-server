/* agents-page.js */

let agentModal;
let allAgents = [];
let allTools = [];
let allSkills = [];
let allReflections = [];
let allReflectionGroups = [];
let allReflectionBindingOptions = [];
let allUsers = [];
let providerGroups = [];
let providerGroupByKey = {};
let modalTools = [];
let modalSkills = [];
let modalBindingUuids = [];
let modalAssignedUsers = [];

document.addEventListener('DOMContentLoaded', function () {
    agentModal = new VorkModal(document.getElementById('agentModal'));
    loadData();

    document.getElementById('agentModal').addEventListener('hidden.bs.modal', function () {
        document.getElementById('tool-search').value = '';
        document.getElementById('skill-search').value = '';
        document.getElementById('assigned-user-search').value = '';
        document.getElementById('tool-dropdown').classList.add('hidden');
        document.getElementById('skill-dropdown').classList.add('hidden');
        document.getElementById('assigned-user-dropdown').classList.add('hidden');
    });

    document.addEventListener('click', function (e) {
        if (!e.target.closest('#tool-search') && !e.target.closest('#tool-dropdown')) {
            document.getElementById('tool-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#skill-search') && !e.target.closest('#skill-dropdown')) {
            document.getElementById('skill-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#reflection-binding-search') && !e.target.closest('#reflection-binding-dropdown')) {
            document.getElementById('reflection-binding-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#assigned-user-search') && !e.target.closest('#assigned-user-dropdown')) {
            document.getElementById('assigned-user-dropdown').classList.add('hidden');
        }
    });
});

async function loadData() {
    try {
        const results = await Promise.all([
            fetch('/api/agents'),
            fetch('/api/management/tools'),
            fetch('/api/skills'),
            fetch('/api/reflections'),
            fetch('/api/reflection-groups'),
            fetch('/api/users'),
            fetch('/api/ai/providers')
        ]);
        const agentsRes = results[0];
        const toolsRes = results[1];
        const skillsRes = results[2];
        const reflectionsRes = results[3];
        const reflectionGroupsRes = results[4];
        const usersRes = results[5];
        const providerRes = results[6];
        allAgents = agentsRes.ok ? await agentsRes.json() : [];
        allTools = toolsRes.ok ? await toolsRes.json() : [];
        allSkills = skillsRes.ok ? await skillsRes.json() : [];
        allReflections = reflectionsRes.ok ? await reflectionsRes.json() : [];
        allReflectionGroups = reflectionGroupsRes.ok ? await reflectionGroupsRes.json() : [];
        allUsers = usersRes.ok ? await usersRes.json() : [];
        providerGroups = providerRes.ok ? await providerRes.json() : [];
        providerGroupByKey = (providerGroups || []).reduce(function (acc, group) {
            if (group && group.providerKey) {
                acc[group.providerKey.toUpperCase()] = group;
            }
            return acc;
        }, {});
        buildRecommendedModelLookupOptions('agent-recommended-model-lookup', '');
        allReflectionBindingOptions = buildReflectionBindingOptions();
        renderAgentBindingColumn();
        renderAgentUsersColumn();
        renderAgentRecommendedModelColumn();
    } catch (_e) {
        showAlert('Failed to load data.', 'warning');
    }
}

function renderAgentBindingColumn() {
    (allAgents || []).forEach(function (agent) {
        const cell = document.getElementById('agent-bindings-' + agent.uuid);
        if (!cell) return;

        const labels = bindingLabelsForAgent(agent);
        if (labels.length === 0) {
            cell.innerHTML = '<span class="text-muted small">— none —</span>';
            return;
        }

        cell.innerHTML = labels.map(function (label) {
            return '<span class="tool-pill">'
                + '<i class="fa-solid fa-link"></i>'
                + '<span>' + escapeHtml(label) + '</span>'
                + '</span>';
        }).join(' ');
    });
}

function bindingLabelsForAgent(agent) {
    const labels = [];
    const assignments = agent && agent.reflectionBindings ? agent.reflectionBindings : [];
    assignments.forEach(function (assignment) {
        (assignment.bindingUuids || []).forEach(function (bindingUuid) {
            const option = allReflectionBindingOptions.find(function (item) { return item.uuid === bindingUuid; });
            const label = option ? option.label : bindingUuid;
            if (label && !labels.includes(label)) labels.push(label);
        });
    });
    return labels;
}

function renderAgentUsersColumn() {
    (allAgents || []).forEach(function (agent) {
        const cell = document.getElementById('agent-users-' + agent.uuid);
        if (!cell) return;

        if (agent.name && agent.name.toLowerCase() === 'concierge') {
            cell.innerHTML = '<span class="tool-pill"><i class="fa-solid fa-users"></i><span>All users</span></span>';
            return;
        }

        const users = agent.assignedUsernames || [];
        if (users.length === 0) {
            cell.innerHTML = '<span class="text-muted small">Admins only (auto)</span>';
            return;
        }

        cell.innerHTML = users.map(function (username) {
            return '<span class="tool-pill"><i class="fa-solid fa-user"></i><span>' + escapeHtml(username) + '</span></span>';
        }).join(' ');
    });
}

function parseRecommendedModel(raw) {
    if (!raw || typeof raw !== 'string') return null;
    const trimmed = raw.trim();
    const sep = trimmed.indexOf(':');
    if (sep <= 0 || sep >= trimmed.length - 1) return null;
    const provider = trimmed.substring(0, sep).trim().toUpperCase();
    const modelId = trimmed.substring(sep + 1).trim();
    if (!provider || !modelId) return null;
    return { provider: provider, modelId: modelId };
}

function evaluateRecommendedModel(raw) {
    if (!raw || !String(raw).trim()) {
        return { text: '—', warning: null, configured: false };
    }
    const parsed = parseRecommendedModel(raw);
    if (!parsed) {
        return { text: raw, warning: 'Invalid format (expected PROVIDER:model-id)', configured: false };
    }
    const group = providerGroupByKey[parsed.provider];
    if (!group || !group.configured) {
        return { text: parsed.provider + ':' + parsed.modelId, warning: 'Provider not configured', configured: false };
    }
    const hasModel = (group.models || []).some(function (m) {
        return (m.modelId || '').toLowerCase() === parsed.modelId.toLowerCase();
    });
    if (!hasModel) {
        return { text: parsed.provider + ':' + parsed.modelId, warning: 'Model not available', configured: true };
    }
    return { text: parsed.provider + ':' + parsed.modelId, warning: null, configured: true };
}

function renderAgentRecommendedModelColumn() {
    (allAgents || []).forEach(function (agent) {
        const cell = document.getElementById('agent-model-' + agent.uuid);
        if (!cell) return;

        const evalResult = evaluateRecommendedModel(agent.recommendedModel);
        if (evalResult.text === '—') {
            cell.innerHTML = '<span class="text-muted small">—</span>';
            return;
        }
        if (evalResult.warning) {
            cell.innerHTML = ''
                + '<span class="tool-pill">'
                + '  <i class="fa-solid fa-triangle-exclamation text-warning"></i>'
                + '  <span>' + escapeHtml(evalResult.text) + '</span>'
                + '</span>'
                + '<div class="text-warning small mt-1">' + escapeHtml(evalResult.warning) + '</div>';
            return;
        }
        cell.innerHTML = ''
            + '<span class="tool-pill">'
            + '  <i class="fa-solid fa-microchip"></i>'
            + '  <span>' + escapeHtml(evalResult.text) + '</span>'
            + '</span>';
    });
}

function buildRecommendedModelLookupOptions(selectId, selectedRaw) {
    const select = document.getElementById(selectId);
    if (!select) return;

    const normalizedSelected = selectedRaw ? String(selectedRaw).trim().toUpperCase() : '';
    select.innerHTML = '<option value="">-- Select from configured provider models --</option>';

    let optionCount = 0;
    (providerGroups || []).forEach(function (group) {
        if (!group || !group.configured || !group.providerKey) return;
        const models = Array.isArray(group.models) ? group.models : [];
        if (models.length === 0) return;

        const optGroup = document.createElement('optgroup');
        optGroup.label = group.providerLabel || group.providerKey;

        models.forEach(function (model) {
            const modelId = model && model.modelId ? String(model.modelId).trim() : '';
            if (!modelId) return;

            const value = String(group.providerKey).toUpperCase() + ':' + modelId;
            const option = document.createElement('option');
            option.value = value;
            option.textContent = (group.providerLabel || group.providerKey) + ' - ' + (model.label || modelId);
            if (normalizedSelected && value.toUpperCase() === normalizedSelected) {
                option.selected = true;
            }
            optGroup.appendChild(option);
            optionCount++;
        });

        if (optGroup.children.length > 0) {
            select.appendChild(optGroup);
        }
    });

    if (optionCount === 0) {
        const empty = document.createElement('option');
        empty.value = '';
        empty.textContent = '-- No configured provider models available --';
        empty.disabled = true;
        select.appendChild(empty);
    }

    syncRecommendedModelLookup('agent-recommended-model', selectId);
}

function setRecommendedModelFromLookup(inputId, selectId) {
    const input = document.getElementById(inputId);
    const select = document.getElementById(selectId);
    if (!input || !select) return;
    if (!select.value) return;
    input.value = select.value;
}

function syncRecommendedModelLookup(inputId, selectId) {
    const input = document.getElementById(inputId);
    const select = document.getElementById(selectId);
    if (!input || !select) return;

    const normalized = input.value ? String(input.value).trim().toUpperCase() : '';
    let matched = false;
    for (let i = 0; i < select.options.length; i++) {
        const option = select.options[i];
        if (option.value && option.value.toUpperCase() === normalized) {
            select.value = option.value;
            matched = true;
            break;
        }
    }
    if (!matched) {
        select.value = '';
    }
}

function openCreate() {
    document.getElementById('agentModalLabel').textContent = 'New Agent';
    document.getElementById('agent-id').value = '';
    document.getElementById('agent-name').value = '';
    document.getElementById('agent-prompt').value = '';
    document.getElementById('agent-recommended-model').value = '';
    buildRecommendedModelLookupOptions('agent-recommended-model-lookup', '');
    modalTools = [];
    modalSkills = [];
    modalBindingUuids = [];
    modalAssignedUsers = [];
    renderToolPills();
    renderSkillPills();
    renderReflectionBindingPills();
    renderAssignedUserPills();
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
    document.getElementById('agent-recommended-model').value = agent.recommendedModel || '';
    buildRecommendedModelLookupOptions('agent-recommended-model-lookup', agent.recommendedModel || '');
    modalTools = agent.allowedTools ? agent.allowedTools.slice() : [];
    modalSkills = agent.skillUuids ? agent.skillUuids.slice() : [];
    modalBindingUuids = extractBindingUuidsFromAssignments(agent.reflectionBindings);
    modalAssignedUsers = agent.assignedUsernames ? agent.assignedUsernames.slice() : [];
    renderToolPills();
    renderSkillPills();
    renderReflectionBindingPills();
    renderAssignedUserPills();
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

function buildReflectionBindingOptions() {
    const options = [];
    (allReflectionGroups || []).forEach(function (entry) {
        const group = entry.group || entry;
        const groupName = group && group.name ? group.name : (group && group.uuid ? group.uuid : 'Group');
        const bindings = entry && entry.bindings ? entry.bindings : [];
        bindings.forEach(function (binding) {
            if (!binding || !binding.uuid) return;
            options.push({
                uuid: binding.uuid,
                groupUuid: group.uuid,
                bindingName: binding.name || binding.uuid,
                groupName: groupName,
                label: groupName + ' (' + (binding.name || binding.uuid) + ')'
            });
        });
    });
    return options;
}

function extractBindingUuidsFromAssignments(assignments) {
    const unique = [];
    (assignments || []).forEach(function (assignment) {
        (assignment.bindingUuids || []).forEach(function (uuid) {
            if (uuid && !unique.includes(uuid)) unique.push(uuid);
        });
    });
    return unique;
}

function renderReflectionBindingPills() {
    const container = document.getElementById('reflection-binding-pill-container');
    if (!container) return;
    container.innerHTML = '';
    if (!modalBindingUuids || modalBindingUuids.length === 0) {
        container.innerHTML = '<span class="text-muted small">No bindings assigned.</span>';
        return;
    }

    modalBindingUuids.forEach(function (uuid) {
        const option = allReflectionBindingOptions.find(function (item) { return item.uuid === uuid; });
        const label = option ? option.label : uuid;
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-link"></i>'
            + '<span>' + escapeHtml(label) + '</span>'
            + '<span class="remove-tool" title="Remove binding">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeReflectionBinding(uuid);
        });
        container.appendChild(pill);
    });
}

function removeReflectionBinding(uuid) {
    modalBindingUuids = (modalBindingUuids || []).filter(function (id) { return id !== uuid; });
    renderReflectionBindingPills();
    filterReflectionBindings();
}

function filterReflectionBindings() {
    const query = document.getElementById('reflection-binding-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('reflection-binding-dropdown');
    const list = document.getElementById('reflection-binding-options');
    if (!dropdown || !list) return;

    const matches = allReflectionBindingOptions.filter(function (option) {
        if ((modalBindingUuids || []).includes(option.uuid)) return false;
        if (!query) return true;
        return (option.label || '').toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (option) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML = ''
            + '<div class="d-flex align-items-center gap-2">'
            + '  <i class="fa-solid fa-link fa-xs text-secondary"></i>'
            + '  <span class="fw-semibold small">' + escapeHtml(option.label) + '</span>'
            + '</div>';
        li.addEventListener('click', function () {
            addReflectionBinding(option.uuid);
            document.getElementById('reflection-binding-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
}

function addReflectionBinding(uuid) {
    if (!uuid) return;
    if (!modalBindingUuids.includes(uuid)) {
        modalBindingUuids.push(uuid);
        renderReflectionBindingPills();
    }
}

function reflectionBindingsPayloadFromSelection() {
    const selected = (modalBindingUuids || []).slice();
    if (selected.length === 0) return [];

    const groupedByReflection = {};
    selected.forEach(function (bindingUuid) {
        const option = allReflectionBindingOptions.find(function (item) { return item.uuid === bindingUuid; });
        if (!option) return;

        const reflectionsInGroup = (allReflections || []).filter(function (reflection) {
            return reflection && reflection.groupUuid === option.groupUuid;
        });

        reflectionsInGroup.forEach(function (reflection) {
            const reflectionId = reflection.id;
            if (!reflectionId) return;
            if (!groupedByReflection[reflectionId]) groupedByReflection[reflectionId] = [];
            if (!groupedByReflection[reflectionId].includes(bindingUuid)) {
                groupedByReflection[reflectionId].push(bindingUuid);
            }
        });
    });

    return Object.keys(groupedByReflection).map(function (reflectionId) {
        return {
            reflectionId: reflectionId,
            bindingUuids: groupedByReflection[reflectionId]
        };
    });
}

function renderAssignedUserPills() {
    const container = document.getElementById('assigned-user-pill-container');
    if (!container) return;
    container.innerHTML = '';

    if (!modalAssignedUsers || modalAssignedUsers.length === 0) {
        container.innerHTML = '<span class="text-muted small">No explicit users assigned (admins still have access).</span>';
        return;
    }

    modalAssignedUsers.forEach(function (username) {
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-user"></i>'
            + '<span>' + escapeHtml(username) + '</span>'
            + '<span class="remove-tool" title="Remove user assignment">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeAssignedUser(username);
        });
        container.appendChild(pill);
    });
}

function removeAssignedUser(username) {
    modalAssignedUsers = (modalAssignedUsers || []).filter(function (u) { return u !== username; });
    renderAssignedUserPills();
    filterAssignedUsers();
}

function addAssignedUser(username) {
    if (!username) return;
    if (!modalAssignedUsers.includes(username)) {
        modalAssignedUsers.push(username);
        renderAssignedUserPills();
    }
}

function filterAssignedUsers() {
    const query = document.getElementById('assigned-user-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('assigned-user-dropdown');
    const list = document.getElementById('assigned-user-options');
    if (!dropdown || !list) return;

    const matches = (allUsers || []).filter(function (user) {
        const username = user.username || '';
        if (modalAssignedUsers.includes(username)) return false;
        if (!query) return true;
        return username.toLowerCase().includes(query)
            || (user.role || '').toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (user) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML = ''
            + '<div class="d-flex align-items-center gap-2">'
            + '  <i class="fa-solid fa-user fa-xs text-secondary"></i>'
            + '  <span class="fw-semibold small">' + escapeHtml(user.username || '') + '</span>'
            + (user.role ? '  <span class="badge bg-dark border border-secondary text-secondary tool-meta-badge">' + escapeHtml(user.role) + '</span>' : '')
            + '</div>';
        li.addEventListener('click', function () {
            addAssignedUser(user.username);
            document.getElementById('assigned-user-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
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
        recommendedModel: document.getElementById('agent-recommended-model').value.trim(),
        allowedTools: modalTools.slice(),
        skillUuids: modalSkills.slice(),
        reflectionBindings: reflectionBindingsPayloadFromSelection(),
        assignedUsernames: modalAssignedUsers.slice()
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
