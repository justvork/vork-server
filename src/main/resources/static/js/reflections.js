/* reflections.js - Reflection management */
/* jshint esversion: 6 */

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';
const isReadOnly = document.body.getAttribute('data-reflections-read-only') === 'true';

let groups = [];
let reflections = [];
let oauthTemplates = [];
let modalParameters = [];
let modalHeaders = [];
let modalQueryParameters = [];
let modalGroupBindingParameters = [];
let modalGroupBindingSecrets = [];
let modalBindingParameterValues = {};
let modalBindingSecretValues = {};
let oauthConnectDefaults = null;

const alertArea = document.getElementById('alert-area');

function init() {
    bindEvents();
    renderOAuthBindingCallbackAlert();
    loadAll();
}

document.addEventListener('DOMContentLoaded', init);

function bindEvents() {
    const newGroupBtn = document.getElementById('new-group-btn');
    const importReflectionsBtn = document.getElementById('import-reflections-btn');
    const importReflectionsInput = document.getElementById('import-reflections-input');
    if (newGroupBtn) {
        newGroupBtn.addEventListener('click', openCreateGroupModal);
    }
    if (importReflectionsBtn && importReflectionsInput) {
        importReflectionsBtn.addEventListener('click', function () {
            importReflectionsInput.click();
        });
        importReflectionsInput.addEventListener('change', function () {
            importReflections(importReflectionsInput);
        });
    }

    document.getElementById('group-modal-close').addEventListener('click', closeGroupModal);
    document.getElementById('group-modal-cancel').addEventListener('click', closeGroupModal);
    document.getElementById('group-modal-save').addEventListener('click', saveGroup);
    document.getElementById('group-type').addEventListener('change', syncGroupAuthVisibility);
    document.getElementById('group-auth-mode').addEventListener('change', syncGroupAuthVisibility);
    document.getElementById('add-group-binding-param-btn').addEventListener('click', function () {
        modalGroupBindingParameters.push({ name: '', type: 'string', description: '', defaultValue: '' });
        renderGroupBindingParameters();
    });
    document.getElementById('add-group-binding-secret-btn').addEventListener('click', function () {
        modalGroupBindingSecrets.push({ name: '', description: '' });
        renderGroupBindingSecrets();
    });

    document.getElementById('reflection-modal-close').addEventListener('click', closeReflectionModal);
    document.getElementById('reflection-modal-cancel').addEventListener('click', closeReflectionModal);
    document.getElementById('reflection-modal-save').addEventListener('click', saveReflection);
    document.getElementById('reflection-method').addEventListener('change', function () {
        updateRequestTemplateVisibility();
    });
    document.getElementById('reflection-response-content-type').addEventListener('change', function () {
        updateOutputSchemaVisibility();
    });
    document.getElementById('add-header-btn').addEventListener('click', function () {
        modalHeaders.push({ name: '', value: '' });
        renderKeyValueRows('headers-list', modalHeaders, 'header');
    });
    document.getElementById('add-query-param-btn').addEventListener('click', function () {
        modalQueryParameters.push({ name: '', value: '' });
        renderKeyValueRows('query-params-list', modalQueryParameters, 'query');
    });
    document.getElementById('add-param-btn').addEventListener('click', function () {
        modalParameters.push({ name: '', type: 'string', description: '', required: false });
        renderParameters();
    });

    document.getElementById('binding-modal-close').addEventListener('click', closeBindingModal);
    document.getElementById('binding-modal-cancel').addEventListener('click', closeBindingModal);
    document.getElementById('binding-modal-save').addEventListener('click', saveBinding);

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeGroupModal();
            closeReflectionModal();
            closeBindingModal();
        }
    });
}

async function loadAll() {
    await Promise.all([loadGroups(), loadReflections(), loadOAuthTemplates()]);
    renderGroups();
}

async function loadOAuthTemplates() {
    try {
        const response = await fetch('/api/oauth-templates');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        oauthTemplates = await response.json();
    } catch (_error) {
        oauthTemplates = [];
    }
    populateGroupOAuthTemplateSelect();
}

async function loadGroups() {
    try {
        const response = await fetch('/api/reflection-groups');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        groups = await response.json();
        populateGroupSelect();
    } catch (error) {
        showAlert('Failed to load reflection groups: ' + (error.message || 'unknown error'), 'danger');
    }
}

async function loadReflections() {
    try {
        const response = await fetch('/api/reflections');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        reflections = await response.json();
    } catch (error) {
        showAlert('Failed to load reflections: ' + (error.message || 'unknown error'), 'danger');
    }
}

function renderGroups() {
    const body = document.getElementById('groups-body');
    const empty = document.getElementById('groups-empty');
    const wrap = document.getElementById('groups-table-wrap');

    body.innerHTML = '';
    if (!groups || groups.length === 0) {
        empty.classList.remove('hidden');
        wrap.classList.add('hidden');
        return;
    }

    empty.classList.add('hidden');
    wrap.classList.remove('hidden');

    groups.forEach(function (entry) {
        const group = entry.group || entry;
        const groupReflections = reflectionsForGroupUuid(group.uuid);
        const row = document.createElement('tr');
        row.className = 'border-b border-zinc-800/80 last:border-0 align-top';
        row.innerHTML = ''
            + '<td class="px-3 py-3">'
            + '  <div class="font-semibold text-zinc-100">' + escapeHtml(group.name || '') + '</div>'
            + '  <div class="mt-1 text-xs text-zinc-500">' + escapeHtml(group.description || 'No description') + '</div>'
            + '</td>'
            + '<td class="px-3 py-3"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-2 py-0.5 text-xs text-zinc-300">' + escapeHtml(group.type || 'REST') + '</span></td>'
            + '<td class="px-3 py-3">'
            + renderReflectionPillsHtml(groupReflections, group.uuid)
            + '</td>'
            + '<td class="px-3 py-3">'
            + renderBindingPillsHtml(entry.bindings || [], group.uuid)
            + '</td>'
            + '<td class="px-3 py-3 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + (isReadOnly ? '' : ''
                + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200" data-action="edit-group" data-id="' + escapeHtml(group.uuid) + '" title="Edit group"><i class="fa-solid fa-pen"></i></button>'
                + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300" data-action="export-group" data-id="' + escapeHtml(group.uuid) + '" title="Export group"><i class="fa-solid fa-file-export"></i></button>'
                + '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300" data-action="delete-group" data-id="' + escapeHtml(group.uuid) + '" title="Delete group"><i class="fa-solid fa-trash"></i></button>')
            + '  </div>'
            + '</td>';
        body.appendChild(row);
    });

    bindDynamicTableEvents(body);
}

