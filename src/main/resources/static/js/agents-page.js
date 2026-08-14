/* agents-page.js */

let agentModal;
let agentPublishModal;
let allAgents = [];
let allTools = [];
let allSkills = [];
let allReflections = [];
let allReflectionGroups = [];
let allReflectionBindingOptions = [];
let allUsers = [];
let allJobs = [];
let providerGroups = [];
let providerGroupByKey = {};
let modalTools = [];
let modalSkills = [];
let modalBindingUuids = [];
let modalAssignedUsers = [];
let modalAssignedJobs = [];
let autoArtifactIdEnabled = true;
let githubConnection;
const AGENT_IDENTITY_REGEX = /^[A-Za-z0-9]+$/;
const AGENT_IDENTITY_MIN_LEN = 3;
const AGENT_IDENTITY_MAX_LEN = 64;
const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

function isLegacySlashId(id) {
    return typeof id === 'string' && id.indexOf('/') >= 0;
}

function contributionPostHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    return headers;
}

document.addEventListener('DOMContentLoaded', function () {
    agentModal = new VorkModal(document.getElementById('agentModal'));
    agentPublishModal = new VorkModal(document.getElementById('agent-publish-modal'));
    githubConnection = window.VorkGitHubConnection
        ? window.VorkGitHubConnection.init({
            alertFn: showAlert
        })
        : null;
    loadData();

    const importBtn = document.getElementById('import-agents-btn');
    const importInput = document.getElementById('import-agents-input');
    if (importBtn && importInput) {
        importBtn.addEventListener('click', function () {
            importInput.click();
        });
        importInput.addEventListener('change', function () {
            importAgents(importInput);
        });
    }

    document.getElementById('agentModal').addEventListener('hidden.bs.modal', function () {
        document.getElementById('tool-search').value = '';
        document.getElementById('skill-search').value = '';
        document.getElementById('assigned-user-search').value = '';
        document.getElementById('assigned-job-search').value = '';
        document.getElementById('tool-dropdown').classList.add('hidden');
        document.getElementById('skill-dropdown').classList.add('hidden');
        document.getElementById('assigned-user-dropdown').classList.add('hidden');
        document.getElementById('assigned-job-dropdown').classList.add('hidden');
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
        if (!e.target.closest('#assigned-job-search') && !e.target.closest('#assigned-job-dropdown')) {
            document.getElementById('assigned-job-dropdown').classList.add('hidden');
        }
    });

    const nameInput = document.getElementById('agent-name');
    const groupInput = document.getElementById('agent-group-id');
    const artifactInput = document.getElementById('agent-artifact-id');
    if (nameInput && artifactInput) {
        nameInput.addEventListener('input', function () {
            const id = document.getElementById('agent-id').value.trim();
            if (id) return;
            if (!autoArtifactIdEnabled) return;
            artifactInput.value = generateArtifactIdFromName(nameInput.value);
            validateIdentityField(artifactInput, 'agent-artifact-id-error', 'Artifact ID');
        });
        artifactInput.addEventListener('input', function () {
            const id = document.getElementById('agent-id').value.trim();
            if (id) return;
            autoArtifactIdEnabled = false;
            validateIdentityField(artifactInput, 'agent-artifact-id-error', 'Artifact ID');
        });
    }
    if (groupInput) {
        groupInput.addEventListener('input', function () {
            validateIdentityField(groupInput, 'agent-group-id-error', 'Group ID');
        });
    }
    if (artifactInput) {
        artifactInput.addEventListener('blur', function () {
            validateIdentityField(artifactInput, 'agent-artifact-id-error', 'Artifact ID');
        });
    }
    if (groupInput) {
        groupInput.addEventListener('blur', function () {
            validateIdentityField(groupInput, 'agent-group-id-error', 'Group ID');
        });
    }
});

