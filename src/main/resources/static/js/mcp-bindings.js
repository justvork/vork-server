/* mcp-bindings.js - Settings page for MCP bindings */
/* jshint esversion: 6 */

let mcpModal;
let mcpToolModal;
let bindings = [];
let bindingTools = [];
let toolsByBindingUuid = {};
const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

function mcpById(id) {
    return document.getElementById(id);
}

function buildCsrfHeaders() {
    const headers = {};
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    return headers;
}

function buildJsonHeaders() {
    return Object.assign({ 'Content-Type': 'application/json' }, buildCsrfHeaders());
}

async function parseResponseBodySafe(response) {
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
        return response.json();
    }

    const text = await response.text();
    return {
        status: response.ok ? 'ok' : 'error',
        message: text || ('HTTP ' + response.status)
    };
}

function buildHttpErrorMessage(response, data, fallback) {
    if (data && data.message) {
        return data.message;
    }
    if (response && (response.status === 401 || response.status === 403)) {
        return 'Request blocked by permissions or CSRF policy. Refresh and retry.';
    }
    return fallback;
}

function formatEpoch(value) {
    if (!value || Number(value) <= 0) return '-';
    const date = new Date(Number(value));
    if (Number.isNaN(date.getTime())) return '-';
    return date.toLocaleString();
}

function statusClass(status) {
    const key = String(status || '').toUpperCase();
    if (key === 'ACTIVE') return 'status-active';
    if (key === 'DRIFTED') return 'status-drifted';
    if (key === 'ERROR') return 'status-error';
    return 'status-inactive';
}

function showAlert(message, type) {
    const area = mcpById('mcp-alert-area');
    if (!area) return;
    renderAlert(area, message, type);
}

function showModalAlert(message, type) {
    const area = mcpById('mcp-modal-alert-area');
    if (!area) return;
    renderAlert(area, message, type);
}

function clearModalAlert() {
    const area = mcpById('mcp-modal-alert-area');
    if (area) area.innerHTML = '';
}

function renderAlert(area, message, type) {
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;

    area.innerHTML =
        '<div class="alert flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
        '<div>' + String(message || '') + '</div>' +
        '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" data-bs-dismiss="alert" aria-label="Dismiss alert">Close</button>' +
        '</div>';
}

async function loadBindings() {
    const res = await fetch('/api/mcp/bindings');
    if (!res.ok) {
        throw new Error('Failed to load MCP bindings.');
    }
    bindings = await res.json();
    await loadBindingToolSnapshots();
    renderBindings();
}

async function loadBindingToolSnapshots() {
    toolsByBindingUuid = {};
    if (!bindings.length) {
        return;
    }

    const results = await Promise.allSettled(bindings.map(async function (binding) {
        const res = await fetch('/api/mcp/bindings/' + encodeURIComponent(binding.uuid) + '/tools');
        if (!res.ok) {
            throw new Error('Failed to load tools for binding ' + binding.uuid);
        }
        const tools = await res.json();
        return {
            bindingUuid: binding.uuid,
            tools: Array.isArray(tools) ? tools : []
        };
    }));

    results.forEach(function (result) {
        if (result.status === 'fulfilled') {
            toolsByBindingUuid[result.value.bindingUuid] = result.value.tools;
        }
    });
}

function renderBindingToolPills(binding) {
    const tools = toolsByBindingUuid[binding.uuid] || [];
    if (!tools.length) {
        return '<span class="text-zinc-500">0</span>';
    }

    return tools.map(function (tool) {
        const toolId = tool.toolId || '';
        const toolName = tool.toolName || toolId || 'Unnamed tool';
        const enabledClass = tool.enabled ? 'enabled' : 'disabled';
        const statusLabel = tool.enabled ? 'enabled' : 'disabled';
        return ''
            + '<button type="button" class="mcp-tool-pill ' + enabledClass + '"'
            + ' data-action="edit-tool" data-uuid="' + escapeAttr(binding.uuid) + '" data-tool-id="' + escapeAttr(toolId) + '"'
            + ' title="Edit tool ' + escapeAttr(toolName) + '">'
            + '  <i class="fa-solid fa-screwdriver-wrench"></i>'
            + '  <span>' + escapeHtml(toolName) + '</span>'
            + '  <span class="mcp-tool-pill-state">' + statusLabel + '</span>'
            + '</button>';
    }).join('');
}