function reflectionsForGroupUuid(groupUuid) {
    return (reflections || []).filter(function (reflection) {
        return reflection.groupUuid === groupUuid;
    });
}

function renderReflectionPillsHtml(groupReflections, groupUuid) {
    if (!groupReflections || groupReflections.length === 0) {
        if (isReadOnly) {
            return '<span class="text-xs text-zinc-500">No reflections</span>';
        }
        return ''
            + '<button class="rounded-full border border-dashed border-zinc-600 px-3 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" '
            + 'data-action="add-reflection" data-group-id="' + escapeHtml(groupUuid) + '">'
            + '<i class="fa-solid fa-plus mr-1"></i>Add first reflection</button>';
    }

    const pills = groupReflections
        .sort(function (a, b) {
            return String(a.id || '').localeCompare(String(b.id || ''), undefined, { sensitivity: 'base' });
        })
        .map(function (reflection) {
            const method = escapeHtml(reflection.method || 'GET');
            const id = escapeHtml(reflection.id || '');
            const name = escapeHtml(reflection.name || '');
            const title = method + ' ' + id + (name ? ' - ' + name : '');
            return ''
                + '<span class="mr-1 mb-1 inline-flex items-center gap-1 rounded-full border border-zinc-700 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200">'
                + '  <button class="inline-flex items-center gap-1 transition-colors hover:text-cyan-300" '
                + 'data-action="edit-reflection" data-id="' + escapeHtml(reflection.uuid) + '" title="Edit ' + title + '">'
                + '    <span class="rounded bg-zinc-800 px-1 py-0.5 text-[10px] font-semibold text-zinc-300">' + method + '</span>'
                + '    <span class="font-mono">' + id + '</span>'
                + '  </button>'
                + (isReadOnly ? '' : '  <button class="ml-1 px-1 py-0.5 text-[10px] text-zinc-300 transition-colors hover:text-cyan-300" data-action="copy-reflection" data-id="' + escapeHtml(reflection.uuid) + '" title="Copy ' + title + '"><i class="fa-solid fa-copy"></i></button>')
                + (isReadOnly ? '' : '  <button class="px-1 py-0.5 text-[10px] text-rose-300 transition-colors hover:text-rose-200" data-action="delete-reflection" data-id="' + escapeHtml(reflection.uuid) + '" title="Delete ' + title + '"><i class="fa-solid fa-trash"></i></button>')
                + '</span>';
        }).join('');

    const addButton = isReadOnly
        ? ''
        : '<button class="mb-1 inline-flex items-center rounded-full border border-dashed border-zinc-600 px-2.5 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" data-action="add-reflection" data-group-id="' + escapeHtml(groupUuid) + '" title="Add reflection to group"><i class="fa-solid fa-plus mr-1"></i>Add</button>';

    return pills + addButton;
}

function renderBindingPillsHtml(bindings, groupUuid) {
    const sorted = (bindings || []).slice().sort(function (a, b) {
        return String(a.name || '').localeCompare(String(b.name || ''), undefined, { sensitivity: 'base' });
    });

    if (sorted.length === 0) {
        if (isReadOnly) {
            return '<span class="text-xs text-zinc-500">No bindings</span>';
        }
        return ''
            + '<button class="rounded-full border border-dashed border-zinc-600 px-3 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" '
            + 'data-action="add-binding" data-group-id="' + escapeHtml(groupUuid) + '">'
            + '<i class="fa-solid fa-plus mr-1"></i>Add default binding</button>';
    }

    const pills = sorted.map(function (binding) {
        const bindingName = escapeHtml(binding.name || 'default');
        const baseUrl = escapeHtml(binding.baseUrl || '');
        const pillTitle = baseUrl ? ('Binding: ' + bindingName + ' (' + baseUrl + ')') : ('Binding: ' + bindingName);
        return ''
            + '<span class="mr-1 mb-1 inline-flex items-center gap-1 rounded-full border border-zinc-700 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200">'
            + '  <button class="inline-flex items-center gap-1 transition-colors hover:text-cyan-300" data-action="edit-binding" data-group-id="' + escapeHtml(groupUuid) + '" data-name="' + bindingName + '" title="' + escapeHtml(pillTitle) + '">'
            + '    <span class="font-mono">' + bindingName + '</span>'
            + '  </button>'
            + (isReadOnly ? '' : '  <button class="ml-1 px-1 py-0.5 text-[10px] text-zinc-300 transition-colors hover:text-cyan-300" data-action="copy-binding" data-group-id="' + escapeHtml(groupUuid) + '" data-name="' + bindingName + '" title="Copy binding"><i class="fa-solid fa-copy"></i></button>')
            + (isReadOnly ? '' : '  <button class="px-1 py-0.5 text-[10px] text-rose-300 transition-colors hover:text-rose-200" data-action="delete-binding" data-group-id="' + escapeHtml(groupUuid) + '" data-name="' + bindingName + '" title="Delete binding"><i class="fa-solid fa-trash"></i></button>')
            + '</span>';
    }).join('');

    const addButton = isReadOnly
        ? ''
        : '<button class="mb-1 inline-flex items-center rounded-full border border-dashed border-zinc-600 px-2.5 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" data-action="add-binding" data-group-id="' + escapeHtml(groupUuid) + '" title="Add binding"><i class="fa-solid fa-plus mr-1"></i>Add</button>';

    return pills + addButton;
}

function bindDynamicTableEvents(root) {
    if (!root || isReadOnly) {
        return;
    }

    root.querySelectorAll('button[data-action="edit-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openEditGroupModal(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="delete-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            deleteGroup(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="export-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            exportGroup(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="add-reflection"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCreateReflectionModal(button.getAttribute('data-group-id'));
        });
    });
    root.querySelectorAll('button[data-action="edit-reflection"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openEditReflectionModal(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="copy-reflection"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCopyReflectionModal(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="delete-reflection"]').forEach(function (button) {
        button.addEventListener('click', function () {
            deleteReflection(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="add-binding"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCreateBindingModal(button.getAttribute('data-group-id'));
        });
    });
    root.querySelectorAll('button[data-action="edit-binding"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openEditBindingModal(button.getAttribute('data-group-id'), button.getAttribute('data-name'));
        });
    });
    root.querySelectorAll('button[data-action="copy-binding"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCopyBindingModal(button.getAttribute('data-group-id'), button.getAttribute('data-name'));
        });
    });
    root.querySelectorAll('button[data-action="delete-binding"]').forEach(function (button) {
        button.addEventListener('click', function () {
            deleteBinding(button.getAttribute('data-group-id'), button.getAttribute('data-name'));
        });
    });
}