async function loadData() {
    try {
        const results = await Promise.all([
            fetch('/api/agents'),
            fetch('/api/management/tools'),
            fetch('/api/skills'),
            fetch('/api/reflections'),
            fetch('/api/chat/bindings'),
            fetch('/api/users'),
            fetch('/api/jobs'),
            fetch('/api/ai/providers')
        ]);
        const agentsRes = results[0];
        const toolsRes = results[1];
        const skillsRes = results[2];
        const reflectionsRes = results[3];
        const bindingsRes = results[4];
        const usersRes = results[5];
        const jobsRes = results[6];
        const providerRes = results[7];
        allAgents = agentsRes.ok ? await agentsRes.json() : [];
        allTools = toolsRes.ok ? await toolsRes.json() : [];
        allSkills = skillsRes.ok ? await skillsRes.json() : [];
        allReflections = reflectionsRes.ok ? await reflectionsRes.json() : [];
        allReflectionGroups = bindingsRes.ok ? await bindingsRes.json() : [];
        allUsers = usersRes.ok ? await usersRes.json() : [];
        allJobs = jobsRes.ok ? await jobsRes.json() : [];
        providerGroups = providerRes.ok ? await providerRes.json() : [];
        providerGroupByKey = (providerGroups || []).reduce(function (acc, group) {
            if (group && group.providerKey) {
                acc[group.providerKey.toUpperCase()] = group;
            }
            return acc;
        }, {});
        buildRecommendedModelLookupOptions('agent-recommended-model-lookup', '');
        allReflectionBindingOptions = buildReflectionBindingOptions();
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
    const bindingUuids = agent && agent.bindingUuids ? agent.bindingUuids : [];
    bindingUuids.forEach(function (bindingUuid) {
        const option = allReflectionBindingOptions.find(function (item) { return item.uuid === bindingUuid; });
        const label = option ? option.label : bindingUuid;
        if (label && !labels.includes(label)) labels.push(label);
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
    document.getElementById('agent-group-id').value = '';
    document.getElementById('agent-group-id').disabled = false;
    document.getElementById('agent-artifact-id').value = '';
    document.getElementById('agent-artifact-id').disabled = false;
    clearIdentityValidation('agent-group-id', 'agent-group-id-error');
    clearIdentityValidation('agent-artifact-id', 'agent-artifact-id-error');
    autoArtifactIdEnabled = true;
    document.getElementById('agent-prompt').value = '';
    document.getElementById('agent-recommended-model').value = '';
    document.getElementById('agent-name').disabled = false;
    document.getElementById('agent-prompt').disabled = false;
    document.getElementById('agent-recommended-model').disabled = false;
    document.getElementById('agent-recommended-model-lookup').disabled = false;
    const saveBtnCreate = document.getElementById('btn-save-agent');
    saveBtnCreate.disabled = false;
    saveBtnCreate.innerHTML = '<i class="fa-solid fa-save me-1"></i>Save Agent';
    buildRecommendedModelLookupOptions('agent-recommended-model-lookup', '');
    modalTools = [];
    modalSkills = [];
    modalBindingUuids = [];
    modalAssignedUsers = [];
    modalAssignedJobs = [];
    renderToolPills();
    renderSkillPills();
    renderReflectionBindingPills();
    renderAssignedUserPills();
    renderAssignedJobPills();
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
    document.getElementById('agent-group-id').value = agent.groupId || '';
    document.getElementById('agent-group-id').disabled = true;
    document.getElementById('agent-artifact-id').value = agent.artifactId || '';
    document.getElementById('agent-artifact-id').disabled = true;
    clearIdentityValidation('agent-group-id', 'agent-group-id-error');
    clearIdentityValidation('agent-artifact-id', 'agent-artifact-id-error');
    autoArtifactIdEnabled = false;
    document.getElementById('agent-prompt').value = agent.systemPrompt || '';
    document.getElementById('agent-recommended-model').value = agent.recommendedModel || '';
    const mutable = (agent.artifactStatus || 'SNAPSHOT') === 'SNAPSHOT';
    document.getElementById('agent-name').disabled = !mutable;
    document.getElementById('agent-prompt').disabled = !mutable;
    document.getElementById('agent-recommended-model').disabled = !mutable;
    document.getElementById('agent-recommended-model-lookup').disabled = !mutable;
    const saveBtn = document.getElementById('btn-save-agent');
    saveBtn.disabled = !mutable;
    saveBtn.innerHTML = mutable
        ? '<i class="fa-solid fa-save me-1"></i>Save Agent'
        : '<i class="fa-solid fa-lock me-1"></i>Immutable';
    buildRecommendedModelLookupOptions('agent-recommended-model-lookup', agent.recommendedModel || '');
    modalTools = agent.allowedTools ? agent.allowedTools.slice() : [];
    modalSkills = agent.skillUuids ? agent.skillUuids.slice() : [];
    modalBindingUuids = agent.bindingUuids ? agent.bindingUuids.slice() : [];
    modalAssignedUsers = agent.assignedUsernames ? agent.assignedUsernames.slice() : [];
    modalAssignedJobs = agent.jobUuids ? agent.jobUuids.slice() : [];
    renderToolPills();
    renderSkillPills();
    renderReflectionBindingPills();
    renderAssignedUserPills();
    renderAssignedJobPills();
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
    if (Array.isArray(allReflectionGroups)
            && allReflectionGroups.length > 0
            && Object.prototype.hasOwnProperty.call(allReflectionGroups[0], 'bindingId')) {
        return allReflectionGroups.map(function (binding) {
            const providerId = binding.providerId || 'binding';
            const displayName = binding.displayName || binding.bindingId || '';
            return {
                uuid: binding.bindingId,
                groupUuid: null,
                bindingName: displayName,
                groupName: providerId,
                providerId: providerId,
                label: displayName
            };
        });
    }

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
                providerId: 'reflection',
                label: binding.name || binding.uuid
            });
        });
    });
    return options;
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