function renderBindings() {
    const body = mcpById('mcp-bindings-body');
    const empty = mcpById('mcp-empty-state');
    const tableWrap = mcpById('mcp-table-wrap');

    body.innerHTML = '';

    if (!bindings.length) {
        empty.classList.remove('hidden');
        tableWrap.classList.add('hidden');
        return;
    }

    empty.classList.add('hidden');
    tableWrap.classList.remove('hidden');

    bindings.forEach(function (binding) {
        const row = document.createElement('tr');
        row.innerHTML =
            '<td>' + escapeHtml(binding.name) + '</td>' +
            '<td><span class="text-zinc-400">' + escapeHtml(binding.baseUrl) + '</span></td>' +
            '<td>' + escapeHtml(binding.transportMode || '-') + '</td>' +
            '<td><span class="status-pill ' + statusClass(binding.status) + '">' + escapeHtml(binding.status || 'INACTIVE') + '</span></td>' +
            '<td><div class="mcp-tool-pill-list">' + renderBindingToolPills(binding) + '</div></td>' +
            '<td>' + Number(binding.resourceCount || 0) + '</td>' +
            '<td>' + Number(binding.promptCount || 0) + '</td>' +
            '<td class="text-zinc-400">' + formatEpoch(binding.lastDiscoveredAt) + '</td>' +
            '<td class="actions">' +
            '  <div class="mcp-bindings-row-actions">' +
            '    <button type="button" data-action="edit" data-uuid="' + escapeAttr(binding.uuid) + '" title="Edit"><i class="fa-solid fa-pen"></i></button>' +
            '    <button type="button" data-action="validate" data-uuid="' + escapeAttr(binding.uuid) + '" title="Validate"><i class="fa-solid fa-check"></i></button>' +
            '    <button type="button" data-action="sync" data-uuid="' + escapeAttr(binding.uuid) + '" title="Sync"><i class="fa-solid fa-rotate"></i></button>' +
            '    <button type="button" data-action="toggle" data-uuid="' + escapeAttr(binding.uuid) + '" title="Activate / Deactivate"><i class="fa-solid fa-power-off"></i></button>' +
            '    <button type="button" data-action="delete" data-uuid="' + escapeAttr(binding.uuid) + '" class="danger" title="Delete"><i class="fa-solid fa-trash"></i></button>' +
            '  </div>' +
            '</td>';
        body.appendChild(row);
    });
}

function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escapeAttr(str) {
    return escapeHtml(str);
}

function clearForm() {
    mcpById('mcp-binding-uuid').value = '';
    mcpById('mcp-binding-name').value = '';
    mcpById('mcp-binding-base-url').value = '';
    mcpById('mcp-binding-transport').value = 'STREAMABLE_HTTP';
    mcpById('mcp-binding-auth').value = '';
    mcpById('mcp-binding-group-id').value = '';
    mcpById('mcp-binding-artifact-id').value = '';
    mcpById('mcp-binding-version').value = 'SNAPSHOT';
    mcpById('mcp-binding-artifact-status').value = 'SNAPSHOT';
}

function openCreateModal() {
    clearForm();
    clearModalAlert();
    mcpById('mcpBindingModalLabel').textContent = 'Create MCP Binding';
    mcpModal.show();
}

async function openEditModal(uuid) {
    try {
        const res = await fetch('/api/mcp/bindings/' + encodeURIComponent(uuid));
        if (!res.ok) throw new Error('Binding not found.');
        const binding = await res.json();

        clearForm();
        clearModalAlert();
        mcpById('mcpBindingModalLabel').textContent = 'Edit MCP Binding';
        mcpById('mcp-binding-uuid').value = binding.uuid || '';
        mcpById('mcp-binding-name').value = binding.name || '';
        mcpById('mcp-binding-base-url').value = binding.baseUrl || '';
        mcpById('mcp-binding-transport').value = binding.transportMode || 'STREAMABLE_HTTP';
        mcpById('mcp-binding-group-id').value = binding.groupId || '';
        mcpById('mcp-binding-artifact-id').value = binding.artifactId || '';
        mcpById('mcp-binding-version').value = binding.version || 'SNAPSHOT';
        mcpById('mcp-binding-artifact-status').value = binding.artifactStatus || 'SNAPSHOT';

        mcpModal.show();
    } catch (e) {
        showAlert(e.message || 'Failed to load binding.', 'danger');
    }
}