function openCreateGroupModal() {
    document.getElementById('group-modal-title').textContent = 'New Reflection Group';
    document.getElementById('group-id').value = '';
    document.getElementById('group-name').value = '';
    document.getElementById('group-description').value = '';
    document.getElementById('group-type').value = 'REST';
    document.getElementById('group-base-url').value = '';
    document.getElementById('group-url-override-enabled').checked = true;
    document.getElementById('group-auth-mode').value = 'NONE';
    populateGroupOAuthTemplateSelect('');
    modalGroupBindingParameters = [];
    modalGroupBindingSecrets = [];
    renderGroupBindingParameters();
    renderGroupBindingSecrets();
    syncGroupAuthVisibility();
    clearGroupModalAlert();
    showModal('group-modal');
}

function openEditGroupModal(uuid) {
    const entry = findGroupEntry(uuid);
    if (!entry) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    const group = entry.group || entry;
    document.getElementById('group-modal-title').textContent = 'Edit Reflection Group';
    document.getElementById('group-id').value = group.uuid;
    document.getElementById('group-name').value = group.name || '';
    document.getElementById('group-description').value = group.description || '';
    document.getElementById('group-type').value = group.type || 'REST';
    document.getElementById('group-base-url').value = group.baseUrl || '';
    document.getElementById('group-url-override-enabled').checked = group.urlOverrideEnabled !== false;
    document.getElementById('group-auth-mode').value = group.authenticationMode || 'NONE';
    populateGroupOAuthTemplateSelect(group.oauthTemplateId || '');
    modalGroupBindingParameters = (group.bindingParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            defaultValue: parameter.defaultValue || ''
        };
    });
    modalGroupBindingSecrets = (group.bindingSecrets || []).map(function (secret) {
        return {
            name: secret.name || '',
            description: secret.description || ''
        };
    });
    renderGroupBindingParameters();
    renderGroupBindingSecrets();
    syncGroupAuthVisibility();
    clearGroupModalAlert();
    showModal('group-modal');
}

async function saveGroup() {
    const groupType = document.getElementById('group-type').value;
    const authenticationMode = document.getElementById('group-auth-mode').value;
    const oauthTemplateId = document.getElementById('group-oauth-template').value;
    const groupId = document.getElementById('group-id').value.trim();
    const payload = {
        name: document.getElementById('group-name').value.trim(),
        description: document.getElementById('group-description').value.trim(),
        type: groupType,
        baseUrl: document.getElementById('group-base-url').value.trim(),
        urlOverrideEnabled: document.getElementById('group-url-override-enabled').checked,
        bindingParameters: sanitizeBindingParameterSchema(modalGroupBindingParameters),
        bindingSecrets: sanitizeBindingSecretSchema(modalGroupBindingSecrets),
        authenticationMode: authenticationMode,
        oauthTemplateId: authenticationMode === 'OAUTH' ? oauthTemplateId : ''
    };

    if (!payload.name) {
        showGroupModalAlert('Group name is required.', 'warning');
        return;
    }
    if (authenticationMode === 'OAUTH' && groupType !== 'REST') {
        showGroupModalAlert('OAuth authentication is supported only for REST groups.', 'warning');
        return;
    }
    if (authenticationMode === 'OAUTH' && !oauthTemplateId) {
        showGroupModalAlert('Select an OAuth template for OAUTH authentication mode.', 'warning');
        return;
    }

    try {
        const response = await fetch(groupId ? '/api/reflection-groups/' + encodeURIComponent(groupId) : '/api/reflection-groups', {
            method: groupId ? 'PUT' : 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showGroupModalAlert(result.error || 'Failed to save group.', 'danger');
            return;
        }

        closeGroupModal();
        showAlert(groupId ? 'Group updated.' : 'Group created.', 'success');
        await loadAll();
    } catch (_error) {
        showGroupModalAlert('Network error while saving group.', 'danger');
    }
}

function populateGroupOAuthTemplateSelect(selectedTemplateId) {
    const select = document.getElementById('group-oauth-template');
    if (!select) {
        return;
    }

    const previousValue = selectedTemplateId == null ? '' : String(selectedTemplateId);
    select.innerHTML = '';

    const blank = document.createElement('option');
    blank.value = '';
    blank.textContent = oauthTemplates.length === 0 ? 'No templates available' : 'Select OAuth template';
    select.appendChild(blank);

    (oauthTemplates || []).forEach(function (template) {
        const option = document.createElement('option');
        option.value = template.id || '';
        const clientName = template.clientName ? (' (' + template.clientName + ')') : '';
        option.textContent = (template.name || 'Unnamed template') + clientName;
        select.appendChild(option);
    });

    select.value = previousValue;
}

function syncGroupAuthVisibility() {
    const typeValue = document.getElementById('group-type').value;
    const modeSelect = document.getElementById('group-auth-mode');
    const oauthWrap = document.getElementById('group-oauth-template-wrap');

    if (!modeSelect || !oauthWrap) {
        return;
    }

    if (typeValue !== 'REST' && modeSelect.value === 'OAUTH') {
        modeSelect.value = 'NONE';
    }

    const showOAuth = typeValue === 'REST' && modeSelect.value === 'OAUTH';
    oauthWrap.classList.toggle('hidden', !showOAuth);
}

function renderGroupBindingParameters() {
    const container = document.getElementById('group-binding-params-list');
    if (!container) {
        return;
    }
    container.innerHTML = '';

    if (!modalGroupBindingParameters || modalGroupBindingParameters.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No binding parameters defined.</p>';
        return;
    }

    const header = document.createElement('div');
    header.className = 'grid grid-cols-12 gap-2 mb-1 text-xs text-zinc-500';
    header.innerHTML = ''
        + '<div class="col-span-3">Name</div>'
        + '<div class="col-span-2">Type</div>'
        + '<div class="col-span-3">Description</div>'
        + '<div class="col-span-3">Default</div>'
        + '<div class="col-span-1"></div>';
    container.appendChild(header);

    modalGroupBindingParameters.forEach(function (parameter, index) {
        const row = document.createElement('div');
        row.className = 'grid grid-cols-12 gap-2 mb-2';
        row.innerHTML = ''
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-param-name" data-index="' + index + '" value="' + escapeHtml(parameter.name || '') + '" placeholder="tenantId">'
            + '<select class="col-span-2 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-param-type" data-index="' + index + '">'
            + '  <option value="string">string</option>'
            + '  <option value="int">int</option>'
            + '  <option value="double">double</option>'
            + '  <option value="boolean">boolean</option>'
            + '  <option value="hidden">hidden</option>'
            + '</select>'
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-param-description" data-index="' + index + '" value="' + escapeHtml(parameter.description || '') + '" placeholder="Tenant identifier">'
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-param-default" data-index="' + index + '" value="' + escapeHtml(parameter.defaultValue || '') + '" placeholder="optional default">'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 remove-group-binding-param" data-index="' + index + '" title="Remove"><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(row);
        const typeSelect = row.querySelector('.group-binding-param-type');
        if (typeSelect) {
            typeSelect.value = parameter.type || 'string';
        }
    });

    container.querySelectorAll('.group-binding-param-name').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingParameters[index].name = input.value;
        });
    });
    container.querySelectorAll('.group-binding-param-type').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingParameters[index].type = input.value;
        });
    });
    container.querySelectorAll('.group-binding-param-description').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingParameters[index].description = input.value;
        });
    });
    container.querySelectorAll('.group-binding-param-default').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingParameters[index].defaultValue = input.value;
        });
    });
    container.querySelectorAll('.remove-group-binding-param').forEach(function (button) {
        button.addEventListener('click', function () {
            const index = Number(button.getAttribute('data-index'));
            modalGroupBindingParameters.splice(index, 1);
            renderGroupBindingParameters();
        });
    });
}