function renderAssignedJobPills() {
    const container = document.getElementById('assigned-job-pill-container');
    if (!container) return;
    container.innerHTML = '';

    if (!modalAssignedJobs || modalAssignedJobs.length === 0) {
        container.innerHTML = '<span class="text-muted small">No delegated jobs assigned.</span>';
        return;
    }

    modalAssignedJobs.forEach(function (jobId) {
        const job = findJobById(jobId);
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-briefcase"></i>'
            + '<span>' + escapeHtml(jobLabel(jobId, job)) + '</span>'
            + '<span class="remove-tool" title="Remove delegated job">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeAssignedJob(jobId);
        });
        container.appendChild(pill);
    });
}

function removeAssignedJob(jobId) {
    modalAssignedJobs = (modalAssignedJobs || []).filter(function (id) { return id !== jobId; });
    renderAssignedJobPills();
    filterAssignedJobs();
}

function addAssignedJob(jobId) {
    if (!jobId) return;
    if (modalAssignedJobs.includes(jobId)) return;

    const candidate = findJobById(jobId);
    if (!candidate) {
        showAlert('Selected job is no longer available.', 'warning');
        return;
    }

    const candidateKey = jobGroupArtifactKey(candidate);
    if (candidateKey) {
        const conflictId = (modalAssignedJobs || []).find(function (selectedId) {
            const selectedJob = findJobById(selectedId);
            if (!selectedJob) return false;
            const selectedKey = jobGroupArtifactKey(selectedJob);
            return selectedKey === candidateKey;
        });
        if (conflictId) {
            const conflictJob = findJobById(conflictId);
            showAlert('Only one version can be assigned per job artifact. Already selected: ' + jobLabel(conflictId, conflictJob), 'warning');
            return;
        }
    }

    modalAssignedJobs.push(jobId);
    renderAssignedJobPills();
}