function buildPayload() {
    return {
        name: mcpById('mcp-binding-name').value.trim(),
        baseUrl: mcpById('mcp-binding-base-url').value.trim(),
        transportMode: mcpById('mcp-binding-transport').value,
        authorization: mcpById('mcp-binding-auth').value.trim(),
        groupId: mcpById('mcp-binding-group-id').value.trim(),
        artifactId: mcpById('mcp-binding-artifact-id').value.trim(),
        version: mcpById('mcp-binding-version').value.trim(),
        artifactStatus: mcpById('mcp-binding-artifact-status').value
    };
}

function ensureRequired(payload) {
    if (!payload.name) return 'Name is required.';
    if (!payload.baseUrl) return 'Base URL is required.';
    if (!payload.transportMode) return 'Transport mode is required.';
    return '';
}

async function saveBinding() {
    const uuid = mcpById('mcp-binding-uuid').value.trim();
    const payload = buildPayload();
    const missing = ensureRequired(payload);
    if (missing) {
        showModalAlert(missing, 'warning');
        return;
    }

    try {
        const endpoint = uuid ? '/api/mcp/bindings/' + encodeURIComponent(uuid) : '/api/mcp/bindings';
        const method = uuid ? 'PUT' : 'POST';
        const res = await fetch(endpoint, {
            method: method,
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const data = await parseResponseBodySafe(res);
        if (!res.ok || data.status === 'error') {
            showModalAlert(buildHttpErrorMessage(res, data, 'Failed to save binding.'), 'danger');
            return;
        }

        mcpModal.hide();
        showAlert('Binding saved.', 'success');
        await loadBindings();
    } catch (_e) {
        showModalAlert('Network error while saving binding.', 'danger');
    }
}

async function callAction(uuid, action, successMessage) {
    const res = await fetch('/api/mcp/bindings/' + encodeURIComponent(uuid) + '/' + action, {
        method: 'POST',
        headers: buildCsrfHeaders()
    });
    const data = await parseResponseBodySafe(res);
    if (!res.ok || data.status === 'error') {
        throw new Error(buildHttpErrorMessage(res, data, 'Action failed: ' + action));
    }
    showAlert(successMessage, 'success');
}

async function deleteBinding(uuid) {
    const confirmed = window.confirm('Delete this binding and its discovered snapshots?');
    if (!confirmed) return;

    const res = await fetch('/api/mcp/bindings/' + encodeURIComponent(uuid), {
        method: 'DELETE',
        headers: buildCsrfHeaders()
    });
    const data = await parseResponseBodySafe(res);
    if (!res.ok || data.status === 'error') {
        throw new Error(buildHttpErrorMessage(res, data, 'Delete failed'));
    }
    showAlert('Binding deleted.', 'warning');
}

async function validateBinding(uuid) {
    const binding = bindings.find(function (item) { return item.uuid === uuid; });
    if (!binding) throw new Error('Binding not found.');

    const payload = {
        name: binding.name,
        baseUrl: binding.baseUrl,
        transportMode: binding.transportMode,
        authorization: '',
        groupId: binding.groupId,
        artifactId: binding.artifactId,
        version: binding.version,
        artifactStatus: binding.artifactStatus
    };

    const res = await fetch('/api/mcp/bindings/' + encodeURIComponent(uuid) + '/validate', {
        method: 'POST',
        headers: buildJsonHeaders(),
        body: JSON.stringify(payload)
    });
    const data = await parseResponseBodySafe(res);
    if (!res.ok || data.status === 'error') {
        throw new Error(buildHttpErrorMessage(res, data, 'Validation failed'));
    }
    showAlert('Validation succeeded.', 'success');
}

async function toggleActivation(uuid) {
    const binding = bindings.find(function (item) { return item.uuid === uuid; });
    if (!binding) throw new Error('Binding not found.');

    if (String(binding.status || '').toUpperCase() === 'ACTIVE') {
        await callAction(uuid, 'deactivate', 'Binding deactivated.');
    } else {
        await callAction(uuid, 'activate', 'Binding activated.');
    }
}

function toolById(toolId) {
    return bindingTools.find(function (item) { return item.toolId === toolId; }) || null;
}

function setToolEditorEnabled(enabled) {
    mcpById('mcp-tool-enabled').disabled = !enabled;
    mcpById('mcp-tool-requires-auth').disabled = !enabled;
    mcpById('mcp-tool-save-btn').disabled = !enabled;
}

function renderToolParams(tool) {
    const container = mcpById('mcp-tool-params');
    const emptyState = mcpById('mcp-tool-params-empty');
    container.innerHTML = '';

    const params = (tool && tool.parameterConfigs) ? tool.parameterConfigs : [];
    if (!params.length) {
        emptyState.classList.remove('hidden');
        return;
    }

    emptyState.classList.add('hidden');

    params.forEach(function (param) {
        const row = document.createElement('div');
        row.className = 'mcp-tool-param-row';
        row.setAttribute('data-param', param.name || '');

        row.innerHTML =
            '<div class="mb-1 text-sm font-semibold text-zinc-100">' + escapeHtml(param.name || '') + '</div>' +
            '<div class="grid grid-cols-1 gap-2 md:grid-cols-4">' +
            '  <div>' +
            '    <label class="mb-1 block text-xs font-medium text-zinc-400">Type</label>' +
            '    <input type="text" data-role="type" readonly value="' + escapeAttr(param.schemaType || 'string') + '">' +
            '  </div>' +
            '  <div>' +
            '    <label class="mb-1 block text-xs font-medium text-zinc-400">Required</label>' +
            '    <input type="text" data-role="required" readonly value="' + (param.requiredByServer ? 'true' : 'false') + '">' +
            '  </div>' +
            '  <div>' +
            '    <label class="mb-1 block text-xs font-medium text-zinc-400">Input Mode</label>' +
            '    <select data-role="inputMode">' +
            '      <option value="AI_REQUIRED">AI Required</option>' +
            '      <option value="AI_OPTIONAL">AI Optional</option>' +
            '      <option value="FIXED">Fixed</option>' +
            '      <option value="USER_ALWAYS_PROMPT">User Always Prompt</option>' +
            '      <option value="USER_PROMPT_IF_EMPTY">User Prompt If Empty</option>' +
            '      <option value="SECRET">Secret</option>' +
            '    </select>' +
            '  </div>' +
            '  <div>' +
            '    <label class="mb-1 block text-xs font-medium text-zinc-400">Default Value</label>' +
            '    <input type="text" data-role="default" value="' + escapeAttr(param.defaultValue || '') + '">' +
            '  </div>' +
            '</div>';

        const modeSelect = row.querySelector('select[data-role="inputMode"]');
        modeSelect.value = param.inputMode || 'AI_OPTIONAL';

        container.appendChild(row);
    });
}
function loadSelectedTool(toolId) {
    const tool = toolById(toolId);
    mcpById('mcp-tool-id').value = tool ? (tool.toolId || '') : '';
    mcpById('mcp-tool-name').textContent = tool ? (tool.toolName || tool.toolId || 'Unnamed tool') : '-';
    mcpById('mcp-tool-key').textContent = tool ? ('toolId=' + (tool.toolId || '-')) : 'toolId=-';

    const descWrap = mcpById('mcp-tool-description-wrap');
    const descArea = mcpById('mcp-tool-description');
    if (tool && tool.description && tool.description.trim()) {
        descArea.value = tool.description;
        descWrap.classList.remove('hidden');
    } else {
        descArea.value = '';
        descWrap.classList.add('hidden');
    }

    mcpById('mcp-tool-enabled').checked = !!(tool && tool.enabled);
    mcpById('mcp-tool-requires-auth').checked = !!(tool && tool.requiresAuthorization);
    renderToolParams(tool);
}

async function openToolModal(bindingUuid, initialToolId) {
    const basePath = '/api/mcp/bindings/' + encodeURIComponent(bindingUuid);
    const toolsRes = await fetch(basePath + '/tools');

    if (!toolsRes.ok) {
        throw new Error('Failed to load tools for binding.');
    }

    bindingTools = await toolsRes.json();

    mcpById('mcp-tool-binding-uuid').value = bindingUuid;

    if (!bindingTools.length) {
        mcpById('mcp-tool-id').value = '';
        mcpById('mcp-tool-name').textContent = '-';
        mcpById('mcp-tool-key').textContent = 'toolId=-';
        mcpById('mcp-tool-description').value = '';
        mcpById('mcp-tool-description-wrap').classList.add('hidden');
        mcpById('mcp-tool-params').innerHTML = '';
        mcpById('mcp-tool-params-empty').classList.remove('hidden');
        mcpById('mcp-tool-enabled').checked = false;
        mcpById('mcp-tool-requires-auth').checked = false;
        setToolEditorEnabled(false);
    } else {
        setToolEditorEnabled(true);
        const selectedToolId = initialToolId || bindingTools[0].toolId || '';
        loadSelectedTool(selectedToolId);
    }

    mcpToolModal.show();
}

function buildToolSavePayload() {
    const rows = Array.from(document.querySelectorAll('#mcp-tool-params .mcp-tool-param-row'));
    const parameterConfigs = rows.map(function (row) {
        const mode = row.querySelector('select[data-role="inputMode"]').value;
        const defaultValue = row.querySelector('input[data-role="default"]').value;
        return {
            name: row.getAttribute('data-param') || '',
            inputMode: mode,
            defaultValue: defaultValue,
            bindingSecretValue: null
        };
    });

    return {
        enabled: mcpById('mcp-tool-enabled').checked,
        requiresAuthorization: mcpById('mcp-tool-requires-auth').checked,
        parameterConfigs: parameterConfigs
    };
}

async function saveToolConfig() {
    const bindingUuid = mcpById('mcp-tool-binding-uuid').value;
    const toolId = mcpById('mcp-tool-id').value;
    if (!bindingUuid || !toolId) {
        showAlert('This binding has no tools to configure. Sync it first if expected.', 'warning');
        return;
    }

    const payload = buildToolSavePayload();
    const invalidFixed = payload.parameterConfigs.find(function (param) {
        return param.inputMode === 'FIXED' && !String(param.defaultValue || '').trim();
    });
    if (invalidFixed) {
        showAlert('FIXED mode requires a default value for parameter: ' + invalidFixed.name, 'warning');
        return;
    }

    const endpoint = '/api/mcp/bindings/' + encodeURIComponent(bindingUuid) + '/tools/' + encodeURIComponent(toolId);
    const res = await fetch(endpoint, {
        method: 'PUT',
        headers: buildJsonHeaders(),
        body: JSON.stringify(payload)
    });

    const data = await parseResponseBodySafe(res);
    if (!res.ok || data.status === 'error') {
        throw new Error(buildHttpErrorMessage(res, data, 'Failed to save tool configuration.'));
    }

    const idx = bindingTools.findIndex(function (item) { return item.toolId === toolId; });
    if (idx >= 0) {
        bindingTools[idx] = data;
    }

    showAlert('Tool configuration saved.', 'success');
    mcpToolModal.hide();
    await loadBindings();
}

async function handleRowAction(event) {
    const target = event.target.closest('button[data-action]');
    if (!target) return;

    const action = target.getAttribute('data-action');
    const uuid = target.getAttribute('data-uuid');
    if (!action || !uuid) return;

    try {
        if (action === 'edit') {
            await openEditModal(uuid);
            return;
        }
        if (action === 'tools') {
            await openToolModal(uuid);
            return;
        }
        if (action === 'edit-tool') {
            const toolId = target.getAttribute('data-tool-id') || '';
            await openToolModal(uuid, toolId);
            return;
        }
        if (action === 'validate') {
            await validateBinding(uuid);
        } else if (action === 'sync') {
            await callAction(uuid, 'sync', 'Binding synced.');
        } else if (action === 'toggle') {
            await toggleActivation(uuid);
        } else if (action === 'delete') {
            await deleteBinding(uuid);
        }

        await loadBindings();
    } catch (e) {
        showAlert(e.message || 'Action failed.', 'danger');
    }
}

document.addEventListener('DOMContentLoaded', async function () {
    const modalElement = mcpById('mcpBindingModal');
    const toolModalElement = mcpById('mcpToolModal');
    mcpModal = new VorkModal(modalElement);
    mcpToolModal = new VorkModal(toolModalElement);

    mcpById('mcp-create-btn').addEventListener('click', openCreateModal);
    mcpById('mcp-save-btn').addEventListener('click', saveBinding);
    mcpById('mcp-tool-save-btn').addEventListener('click', async function () {
        try {
            await saveToolConfig();
        } catch (e) {
            showAlert(e.message || 'Failed to save tool configuration.', 'danger');
        }
    });
    mcpById('mcp-bindings-body').addEventListener('click', handleRowAction);

    modalElement.addEventListener('hidden.bs.modal', clearForm);
    toolModalElement.addEventListener('hidden.bs.modal', function () {
        bindingTools = [];
        mcpById('mcp-tool-binding-uuid').value = '';
        mcpById('mcp-tool-id').value = '';
        mcpById('mcp-tool-name').textContent = '-';
        mcpById('mcp-tool-key').textContent = 'toolId=-';
        mcpById('mcp-tool-description').value = '';
        mcpById('mcp-tool-description-wrap').classList.add('hidden');
        mcpById('mcp-tool-params').innerHTML = '';
        mcpById('mcp-tool-params-empty').classList.remove('hidden');
        setToolEditorEnabled(true);
    });

    try {
        await loadBindings();
    } catch (e) {
        showAlert(e.message || 'Failed to load MCP bindings.', 'danger');
    }
});