function renderGroupBindingSecrets() {
    const container = document.getElementById('group-binding-secrets-list');
    if (!container) {
        return;
    }
    container.innerHTML = '';

    if (!modalGroupBindingSecrets || modalGroupBindingSecrets.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No binding secrets defined.</p>';
        return;
    }

    const header = document.createElement('div');
    header.className = 'grid grid-cols-12 gap-2 mb-1 text-xs text-zinc-500';
    header.innerHTML = ''
        + '<div class="col-span-4">Name</div>'
        + '<div class="col-span-7">Description</div>'
        + '<div class="col-span-1"></div>';
    container.appendChild(header);

    modalGroupBindingSecrets.forEach(function (secret, index) {
        const row = document.createElement('div');
        row.className = 'grid grid-cols-12 gap-2 mb-2';
        row.innerHTML = ''
            + '<input class="col-span-4 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-secret-name" data-index="' + index + '" value="' + escapeHtml(secret.name || '') + '" placeholder="API_KEY">'
            + '<input class="col-span-7 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 group-binding-secret-description" data-index="' + index + '" value="' + escapeHtml(secret.description || '') + '" placeholder="API key used by this connection">'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 remove-group-binding-secret" data-index="' + index + '" title="Remove"><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(row);
    });

    container.querySelectorAll('.group-binding-secret-name').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingSecrets[index].name = input.value;
        });
    });
    container.querySelectorAll('.group-binding-secret-description').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalGroupBindingSecrets[index].description = input.value;
        });
    });
    container.querySelectorAll('.remove-group-binding-secret').forEach(function (button) {
        button.addEventListener('click', function () {
            const index = Number(button.getAttribute('data-index'));
            modalGroupBindingSecrets.splice(index, 1);
            renderGroupBindingSecrets();
        });
    });
}

function sanitizeBindingParameterSchema(parameters) {
    return (parameters || [])
        .filter(function (parameter) {
            return parameter.name && parameter.name.trim();
        })
        .map(function (parameter) {
            return {
                name: parameter.name.trim(),
                type: (parameter.type || 'string').trim(),
                description: (parameter.description || '').trim(),
                defaultValue: (parameter.defaultValue || '').trim()
            };
        });
}

function sanitizeBindingSecretSchema(secrets) {
    return (secrets || [])
        .filter(function (secret) {
            return secret.name && secret.name.trim();
        })
        .map(function (secret) {
            return {
                name: secret.name.trim(),
                description: (secret.description || '').trim()
            };
        });
}

function findGroupEntry(groupUuid) {
    return (groups || []).find(function (item) {
        const group = item.group || item;
        return group.uuid === groupUuid;
    });
}

function openCreateBindingModal(groupUuid) {
    const entry = findGroupEntry(groupUuid);
    if (!entry) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    const group = entry.group || entry;

    document.getElementById('binding-modal-title').textContent = 'New Binding';
    document.getElementById('binding-group-uuid').value = groupUuid;
    document.getElementById('binding-original-name').value = '';
    document.getElementById('binding-copy-source-name').value = '';
    document.getElementById('binding-name').value = (entry.bindings || []).length === 0 ? 'default' : '';
    document.getElementById('binding-base-url').value = '';
    document.getElementById('binding-oauth-client-id').value = '';
    document.getElementById('binding-oauth-client-secret').value = '';
    document.getElementById('binding-oauth-redirect-uri').value = '';
    modalBindingParameterValues = {};
    modalBindingSecretValues = {};
    syncBindingModalSections(group);
    loadBindingOauthDefaultsIfNeeded(group);
    renderBindingParameterFields(group.bindingParameters || []);
    renderBindingSecretFields(group.bindingSecrets || []);
    clearBindingModalAlert();
    showModal('binding-modal');
}

function openEditBindingModal(groupUuid, bindingName) {
    const entry = findGroupEntry(groupUuid);
    if (!entry) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    const group = entry.group || entry;
    const binding = (entry.bindings || []).find(function (item) {
        return String(item.name || '').toLowerCase() === String(bindingName || '').toLowerCase();
    });
    if (!binding) {
        showAlert('Binding not found. Reload and try again.', 'warning');
        return;
    }

    document.getElementById('binding-modal-title').textContent = 'Edit Binding';
    document.getElementById('binding-group-uuid').value = groupUuid;
    document.getElementById('binding-original-name').value = binding.name || '';
    document.getElementById('binding-copy-source-name').value = '';
    document.getElementById('binding-name').value = binding.name || '';
    document.getElementById('binding-base-url').value = binding.baseUrl || '';
    document.getElementById('binding-oauth-client-id').value = '';
    document.getElementById('binding-oauth-client-secret').value = '';
    document.getElementById('binding-oauth-redirect-uri').value = '';
    modalBindingParameterValues = Object.assign({}, binding.parameterValues || {});
    modalBindingSecretValues = {};
    syncBindingModalSections(group);
    loadBindingOauthDefaultsIfNeeded(group);
    renderBindingParameterFields(group.bindingParameters || []);
    renderBindingSecretFields(group.bindingSecrets || []);
    clearBindingModalAlert();
    showModal('binding-modal');
}