function filterAssignedJobs() {
    const query = document.getElementById('assigned-job-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('assigned-job-dropdown');
    const list = document.getElementById('assigned-job-options');
    if (!dropdown || !list) return;

    const selectedGroupArtifactKeys = new Set(
        (modalAssignedJobs || []).map(function (jobId) {
            const job = findJobById(jobId);
            return jobGroupArtifactKey(job);
        }).filter(function (key) { return !!key; })
    );

    const matches = (allJobs || []).filter(function (job) {
        if (!job || !job.id) return false;
        if ((modalAssignedJobs || []).includes(job.id)) return false;

        const gaKey = jobGroupArtifactKey(job);
        if (gaKey && selectedGroupArtifactKeys.has(gaKey)) return false;

        if (!query) return true;
        return jobSearchText(job).includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (job) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML = ''
            + '<div class="d-flex align-items-center gap-2">'
            + '  <i class="fa-solid fa-briefcase fa-xs text-secondary"></i>'
            + '  <span class="fw-semibold small">' + escapeHtml(job.name || job.id) + '</span>'
            + '  <span class="badge bg-dark border border-secondary text-secondary tool-meta-badge">'
            + escapeHtml(jobArtifactLabel(job))
            + '</span>'
            + '</div>'
            + '<div class="text-muted tool-description">' + escapeHtml(job.id) + '</div>';
        li.addEventListener('click', function () {
            addAssignedJob(job.id);
            document.getElementById('assigned-job-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
}

function findJobById(jobId) {
    return (allJobs || []).find(function (job) { return job && job.id === jobId; });
}

function jobGroupArtifactKey(job) {
    if (!job) return null;
    const groupId = (job.groupId || '').trim();
    const artifactId = (job.artifactId || '').trim();
    if (!groupId || !artifactId) return null;
    return groupId + ':' + artifactId;
}

function jobArtifactLabel(job) {
    if (!job) return 'unknown';
    const groupId = (job.groupId || '').trim();
    const artifactId = (job.artifactId || '').trim();
    const version = (job.version || '').trim();
    if (groupId && artifactId && version) return groupId + '/' + artifactId + '@' + version;
    if (groupId && artifactId) return groupId + '/' + artifactId;
    if (version) return 'version:' + version;
    return 'legacy';
}

function jobLabel(jobId, job) {
    if (!job) return jobId;
    return (job.name || job.id) + ' (' + jobArtifactLabel(job) + ')';
}

function jobSearchText(job) {
    return [job.name || '', job.id || '', job.groupId || '', job.artifactId || '', job.version || '']
        .join(' ')
        .toLowerCase();
}

async function saveAgent() {
    const id = document.getElementById('agent-id').value.trim();
    const name = document.getElementById('agent-name').value.trim();
    const groupId = document.getElementById('agent-group-id').value.trim();
    const artifactId = document.getElementById('agent-artifact-id').value.trim();
    if (!name) {
        showAlert('Name is required.', 'warning');
        return;
    }
    if (!id) {
        if (!groupId) {
            showAlert('Group ID is required.', 'warning');
            return;
        }
        if (!artifactId) {
            showAlert('Artifact ID is required.', 'warning');
            return;
        }
        const validGroup = validateIdentityField(document.getElementById('agent-group-id'), 'agent-group-id-error', 'Group ID');
        const validArtifact = validateIdentityField(document.getElementById('agent-artifact-id'), 'agent-artifact-id-error', 'Artifact ID');
        if (!validGroup || !validArtifact) {
            showAlert('Please fix Group ID and Artifact ID format errors.', 'warning');
            return;
        }
    }

    const body = {
        name: name,
        systemPrompt: document.getElementById('agent-prompt').value,
        recommendedModel: document.getElementById('agent-recommended-model').value.trim(),
        allowedTools: modalTools.slice(),
        skillUuids: modalSkills.slice(),
        bindingUuids: modalBindingUuids.slice(),
        assignedUsernames: modalAssignedUsers.slice(),
        jobUuids: modalAssignedJobs.slice(),
        groupId: groupId,
        artifactId: artifactId
    };

    const btn = document.getElementById('btn-save-agent');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving…';

    try {
        const url = id
            ? (isLegacySlashId(id)
                ? '/api/agents/update?id=' + encodeURIComponent(id)
                : '/api/agents/' + encodeURIComponent(id))
            : '/api/agents';
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
        const url = isLegacySlashId(id)
            ? '/api/agents/delete?id=' + encodeURIComponent(id)
            : '/api/agents/' + encodeURIComponent(id);
        const res = await fetch(url, { method: 'DELETE' });
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

async function recommendAgentVersion(id) {
    if (!id) {
        showAlert('Agent id is required for version recommendation.', 'warning');
        return;
    }
    try {
        const breakingChange = window.confirm('Does this release include breaking changes?\nOK = yes (major bump), Cancel = no (minor bump).');
        const recommendUrl = '/api/contributions/agents/' + encodeURIComponent(id)
            + '/recommend-version?breakingChange=' + encodeURIComponent(String(breakingChange));
        const res = await fetch(recommendUrl, {
            method: 'GET'
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.error) {
            showAlert(data.error || data.message || 'Failed to recommend version.', 'danger');
            return;
        }
        const rec = data.recommendation || {};
        const latest = rec.latestVersion ? ('Latest in staging: ' + rec.latestVersion + '. ') : 'No staging version found. ';
        showAlert(latest + 'Recommended next version: ' + (rec.recommendedVersion || 'n/a') + '.', 'success');
    } catch (_e) {
        showAlert('Network error getting version recommendation.', 'danger');
    }
}

async function checkAgentContributionDependencies(id) {
    if (!id) {
        showAlert('Agent id is required for dependency pre-check.', 'warning');
        return;
    }
    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndDisplay === 'function') {
        await window.VorkDependencyPrecheck.runAndDisplay('agents', id, 'Agent', showAlert);
        return;
    }
    showAlert('Dependency pre-check helper is not available on this page.', 'warning');
}

async function publishAgentContribution(id) {
    if (!id) {
        showAlert('Agent id is required for publish.', 'warning');
        return;
    }

    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndGate === 'function') {
        const ready = await window.VorkDependencyPrecheck.runAndGate('agents', id, 'Agent', showAlert);
        if (!ready) {
            return;
        }
    }

    document.getElementById('agent-publish-id').value = id;
    clearAlert('agent-publish-modal-alert');
    setAgentPublishLoading(true);
    agentPublishModal.show();

    try {
        const draftRes = await fetch('/api/contributions/agents/' + encodeURIComponent(id) + '/publish-draft', {
            method: 'POST',
            headers: contributionPostHeaders(),
            body: JSON.stringify({})
        });
        const draftData = await draftRes.json().catch(function () { return {}; });
        if (!draftRes.ok || draftData.error) {
            showAlert(draftData.error || draftData.message || 'Failed to generate publish draft.', 'danger', 'agent-publish-modal-alert');
            setAgentPublishLoading(false);
            return;
        }

        const draft = draftData.draft || {};
        document.getElementById('agent-publish-version').value = (draft.version || '').trim();
        document.getElementById('agent-publish-pr-title').value = (draft.prTitle || '').trim();
        document.getElementById('agent-publish-change-summary').value = (draft.changeSummary || '').trim();
        document.getElementById('agent-publish-commit-message').value = (draft.commitMessage || '').trim();
        document.getElementById('agent-publish-pr-body').value = (draft.prBody || '').trim();
        document.getElementById('agent-publish-release-notes').value = (draft.releaseNotes || '').trim();
        document.getElementById('agent-publish-reviewer-hints').value = (draft.reviewerHints || '').trim();
        document.getElementById('agent-publish-breaking-change').checked = !!draft.breakingChange;

        if (draft.latestVersion) {
            showAlert('Latest in staging: ' + draft.latestVersion + '. Draft generated and ready to edit.', 'success', 'agent-publish-modal-alert');
        }

        setAgentPublishLoading(false);
    } catch (_e) {
        showAlert('Network error during draft generation.', 'danger', 'agent-publish-modal-alert');
        setAgentPublishLoading(false);
    }
}

async function submitAgentPublishFromModal() {
    const id = document.getElementById('agent-publish-id').value;
    const version = document.getElementById('agent-publish-version').value.trim();
    const prTitle = document.getElementById('agent-publish-pr-title').value.trim();
    const changeSummary = document.getElementById('agent-publish-change-summary').value.trim();
    const commitMessage = document.getElementById('agent-publish-commit-message').value.trim();
    const prBody = document.getElementById('agent-publish-pr-body').value.trim();
    const releaseNotes = document.getElementById('agent-publish-release-notes').value.trim();
    const reviewerHints = document.getElementById('agent-publish-reviewer-hints').value.trim();
    const breakingChange = !!document.getElementById('agent-publish-breaking-change').checked;

    if (!id) {
        showAlert('Agent id is missing for publish.', 'danger', 'agent-publish-modal-alert');
        return;
    }
    if (!/^[0-9]+\.[0-9]+$/.test(version) || version.toUpperCase() === 'SNAPSHOT') {
        showAlert('Version must follow major.minor and cannot be SNAPSHOT.', 'danger', 'agent-publish-modal-alert');
        return;
    }
    if (!prTitle) {
        showAlert('PR title is required.', 'danger', 'agent-publish-modal-alert');
        return;
    }
    if (!changeSummary) {
        showAlert('Change summary is required.', 'danger', 'agent-publish-modal-alert');
        return;
    }

    setAgentPublishLoading(true, 'Creating PR...');
    clearAlert('agent-publish-modal-alert');

    try {
        const publishRes = await fetch('/api/contributions/agents/' + encodeURIComponent(id) + '/publish', {
            method: 'POST',
            headers: contributionPostHeaders(),
            body: JSON.stringify({
                version: version,
                commitMessage: commitMessage,
                prTitle: prTitle,
                prBody: prBody,
                changeSummary: changeSummary,
                breakingChange: breakingChange,
                releaseNotes: releaseNotes,
                reviewerHints: reviewerHints
            })
        });
        const publishData = await publishRes.json().catch(function () { return {}; });
        if (!publishRes.ok || publishData.error) {
            showAlert(publishData.error || publishData.message || 'Publish failed.', 'danger', 'agent-publish-modal-alert');
            setAgentPublishLoading(false);
            return;
        }

        const pullRequest = publishData.pullRequest || {};
        showAlert('Published. PR: ' + (pullRequest.url || 'created'), 'success');
        agentPublishModal.hide();
        setTimeout(function () { location.reload(); }, 900);
    } catch (_e) {
        showAlert('Network error during publish.', 'danger', 'agent-publish-modal-alert');
        setAgentPublishLoading(false);
    }
}

function setAgentPublishLoading(isLoading, loadingLabel) {
    const fields = [
        'agent-publish-version',
        'agent-publish-pr-title',
        'agent-publish-change-summary',
        'agent-publish-commit-message',
        'agent-publish-pr-body',
        'agent-publish-release-notes',
        'agent-publish-reviewer-hints',
        'agent-publish-breaking-change'
    ];
    fields.forEach(function (id) {
        const el = document.getElementById(id);
        if (!el) return;
        if (isLoading) {
            el.setAttribute('disabled', 'disabled');
        } else {
            el.removeAttribute('disabled');
        }
    });

    const submitBtn = document.getElementById('agent-publish-submit-btn');
    if (!submitBtn) return;
    if (isLoading) {
        submitBtn.setAttribute('disabled', 'disabled');
        submitBtn.dataset.label = submitBtn.textContent;
        submitBtn.textContent = loadingLabel || 'Preparing draft...';
    } else {
        submitBtn.removeAttribute('disabled');
        submitBtn.textContent = submitBtn.dataset.label || 'Submit PR';
    }
}

async function createAgentSnapshotContribution(id) {
    if (!id) {
        showAlert('Agent id is required for snapshot.', 'warning');
        return;
    }
    if (!window.confirm('Create a SNAPSHOT clone from this immutable agent?')) {
        return;
    }
    try {
        const res = await fetch('/api/contributions/agents/' + encodeURIComponent(id) + '/snapshot', {
            method: 'POST',
            headers: contributionPostHeaders(),
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.error) {
            showAlert(data.error || data.message || 'Failed to create snapshot.', 'danger');
            return;
        }
        showAlert('SNAPSHOT clone created.', 'success');
        setTimeout(function () { location.reload(); }, 700);
    } catch (_e) {
        showAlert('Network error creating snapshot.', 'danger');
    }
}

async function refreshAgentContributionStatus(id) {
    if (!id) {
        showAlert('Agent id is required to refresh status.', 'warning');
        return;
    }
    try {
        const res = await fetch('/api/contributions/promotions/reconcile', {
            method: 'POST',
            headers: contributionPostHeaders(),
            body: JSON.stringify({})
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.error) {
            showAlert(data.error || data.message || 'Failed to refresh contribution status.', 'danger');
            return;
        }
        const summary = data.summary || {};
        const promoted = (summary.agentsPromotedToStaged || 0);
        showAlert('Status refresh complete. Agents promoted to STAGED: ' + promoted + '.', 'success');
        setTimeout(function () { location.reload(); }, 700);
    } catch (_e) {
        showAlert('Network error refreshing contribution status.', 'danger');
    }
}

function exportAgentPackage(id) {
    if (!id) {
        showAlert('Agent id is missing for export.', 'warning');
        return;
    }
    window.location.href = isLegacySlashId(id)
        ? '/api/agents/export?id=' + encodeURIComponent(id)
        : '/api/agents/' + encodeURIComponent(id) + '/export';
}

function slugifyArtifactId(raw) {
    if (!raw) return '';
    const tokens = String(raw).match(/[A-Za-z0-9]+/g) || [];
    if (tokens.length === 0) return '';
    const first = tokens[0];
    const rest = tokens.slice(1).map(function (t) {
        return t.charAt(0).toUpperCase() + t.slice(1);
    });
    return [first].concat(rest).join('');
}

function generateArtifactIdFromName(name) {
    return slugifyArtifactId(name);
}

function validateIdentityField(inputEl, errorId, fieldLabel) {
    if (!inputEl || inputEl.disabled) return true;
    const value = (inputEl.value || '').trim();
    const errorEl = document.getElementById(errorId);
    if (!value) {
        inputEl.classList.remove('is-invalid');
        if (errorEl) {
            errorEl.textContent = '';
            errorEl.style.display = 'none';
        }
        return true;
    }
    if (!AGENT_IDENTITY_REGEX.test(value)) {
        inputEl.classList.add('is-invalid');
        if (errorEl) {
            errorEl.textContent = fieldLabel + ' must be alphanumeric only (letters and numbers), with no spaces.';
            errorEl.style.display = 'block';
        }
        return false;
    }
    if (value.length < AGENT_IDENTITY_MIN_LEN || value.length > AGENT_IDENTITY_MAX_LEN) {
        inputEl.classList.add('is-invalid');
        if (errorEl) {
            errorEl.textContent = fieldLabel + ' length must be between 3 and 64 characters.';
            errorEl.style.display = 'block';
        }
        return false;
    }
    inputEl.classList.remove('is-invalid');
    if (errorEl) {
        errorEl.textContent = '';
        errorEl.style.display = 'none';
    }
    return true;
}

function clearIdentityValidation(inputId, errorId) {
    const inputEl = document.getElementById(inputId);
    const errorEl = document.getElementById(errorId);
    if (inputEl) {
        inputEl.classList.remove('is-invalid');
    }
    if (errorEl) {
        errorEl.textContent = '';
        errorEl.style.display = 'none';
    }
}

async function importAgents(input) {
    const file = input && input.files && input.files[0];
    if (!file) return;

    try {
        const raw = await file.text();
        const payload = JSON.parse(raw);
        const res = await fetch('/api/agents/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.status === 'error') {
            showAlert(data.message || 'Agent import failed.', 'danger');
            return;
        }
        showAlert(data.status === 'updated' ? 'Agent updated from import.' : 'Agent imported successfully.', 'success');
        setTimeout(function () { location.reload(); }, 700);
    } catch (_e) {
        showAlert('Invalid JSON file or network error during agent import.', 'danger');
    } finally {
        if (input) input.value = '';
    }
}

function clearAlert(targetId) {
    const area = document.getElementById(targetId || 'alert-area');
    if (area) area.innerHTML = '';
}

function showAlert(msg, type, targetId) {
    const area = document.getElementById(targetId || 'alert-area');
    if (!area) return;
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