function openCopyBindingModal(groupUuid, bindingName) {
    const entry = findGroupEntry(groupUuid);
    if (!entry) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    const group = entry.group || entry;
    const binding = (entry.bindings || []).find(function (item) {
        return String(item.name || '').toLowerCase() === String(bindingName || '').toLowerCase();
    });
    if (!binding) {
        showAlert('Binding not found. Reload and try again.', 'warning');
        return;
    }

    document.getElementById('binding-modal-title').textContent = 'Copy Binding';
    document.getElementById('binding-group-uuid').value = groupUuid;
    document.getElementById('binding-original-name').value = '';
    document.getElementById('binding-copy-source-name').value = binding.name || '';
    document.getElementById('binding-name').value = '';
    document.getElementById('binding-base-url').value = binding.baseUrl || '';
    document.getElementById('binding-oauth-client-id').value = '';
    document.getElementById('binding-oauth-client-secret').value = '';
    document.getElementById('binding-oauth-redirect-uri').value = '';
    modalBindingParameterValues = Object.assign({}, binding.parameterValues || {});
    modalBindingSecretValues = {};
    syncBindingModalSections(group);
    loadBindingOauthDefaultsIfNeeded(group);
    renderBindingParameterFields(group.bindingParameters || []);
    renderBindingSecretFields(group.bindingSecrets || []);
    clearBindingModalAlert();
    showModal('binding-modal');
}

function renderBindingParameterFields(parameterSchema) {
    const container = document.getElementById('binding-params-list');
    if (!container) {
        return;
    }
    container.innerHTML = '';

    const schema = parameterSchema || [];
    if (schema.length === 0) {
        container.innerHTML = '';
        return;
    }

    schema.forEach(function (parameter) {
        const name = parameter.name || '';
        const value = modalBindingParameterValues[name] == null ? '' : String(modalBindingParameterValues[name]);
        const row = document.createElement('div');
        row.className = 'mb-2 grid grid-cols-12 gap-2';
        row.innerHTML = ''
            + '<div class="col-span-4 text-xs text-zinc-300 pt-2"><span class="font-mono">' + escapeHtml(name) + '</span><div class="text-zinc-500">' + escapeHtml(parameter.type || 'string') + '</div></div>'
            + '<input class="col-span-8 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 binding-param-value" data-name="' + escapeHtml(name) + '" placeholder="' + escapeHtml(parameter.defaultValue || '') + '" value="' + escapeHtml(value) + '">';
        container.appendChild(row);
    });

    container.querySelectorAll('.binding-param-value').forEach(function (input) {
        input.addEventListener('input', function () {
            const name = input.getAttribute('data-name');
            modalBindingParameterValues[name] = input.value;
        });
    });
}

function renderBindingSecretFields(secretSchema) {
    const container = document.getElementById('binding-secrets-list');
    if (!container) {
        return;
    }
    container.innerHTML = '';

    const schema = secretSchema || [];
    if (schema.length === 0) {
        container.innerHTML = '';
        return;
    }

    schema.forEach(function (secret) {
        const name = secret.name || '';
        const value = modalBindingSecretValues[name] == null ? '' : String(modalBindingSecretValues[name]);
        const row = document.createElement('div');
        row.className = 'mb-2 grid grid-cols-12 gap-2';
        row.innerHTML = ''
            + '<div class="col-span-4 text-xs text-zinc-300 pt-2"><span class="font-mono">' + escapeHtml(name) + '</span><div class="text-zinc-500">' + escapeHtml(secret.description || '') + '</div></div>'
            + '<input class="col-span-8 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 binding-secret-value" data-name="' + escapeHtml(name) + '" placeholder="Enter new value to update" value="' + escapeHtml(value) + '">';
        container.appendChild(row);
    });

    container.querySelectorAll('.binding-secret-value').forEach(function (input) {
        input.addEventListener('input', function () {
            const name = input.getAttribute('data-name');
            modalBindingSecretValues[name] = input.value;
        });
    });
}

function syncBindingModalSections(group) {
    const baseUrlSection = document.getElementById('binding-base-url-section');
    const paramsSection = document.getElementById('binding-params-section');
    const secretsSection = document.getElementById('binding-secrets-section');
    const oauthConfigSection = document.getElementById('binding-oauth-config-section');
    const baseUrlInput = document.getElementById('binding-base-url');

    const hasParams = Array.isArray(group?.bindingParameters) && group.bindingParameters.length > 0;
    const hasSecrets = Array.isArray(group?.bindingSecrets) && group.bindingSecrets.length > 0;
    const urlOverrideEnabled = group?.urlOverrideEnabled !== false;
    const usesOAuth = String(group?.authenticationMode || 'NONE').toUpperCase() === 'OAUTH';

    if (baseUrlSection) {
        baseUrlSection.classList.toggle('hidden', !urlOverrideEnabled);
    }
    if (!urlOverrideEnabled && baseUrlInput) {
        baseUrlInput.value = '';
    }

    if (paramsSection) {
        paramsSection.classList.toggle('hidden', !hasParams);
    }
    if (secretsSection) {
        secretsSection.classList.toggle('hidden', !hasSecrets);
    }
    if (oauthConfigSection) {
        oauthConfigSection.classList.toggle('hidden', !usesOAuth);
    }
}

async function loadBindingOauthDefaultsIfNeeded(group) {
    const usesOAuth = String(group?.authenticationMode || 'NONE').toUpperCase() === 'OAUTH';
    if (!usesOAuth) {
        return;
    }
    if (!oauthConnectDefaults) {
        try {
            const response = await fetch('/api/oauth-templates/connect-defaults');
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            oauthConnectDefaults = await response.json();
        } catch (_error) {
            oauthConnectDefaults = { redirectUri: '' };
        }
    }
    const redirectInput = document.getElementById('binding-oauth-redirect-uri');
    if (redirectInput && !redirectInput.value) {
        redirectInput.value = (oauthConnectDefaults && oauthConnectDefaults.redirectUri) ? oauthConnectDefaults.redirectUri : '';
    }
}

async function saveBinding() {
    const groupUuid = document.getElementById('binding-group-uuid').value;
    const originalName = document.getElementById('binding-original-name').value.trim();
    const copySourceName = document.getElementById('binding-copy-source-name').value.trim();
    const bindingName = document.getElementById('binding-name').value.trim();
    const baseUrl = document.getElementById('binding-base-url').value.trim();
    const oauthClientId = document.getElementById('binding-oauth-client-id').value.trim();
    const oauthClientSecret = document.getElementById('binding-oauth-client-secret').value.trim();
    const oauthRedirectUri = document.getElementById('binding-oauth-redirect-uri').value.trim();

    if (!groupUuid) {
        showBindingModalAlert('Group context is missing.', 'danger');
        return;
    }
    if (!bindingName) {
        showBindingModalAlert('Binding name is required.', 'warning');
        return;
    }

    const payload = {
        name: bindingName,
        baseUrl: baseUrl,
        parameterValues: sanitizeBindingValueMap(modalBindingParameterValues),
        secretValues: sanitizeBindingValueMap(modalBindingSecretValues),
        copySecretsFromBindingName: (!originalName && copySourceName) ? copySourceName : null
    };

    const isUpdate = !!originalName;
    const path = isUpdate
        ? '/api/reflection-groups/' + encodeURIComponent(groupUuid) + '/bindings/' + encodeURIComponent(originalName)
        : '/api/reflection-groups/' + encodeURIComponent(groupUuid) + '/bindings';

    const entry = findGroupEntry(groupUuid);
    const group = entry ? (entry.group || entry) : null;
    const usesOAuth = group && String(group.authenticationMode || 'NONE').toUpperCase() === 'OAUTH';

    try {
        if (usesOAuth) {
            const oauthFlowResponse = await fetch(
                '/api/reflection-groups/' + encodeURIComponent(groupUuid) + '/bindings/oauth-flow',
                {
                    method: 'POST',
                    headers: buildJsonHeaders(),
                    body: JSON.stringify({
                        originalBindingName: originalName || '',
                        bindingRequest: payload,
                        clientId: oauthClientId,
                        clientSecret: oauthClientSecret,
                        redirectUri: oauthRedirectUri
                    })
                }
            );
            const oauthFlowResult = await parseJson(oauthFlowResponse);
            if (!oauthFlowResponse.ok) {
                showBindingModalAlert(oauthFlowResult.error || 'Failed to start OAuth binding flow.', 'danger');
                return;
            }
            if (oauthFlowResult.status === 'connect_required' && oauthFlowResult.authorizationUrl) {
                closeBindingModal();
                showAlert('Complete OAuth consent to finish saving the binding. Redirecting…', 'info');
                window.location.href = oauthFlowResult.authorizationUrl;
                return;
            }
            if (oauthFlowResult.status === 'binding_saved') {
                closeBindingModal();
                showAlert(isUpdate ? 'Binding updated.' : 'Binding created.', 'success');
                await loadAll();
                return;
            }
            showBindingModalAlert(oauthFlowResult.message || oauthFlowResult.error || 'Failed to save binding.', 'danger');
            return;
        }

        const response = await fetch(path, {
            method: isUpdate ? 'PUT' : 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showBindingModalAlert(result.error || 'Failed to save binding.', 'danger');
            return;
        }

        closeBindingModal();
        showAlert(isUpdate ? 'Binding updated.' : 'Binding created.', 'success');
        await loadAll();
    } catch (_error) {
        showBindingModalAlert('Network error while saving binding.', 'danger');
    }
}

function renderOAuthBindingCallbackAlert() {
    const params = new URLSearchParams(window.location.search);
    const status = (params.get('oauthBindingStatus') || '').trim();
    if (!status) {
        return;
    }

    if (status === 'created') {
        const bindingName = params.get('oauthBindingName') || 'binding';
        showAlert('OAuth connected and binding "' + bindingName + '" was saved.', 'success');
    } else if (status === 'error') {
        const message = params.get('oauthBindingMessage') || 'OAuth connected but binding could not be saved.';
        showAlert(message, 'danger');
    }

    params.delete('oauthBindingStatus');
    params.delete('oauthBindingName');
    params.delete('oauthBindingMessage');
    const nextQuery = params.toString();
    const nextUrl = window.location.pathname + (nextQuery ? ('?' + nextQuery) : '') + window.location.hash;
    window.history.replaceState({}, document.title, nextUrl);
}

async function deleteBinding(groupUuid, bindingName) {
    if (!confirm('Delete binding "' + bindingName + '"?')) {
        return;
    }
    try {
        const response = await fetch(
            '/api/reflection-groups/' + encodeURIComponent(groupUuid) + '/bindings/' + encodeURIComponent(bindingName),
            {
                method: 'DELETE',
                headers: buildCsrfHeaders()
            }
        );
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to delete binding.', 'danger');
            return;
        }
        showAlert('Binding deleted.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error while deleting binding.', 'danger');
    }
}

function sanitizeBindingValueMap(values) {
    const out = {};
    Object.keys(values || {}).forEach(function (key) {
        const raw = values[key];
        if (raw == null) {
            return;
        }
        const value = String(raw).trim();
        if (!value) {
            return;
        }
        out[key] = value;
    });
    return out;
}

async function deleteGroup(uuid) {
    if (!confirm('Delete this reflection group?')) {
        return;
    }
    try {
        const response = await fetch('/api/reflection-groups/' + encodeURIComponent(uuid), {
            method: 'DELETE',
            headers: buildCsrfHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to delete group.', 'danger');
            return;
        }
        showAlert('Group deleted.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error while deleting group.', 'danger');
    }
}

function openCreateReflectionModal(defaultGroupUuid) {
    if (!groups || groups.length === 0) {
        showAlert('Create a reflection group first.', 'warning');
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'New Reflection';
    document.getElementById('reflection-uuid').value = '';
    document.getElementById('reflection-id').value = '';
    document.getElementById('reflection-name').value = '';
    document.getElementById('reflection-description').value = '';
    document.getElementById('reflection-method').value = 'GET';
    document.getElementById('reflection-url').value = '';
    document.getElementById('reflection-content-type').value = 'application/json';
    document.getElementById('reflection-response-content-type').value = 'application/json';
    document.getElementById('reflection-output-schema').value = '';
    document.getElementById('reflection-body').value = '';
    modalParameters = [];
    modalHeaders = [];
    modalQueryParameters = [];
    populateGroupSelect(defaultGroupUuid || '');
    updateRequestTemplateVisibility();
    updateOutputSchemaVisibility();
    renderKeyValueRows('headers-list', modalHeaders, 'header');
    renderKeyValueRows('query-params-list', modalQueryParameters, 'query');
    renderParameters();
    clearReflectionModalAlert();
    showModal('reflection-modal');
}

function openEditReflectionModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Reflection not found. Reload and try again.', 'warning');
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'Edit Reflection';
    populateReflectionModalFromReflection(reflection, false);
    showModal('reflection-modal');
}

function openCopyReflectionModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Reflection not found. Reload and try again.', 'warning');
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'Copy Reflection';
    populateReflectionModalFromReflection(reflection, true);
    showModal('reflection-modal');
}

function populateReflectionModalFromReflection(reflection, asCopy) {
    document.getElementById('reflection-uuid').value = asCopy ? '' : (reflection.uuid || '');
    document.getElementById('reflection-id').value = asCopy ? '' : (reflection.id || '');
    document.getElementById('reflection-name').value = reflection.name || '';
    document.getElementById('reflection-description').value = reflection.description || '';
    document.getElementById('reflection-method').value = reflection.method || 'GET';
    document.getElementById('reflection-url').value = reflection.url || '';
    document.getElementById('reflection-content-type').value = reflection.requestContentType || 'application/json';
    document.getElementById('reflection-response-content-type').value = reflection.responseContentType || 'application/json';
    document.getElementById('reflection-output-schema').value = reflection.outputSchema || '';
    document.getElementById('reflection-body').value = reflection.bodyTemplate || '';
    modalHeaders = mapToKeyValueEntries(reflection.headers || {});
    modalQueryParameters = mapToKeyValueEntries(reflection.queryParameters || {});
    modalParameters = (reflection.inputParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            required: !!parameter.required
        };
    });
    populateGroupSelect(reflection.groupUuid || '');
    updateRequestTemplateVisibility();
    updateOutputSchemaVisibility();
    renderKeyValueRows('headers-list', modalHeaders, 'header');
    renderKeyValueRows('query-params-list', modalQueryParameters, 'query');
    renderParameters();
    clearReflectionModalAlert();
}

async function saveReflection() {
    const uuid = document.getElementById('reflection-uuid').value.trim();
    const payload = {
        id: document.getElementById('reflection-id').value.trim(),
        name: document.getElementById('reflection-name').value.trim(),
        description: document.getElementById('reflection-description').value.trim(),
        groupUuid: document.getElementById('reflection-group').value,
        inputParameters: sanitizeParameters(modalParameters),
        method: document.getElementById('reflection-method').value,
        url: document.getElementById('reflection-url').value.trim(),
        requestContentType: document.getElementById('reflection-content-type').value,
        responseContentType: document.getElementById('reflection-response-content-type').value,
        outputSchema: document.getElementById('reflection-output-schema').value,
        headers: keyValueEntriesToMap(modalHeaders),
        queryParameters: keyValueEntriesToMap(modalQueryParameters),
        bodyTemplate: document.getElementById('reflection-body').value
    };

    if (!payload.id || !payload.name || !payload.groupUuid || !payload.url) {
        showReflectionModalAlert('ID, Name, Group, and URL are required.', 'warning');
        return;
    }

    if (!/^[A-Za-z0-9]+$/.test(payload.id)) {
        showReflectionModalAlert('ID must be alphanumeric.', 'warning');
        return;
    }

    try {
        const response = await fetch(uuid ? '/api/reflections/' + encodeURIComponent(uuid) : '/api/reflections', {
            method: uuid ? 'PUT' : 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showReflectionModalAlert(result.error || 'Failed to save reflection.', 'danger');
            return;
        }

        closeReflectionModal();
        showAlert(uuid ? 'Reflection updated.' : 'Reflection created.', 'success');
        await loadAll();
    } catch (_error) {
        showReflectionModalAlert('Network error while saving reflection.', 'danger');
    }
}

async function deleteReflection(uuid) {
    if (!confirm('Delete this reflection?')) {
        return;
    }
    try {
        const response = await fetch('/api/reflections/' + encodeURIComponent(uuid), {
            method: 'DELETE',
            headers: buildCsrfHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to delete reflection.', 'danger');
            return;
        }
        showAlert('Reflection deleted.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error while deleting reflection.', 'danger');
    }
}

function exportGroup(groupUuid) {
    if (!groupUuid) {
        showAlert('Group id is missing for export.', 'warning');
        return;
    }
    window.location.href = '/api/reflection-groups/' + encodeURIComponent(groupUuid) + '/export';
}

async function importReflections(input) {
    const file = input.files && input.files[0] ? input.files[0] : null;
    input.value = '';
    if (!file) {
        return;
    }

    let payload;
    try {
        payload = JSON.parse(await file.text());
    } catch (error) {
        const detail = error && error.message ? ': ' + error.message : '.';
        showAlert('Could not parse file - not valid JSON' + detail, 'danger');
        return;
    }

    if (!payload.vorkReflectionGroupExport || !payload.group || !Array.isArray(payload.reflections) || payload.reflections.length === 0) {
        showAlert('Not a valid Vork reflection-group export file.', 'danger');
        return;
    }

    try {
        const response = await fetch('/api/reflection-groups/import', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            const detail = result && result.detail ? ' (' + result.detail + ')' : '';
            showAlert('Import failed: ' + (result.message || 'Unknown error') + detail, 'danger');
            return;
        }

        if (result.status === 'already_installed') {
            showAlert(result.message || 'Reflection group is already installed.', 'warning');
            return;
        }
        if (result.status === 'error') {
            showAlert(result.message || 'Import failed.', 'danger');
            return;
        }

        showAlert('Reflection group imported successfully.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error during import.', 'danger');
    }
}

function populateGroupSelect(selectedUuid) {
    const select = document.getElementById('reflection-group');
    if (!select) {
        return;
    }
    select.innerHTML = '';
    groups.forEach(function (entry) {
        const group = entry.group || entry;
        const option = document.createElement('option');
        option.value = group.uuid;
        option.textContent = group.name + ' [' + (group.type || 'REST') + ']';
        if (selectedUuid && selectedUuid === group.uuid) {
            option.selected = true;
        }
        select.appendChild(option);
    });
}

function renderParameters() {
    const container = document.getElementById('params-list');
    container.innerHTML = '';

    if (!modalParameters || modalParameters.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No explicit parameters. Runtime input is still accepted.</p>';
        return;
    }

    const header = document.createElement('div');
    header.className = 'grid grid-cols-12 gap-2 mb-1 text-xs text-zinc-500';
    header.innerHTML = ''
        + '<div class="col-span-3">Name</div>'
        + '<div class="col-span-2">Type</div>'
        + '<div class="col-span-4">Description</div>'
        + '<div class="col-span-2">Required</div>'
        + '<div class="col-span-1"></div>';
    container.appendChild(header);

    modalParameters.forEach(function (parameter, index) {
        const row = document.createElement('div');
        row.className = 'grid grid-cols-12 gap-2 mb-2';
        row.innerHTML = ''
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-name" data-index="' + index + '" value="' + escapeHtml(parameter.name || '') + '" placeholder="city">'
            + '<select class="col-span-2 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-type" data-index="' + index + '">'
            + '  <option value="string">string</option>'
            + '  <option value="int">int</option>'
            + '  <option value="double">double</option>'
            + '  <option value="boolean">boolean</option>'
            + '</select>'
            + '<input class="col-span-4 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-description" data-index="' + index + '" value="' + escapeHtml(parameter.description || '') + '" placeholder="parameter purpose">'
            + '<label class="col-span-2 inline-flex items-center gap-1 text-xs text-zinc-300"><input type="checkbox" class="param-required" data-index="' + index + '" ' + (parameter.required ? 'checked' : '') + '>Required</label>'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 remove-param" data-index="' + index + '" title="Remove"><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(row);

        const typeSelect = row.querySelector('.param-type');
        if (typeSelect) {
            typeSelect.value = parameter.type || 'string';
        }
    });

    container.querySelectorAll('.param-name').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].name = input.value;
        });
    });
    container.querySelectorAll('.param-type').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].type = input.value;
        });
    });
    container.querySelectorAll('.param-description').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].description = input.value;
        });
    });
    container.querySelectorAll('.param-required').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].required = input.checked;
        });
    });
    container.querySelectorAll('.remove-param').forEach(function (button) {
        button.addEventListener('click', function () {
            const index = Number(button.getAttribute('data-index'));
            modalParameters.splice(index, 1);
            renderParameters();
        });
    });
}

function sanitizeParameters(parameters) {
    return (parameters || [])
        .filter(function (parameter) { return parameter.name && parameter.name.trim(); })
        .map(function (parameter) {
            return {
                name: parameter.name.trim(),
                type: (parameter.type || 'string').trim(),
                description: (parameter.description || '').trim(),
                required: !!parameter.required
            };
        });
}

function mapToKeyValueEntries(map) {
    return Object.entries(map || {}).map(function (entry) {
        return { name: entry[0], value: entry[1] == null ? '' : String(entry[1]) };
    });
}

function keyValueEntriesToMap(entries) {
    const out = {};
    (entries || []).forEach(function (entry) {
        const name = (entry.name || '').trim();
        if (!name) {
            return;
        }
        out[name] = (entry.value || '').trim();
    });
    return out;
}

function renderKeyValueRows(containerId, rows, type) {
    const container = document.getElementById(containerId);
    if (!container) {
        return;
    }
    container.innerHTML = '';

    if (!rows || rows.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No entries defined.</p>';
        return;
    }

    rows.forEach(function (row, index) {
        const el = document.createElement('div');
        el.className = 'grid grid-cols-12 gap-2';
        el.innerHTML = ''
            + '<input class="col-span-5 rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-100 kv-name" data-kind="' + type + '" data-index="' + index + '" placeholder="name" value="' + escapeHtml(row.name || '') + '">'
            + '<input class="col-span-6 rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-100 kv-value" data-kind="' + type + '" data-index="' + index + '" placeholder="value" value="' + escapeHtml(row.value || '') + '">'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 kv-remove" data-kind="' + type + '" data-index="' + index + '" title="Remove"><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(el);
    });

    container.querySelectorAll('.kv-name').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            const kind = input.getAttribute('data-kind');
            const target = kind === 'header' ? modalHeaders : modalQueryParameters;
            target[index].name = input.value;
        });
    });

    container.querySelectorAll('.kv-value').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            const kind = input.getAttribute('data-kind');
            const target = kind === 'header' ? modalHeaders : modalQueryParameters;
            target[index].value = input.value;
        });
    });

    container.querySelectorAll('.kv-remove').forEach(function (button) {
        button.addEventListener('click', function () {
            const index = Number(button.getAttribute('data-index'));
            const kind = button.getAttribute('data-kind');
            const target = kind === 'header' ? modalHeaders : modalQueryParameters;
            target.splice(index, 1);
            renderKeyValueRows(containerId, target, kind);
        });
    });
}

function updateRequestTemplateVisibility() {
    const method = (document.getElementById('reflection-method').value || '').toUpperCase();
    const templateSection = document.getElementById('request-template-section');
    const contentTypeSection = document.getElementById('request-content-type-section');
    if (!templateSection && !contentTypeSection) {
        return;
    }

    const noBodyMethod = method === 'GET' || method === 'DELETE' || method === 'HEAD' || method === 'OPTIONS';
    if (noBodyMethod) {
        if (templateSection) {
            templateSection.classList.add('hidden');
        }
        if (contentTypeSection) {
            contentTypeSection.classList.add('hidden');
        }
    } else {
        if (templateSection) {
            templateSection.classList.remove('hidden');
        }
        if (contentTypeSection) {
            contentTypeSection.classList.remove('hidden');
        }
    }
}

function updateOutputSchemaVisibility() {
    const responseType = (document.getElementById('reflection-response-content-type').value || '').toLowerCase();
    const schemaSection = document.getElementById('output-schema-section');
    const schemaInput = document.getElementById('reflection-output-schema');
    if (!schemaSection || !schemaInput) {
        return;
    }

    const isJson = responseType === 'application/json';
    schemaSection.classList.toggle('hidden', !isJson);
    schemaInput.disabled = !isJson;
}

function closeGroupModal() {
    hideModal('group-modal');
}

function closeReflectionModal() {
    hideModal('reflection-modal');
}

function closeBindingModal() {
    hideModal('binding-modal');
}

function showModal(id) {
    document.getElementById(id).classList.remove('hidden');
}

function hideModal(id) {
    document.getElementById(id).classList.add('hidden');
}

function showAlert(message, type) {
    alertArea.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function showGroupModalAlert(message, type) {
    document.getElementById('group-modal-alert').innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearGroupModalAlert() {
    document.getElementById('group-modal-alert').innerHTML = '';
}

function showReflectionModalAlert(message, type) {
    document.getElementById('reflection-modal-alert').innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearReflectionModalAlert() {
    document.getElementById('reflection-modal-alert').innerHTML = '';
}

function showBindingModalAlert(message, type) {
    document.getElementById('binding-modal-alert').innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearBindingModalAlert() {
    document.getElementById('binding-modal-alert').innerHTML = '';
}

function alertClass(type) {
    switch (type) {
        case 'success':
            return 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300';
        case 'warning':
            return 'border-amber-500/40 bg-amber-500/10 text-amber-300';
        default:
            return 'border-rose-500/40 bg-rose-500/10 text-rose-300';
    }
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

async function parseJson(response) {
    try {
        return await response.json();
    } catch (_error) {
        return {};
    }
}

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
