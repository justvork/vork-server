/* settings-binding-contracts.js - Binding Contract group + tool management */
/* jshint esversion: 6 */

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

const alertArea = document.getElementById('alert-area');
const emptyState = document.getElementById('empty-state');
const tableContainer = document.getElementById('table-container');
const contractsBody = document.getElementById('contracts-body');

const contractModal = document.getElementById('contractModal');
const contractModalTitle = document.getElementById('contract-modal-title');
const contractModalAlert = document.getElementById('contract-modal-alert');

const contractId = document.getElementById('contract-id');
const contractGroupId = document.getElementById('contract-group-id');
const contractArtifactId = document.getElementById('contract-artifact-id');
const contractVersion = document.getElementById('contract-version');
const contractName = document.getElementById('contract-name');
const contractDescription = document.getElementById('contract-description');

const toolModal = document.getElementById('toolModal');
const toolModalTitle = document.getElementById('tool-modal-title');
const toolModalAlert = document.getElementById('tool-modal-alert');
const toolContractId = document.getElementById('tool-contract-id');
const toolOriginalName = document.getElementById('tool-original-name');
const toolName = document.getElementById('tool-name');
const toolDescription = document.getElementById('tool-description');
const toolPubliclyVisible = document.getElementById('tool-publicly-visible');
const toolParamsList = document.getElementById('tool-params-list');

let allContracts = [];
let toolDraftParams = [];

function init() {
    document.getElementById('new-contract-btn').addEventListener('click', openCreateContractModal);
    document.getElementById('close-contract-modal').addEventListener('click', closeContractModal);
    document.getElementById('cancel-contract-btn').addEventListener('click', closeContractModal);
    document.getElementById('save-contract-btn').addEventListener('click', saveContract);

    document.getElementById('close-tool-modal').addEventListener('click', closeToolModal);
    document.getElementById('cancel-tool-modal').addEventListener('click', closeToolModal);
    document.getElementById('save-tool-btn').addEventListener('click', saveTool);
    document.getElementById('add-tool-param-btn').addEventListener('click', addToolParameterDraft);

    document.getElementById('import-contract-btn').addEventListener('click', function () {
        document.getElementById('import-contract-input').click();
    });
    document.getElementById('import-contract-input').addEventListener('change', function () {
        importContract(this);
    });

    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape') {
            return;
        }
        if (!toolModal.classList.contains('hidden')) {
            closeToolModal();
            return;
        }
        if (!contractModal.classList.contains('hidden')) {
            closeContractModal();
        }
    });

    loadContracts();
}

document.addEventListener('DOMContentLoaded', init);

async function loadContracts() {
    try {
        const response = await fetch('/api/binding-contracts');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        allContracts = await response.json();
        renderContracts();
    } catch (error) {
        showAlert('Failed to load binding contracts: ' + (error.message || 'unknown error'), 'danger');
    }
}

function renderContracts() {
    contractsBody.innerHTML = '';

    if (!allContracts || allContracts.length === 0) {
        emptyState.classList.remove('hidden');
        tableContainer.classList.add('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    tableContainer.classList.remove('hidden');

    allContracts.forEach(function (contract) {
        const row = document.createElement('tr');
        row.className = 'border-b border-zinc-800/80 last:border-0';

        const artifactStatus = contract.artifactStatus || 'SNAPSHOT';
        const isEditable = artifactStatus === 'SNAPSHOT' || artifactStatus === 'REJECTED';

        row.innerHTML = ''
            + '<td class="px-3 py-2 align-top font-semibold text-zinc-100">' + escapeHtml(contract.name || '') + '</td>'
            + '<td class="px-3 py-2 align-top text-xs font-mono text-zinc-300">' + escapeHtml(contract.uuid || '') + '</td>'
            + '<td class="px-3 py-2 align-top">' + buildToolsCell(contract, isEditable) + '</td>'
            + '<td class="px-3 py-2 align-top"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-2 py-0.5 text-xs text-zinc-300">' + escapeHtml(artifactStatus) + '</span></td>'
            + '<td class="px-3 py-2 align-top text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" data-action="export" data-id="' + escapeHtml(contract.uuid) + '" title="Export contract"><i class="fa-solid fa-file-export"></i></button>'
            + (isEditable
                ? '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" data-action="edit" data-id="' + escapeHtml(contract.uuid) + '" title="Edit group"><i class="fa-solid fa-pen"></i></button>'
                : '')
            + buildLifecycleButtons(contract)
            + (isEditable
                ? '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-action="delete" data-id="' + escapeHtml(contract.uuid) + '" title="Delete group"><i class="fa-solid fa-trash"></i></button>'
                : '    <button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-500 cursor-not-allowed" title="Only SNAPSHOT or REJECTED groups can be deleted" disabled><i class="fa-solid fa-trash"></i></button>')
            + '  </div>'
            + '</td>';

        contractsBody.appendChild(row);
    });

    wireRowActions();
}

function buildToolsCell(contract, editable) {
    const tools = contract.tools || [];
    const pills = tools.map(function (tool, index) {
        const name = tool && tool.name ? tool.name : 'Unnamed';
        const publiclyVisible = !tool || tool.publiclyVisible === undefined || tool.publiclyVisible === null
            ? true
            : !!tool.publiclyVisible;
        return ''
            + '<span class="extra-pill tool-pill contract-tool-pill">'
            + '  <button type="button" class="contract-pill-edit" data-action="edit-tool" data-id="' + escapeHtml(contract.uuid) + '" data-tool-index="' + index + '" ' + (editable ? '' : 'disabled') + ' title="' + (editable ? 'Edit tool' : 'This contract is immutable') + '">'
            + escapeHtml(name)
            + (publiclyVisible ? '' : ' <i class="fa-solid fa-lock text-zinc-400" title="Private tool (not advertised)"></i>')
            + '  </button>'
            + (editable
                ? '  <button type="button" class="pill-remove" data-action="remove-tool" data-id="' + escapeHtml(contract.uuid) + '" data-tool-index="' + index + '" title="Remove tool"><i class="fa-solid fa-xmark"></i></button>'
                : '')
            + '</span>';
    }).join('');

    const emptyText = tools.length === 0
        ? '<span class="text-xs text-zinc-500">No tools</span>'
        : pills;

    const addButton = editable
        ? '<button type="button" class="contract-inline-add-btn" data-action="add-tool" data-id="' + escapeHtml(contract.uuid) + '"><i class="fa-solid fa-plus mr-1"></i>Add</button>'
        : '';

    return ''
        + '<div class="contract-tools-wrap">'
        + '  <div class="contract-tools-pills">' + emptyText + '</div>'
        + (addButton ? '  <div class="contract-tools-add">' + addButton + '</div>' : '')
        + '</div>';
}

function wireRowActions() {
    contractsBody.querySelectorAll('button[data-action="edit"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openEditContractModal(button.getAttribute('data-id'));
        });
    });

    contractsBody.querySelectorAll('button[data-action="delete"]').forEach(function (button) {
        button.addEventListener('click', function () {
            deleteContract(button.getAttribute('data-id'));
        });
    });

    contractsBody.querySelectorAll('button[data-action="export"]').forEach(function (button) {
        button.addEventListener('click', function () {
            exportContract(button.getAttribute('data-id'));
        });
    });

    contractsBody.querySelectorAll('button[data-action="submit"]').forEach(function (button) {
        button.addEventListener('click', function () {
            transitionContract(button.getAttribute('data-id'), 'submit');
        });
    });

    contractsBody.querySelectorAll('button[data-action="stage"]').forEach(function (button) {
        button.addEventListener('click', function () {
            transitionContract(button.getAttribute('data-id'), 'stage');
        });
    });

    contractsBody.querySelectorAll('button[data-action="publish"]').forEach(function (button) {
        button.addEventListener('click', function () {
            transitionContract(button.getAttribute('data-id'), 'publish');
        });
    });

    contractsBody.querySelectorAll('button[data-action="add-tool"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openAddToolModal(button.getAttribute('data-id'));
        });
    });

    contractsBody.querySelectorAll('button[data-action="edit-tool"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openEditToolModal(button.getAttribute('data-id'), Number(button.getAttribute('data-tool-index')));
        });
    });

    contractsBody.querySelectorAll('button[data-action="remove-tool"]').forEach(function (button) {
        button.addEventListener('click', function () {
            deleteTool(button.getAttribute('data-id'), Number(button.getAttribute('data-tool-index')));
        });
    });
}

function buildLifecycleButtons(contract) {
    const status = contract.artifactStatus || 'SNAPSHOT';
    const id = escapeHtml(contract.uuid);
    if (status === 'SNAPSHOT' || status === 'REJECTED') {
        return '<button class="rounded-md border border-blue-500/40 px-2 py-1 text-xs text-blue-300 transition-colors hover:bg-blue-500/15" data-action="submit" data-id="' + id + '" title="Submit"><i class="fa-solid fa-paper-plane"></i></button>';
    }
    if (status === 'SUBMITTED') {
        return '<button class="rounded-md border border-indigo-500/40 px-2 py-1 text-xs text-indigo-300 transition-colors hover:bg-indigo-500/15" data-action="stage" data-id="' + id + '" title="Stage"><i class="fa-solid fa-layer-group"></i></button>';
    }
    if (status === 'STAGED') {
        return '<button class="rounded-md border border-emerald-500/40 px-2 py-1 text-xs text-emerald-300 transition-colors hover:bg-emerald-500/15" data-action="publish" data-id="' + id + '" title="Publish"><i class="fa-solid fa-cloud-arrow-up"></i></button>';
    }
    return '';
}

function openCreateContractModal() {
    contractModalTitle.textContent = 'New Binding Contract Group';
    contractId.value = '';
    contractGroupId.value = 'binding';
    contractArtifactId.value = '';
    contractVersion.value = 'SNAPSHOT';
    contractName.value = '';
    contractDescription.value = '';
    contractGroupId.disabled = false;
    contractArtifactId.disabled = false;
    contractVersion.disabled = false;
    clearContractModalAlert();
    openContractModal();
}

function openEditContractModal(id) {
    const contract = allContracts.find(function (entry) { return entry.uuid === id; });
    if (!contract) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }

    contractModalTitle.textContent = 'Edit Binding Contract Group';
    contractId.value = contract.uuid || '';
    contractGroupId.value = contract.groupId || '';
    contractArtifactId.value = contract.artifactId || '';
    contractVersion.value = contract.version || 'SNAPSHOT';
    contractName.value = contract.name || '';
    contractDescription.value = contract.description || '';

    const isEditable = (contract.artifactStatus || 'SNAPSHOT') === 'SNAPSHOT'
        || (contract.artifactStatus || 'SNAPSHOT') === 'REJECTED';
    contractGroupId.disabled = !isEditable;
    contractArtifactId.disabled = !isEditable;
    contractVersion.disabled = !isEditable;

    clearContractModalAlert();
    openContractModal();
}

async function saveContract() {
    const id = contractId.value.trim();
    const existing = id
        ? allContracts.find(function (entry) { return entry.uuid === id; })
        : null;
    const payload = {
        uuid: id || null,
        name: contractName.value.trim(),
        description: contractDescription.value.trim(),
        tools: existing ? (existing.tools || []) : [],
        groupId: contractGroupId.value.trim(),
        artifactId: contractArtifactId.value.trim(),
        version: contractVersion.value.trim()
    };

    if (!payload.name || !payload.groupId || !payload.artifactId || !payload.version) {
        showContractModalAlert('Name, groupId, artifactId, and version are required.', 'warning');
        return;
    }

    const url = id ? '/api/binding-contracts/' + encodeURIComponent(id) : '/api/binding-contracts';
    const method = id ? 'PUT' : 'POST';

    try {
        const response = await fetch(url, {
            method: method,
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showContractModalAlert(result.error || result.message || 'Failed to save group.', 'danger');
            return;
        }

        closeContractModal();
        showAlert(id ? 'Binding contract group updated.' : 'Binding contract group created.', 'success');
        await loadContracts();
    } catch (error) {
        showContractModalAlert('Network error while saving group.', 'danger');
    }
}

function openAddToolModal(contractUuid) {
    const contract = allContracts.find(function (entry) { return entry.uuid === contractUuid; });
    if (!contract) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    if (!isContractEditable(contract)) {
        showAlert('Only SNAPSHOT or REJECTED groups can be modified.', 'warning');
        return;
    }

    toolModalTitle.textContent = 'Add Tool';
    toolContractId.value = contract.uuid;
    toolOriginalName.value = '';
    toolName.value = '';
    toolDescription.value = '';
    toolPubliclyVisible.checked = true;
    toolDraftParams = [];
    clearToolModalAlert();
    renderToolParameterRows();
    openToolModal();
}

function openEditToolModal(contractUuid, toolIndex) {
    const contract = allContracts.find(function (entry) { return entry.uuid === contractUuid; });
    if (!contract) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    if (!isContractEditable(contract)) {
        showAlert('Only SNAPSHOT or REJECTED groups can be modified.', 'warning');
        return;
    }

    const tool = (contract.tools || [])[toolIndex];
    if (!tool) {
        showAlert('Tool not found. Reload and try again.', 'warning');
        return;
    }

    toolModalTitle.textContent = 'Edit Tool: ' + (tool.name || 'Tool');
    toolContractId.value = contract.uuid;
    toolOriginalName.value = tool.name || '';
    toolName.value = tool.name || '';
    toolDescription.value = tool.description || '';
    toolPubliclyVisible.checked = tool.publiclyVisible === undefined || tool.publiclyVisible === null
        ? true
        : !!tool.publiclyVisible;
    toolDraftParams = (tool.inputParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            required: !!parameter.required,
            array: !!parameter.array
        };
    });

    clearToolModalAlert();
    renderToolParameterRows();
    openToolModal();
}

function addToolParameterDraft() {
    toolDraftParams.push({
        name: '',
        type: 'string',
        description: '',
        required: false,
        array: false
    });
    renderToolParameterRows();
}

function removeToolParameterDraft(paramIndex) {
    toolDraftParams.splice(paramIndex, 1);
    renderToolParameterRows();
}

function renderToolParameterRows() {
    toolParamsList.innerHTML = '';

    if (!toolDraftParams.length) {
        const message = document.createElement('div');
        message.className = 'rounded-md border border-zinc-700 bg-zinc-900 px-3 py-2 text-xs text-zinc-400';
        message.textContent = 'No parameters defined.';
        toolParamsList.appendChild(message);
        return;
    }

    toolDraftParams.forEach(function (parameter, paramIndex) {
        const row = document.createElement('div');
        row.className = 'contract-param-grid rounded-md border border-zinc-800 bg-zinc-900 p-2';
        row.innerHTML = ''
            + '<input data-param-name="' + paramIndex + '" type="text" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100" placeholder="name" value="' + escapeHtmlAttribute(parameter.name || '') + '">'
            + '<select data-param-type="' + paramIndex + '" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100">'
            + typeOptions(parameter.type)
            + '</select>'
            + '<input data-param-description="' + paramIndex + '" type="text" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100" placeholder="description" value="' + escapeHtmlAttribute(parameter.description || '') + '">'
            + '<label class="inline-flex items-center gap-1 text-xs text-zinc-300"><input data-param-required="' + paramIndex + '" type="checkbox" ' + (parameter.required ? 'checked' : '') + '>required</label>'
            + '<label class="inline-flex items-center gap-1 text-xs text-zinc-300"><input data-param-array="' + paramIndex + '" type="checkbox" ' + (parameter.array ? 'checked' : '') + '>array</label>'
            + '<button type="button" class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-remove-param="' + paramIndex + '"><i class="fa-solid fa-trash"></i></button>';
        toolParamsList.appendChild(row);
    });

    toolParamsList.querySelectorAll('button[data-remove-param]').forEach(function (button) {
        button.addEventListener('click', function () {
            removeToolParameterDraft(Number(button.getAttribute('data-remove-param')));
        });
    });

    toolParamsList.querySelectorAll('input[data-param-name]').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-param-name'));
            toolDraftParams[index].name = input.value;
        });
    });

    toolParamsList.querySelectorAll('select[data-param-type]').forEach(function (select) {
        select.addEventListener('change', function () {
            const index = Number(select.getAttribute('data-param-type'));
            toolDraftParams[index].type = select.value;
        });
    });

    toolParamsList.querySelectorAll('input[data-param-description]').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-param-description'));
            toolDraftParams[index].description = input.value;
        });
    });

    toolParamsList.querySelectorAll('input[data-param-required]').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-param-required'));
            toolDraftParams[index].required = input.checked;
        });
    });

    toolParamsList.querySelectorAll('input[data-param-array]').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-param-array'));
            toolDraftParams[index].array = input.checked;
        });
    });
}

function typeOptions(selectedType) {
    const types = ['string', 'int', 'double', 'boolean', 'date', 'timestamp'];
    return types.map(function (type) {
        const selected = (selectedType || 'string') === type ? ' selected' : '';
        return '<option value="' + type + '"' + selected + '>' + type + '</option>';
    }).join('');
}

function sanitizeSingleTool() {
    const cleanedName = (toolName.value || '').trim();
    if (!cleanedName) {
        return null;
    }
    return {
        name: cleanedName,
        description: (toolDescription.value || '').trim(),
        publiclyVisible: !!toolPubliclyVisible.checked,
        inputParameters: (toolDraftParams || [])
            .filter(function (parameter) {
                return parameter && parameter.name && parameter.name.trim();
            })
            .map(function (parameter) {
                return {
                    name: parameter.name.trim(),
                    type: (parameter.type || 'string').trim().toLowerCase(),
                    description: (parameter.description || '').trim(),
                    required: !!parameter.required,
                    array: !!parameter.array
                };
            })
    };
}

async function saveTool() {
    const groupId = toolContractId.value.trim();
    const originalName = toolOriginalName.value.trim();
    const payload = sanitizeSingleTool();

    if (!groupId) {
        showToolModalAlert('No contract group selected.', 'warning');
        return;
    }
    if (!payload || !payload.name) {
        showToolModalAlert('Tool name is required.', 'warning');
        return;
    }

    const creating = !originalName;
    const url = creating
        ? '/api/binding-contracts/' + encodeURIComponent(groupId) + '/tools'
        : '/api/binding-contracts/' + encodeURIComponent(groupId) + '/tools/' + encodeURIComponent(originalName);
    const method = creating ? 'POST' : 'PUT';

    try {
        const response = await fetch(url, {
            method: method,
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showToolModalAlert(result.error || result.message || 'Failed to save tool.', 'danger');
            return;
        }

        closeToolModal();
        showAlert(creating ? 'Tool added.' : 'Tool updated.', 'success');
        await loadContracts();
    } catch (error) {
        showToolModalAlert('Network error while saving tool.', 'danger');
    }
}

async function deleteTool(contractUuid, toolIndex) {
    const contract = allContracts.find(function (entry) { return entry.uuid === contractUuid; });
    if (!contract) {
        showAlert('Group not found. Reload and try again.', 'warning');
        return;
    }
    if (!isContractEditable(contract)) {
        showAlert('Only SNAPSHOT or REJECTED groups can be modified.', 'warning');
        return;
    }

    const tool = (contract.tools || [])[toolIndex];
    if (!tool || !tool.name) {
        showAlert('Tool not found. Reload and try again.', 'warning');
        return;
    }
    if (!confirm('Delete tool "' + tool.name + '" from this contract group?')) {
        return;
    }

    try {
        const response = await fetch('/api/binding-contracts/' + encodeURIComponent(contract.uuid) + '/tools/' + encodeURIComponent(tool.name), {
            method: 'DELETE',
            headers: buildJsonHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || result.message || 'Failed to delete tool.', 'danger');
            return;
        }

        showAlert('Tool deleted.', 'success');
        await loadContracts();
    } catch (error) {
        showAlert('Network error while deleting tool.', 'danger');
    }
}

function isContractEditable(contract) {
    const status = contract && contract.artifactStatus ? contract.artifactStatus : 'SNAPSHOT';
    return status === 'SNAPSHOT' || status === 'REJECTED';
}

async function transitionContract(id, action) {
    if (!id || !action) {
        return;
    }
    try {
        const response = await fetch('/api/binding-contracts/' + encodeURIComponent(id) + '/' + action, {
            method: 'POST',
            headers: buildJsonHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || result.message || 'Lifecycle update failed.', 'danger');
            return;
        }
        showAlert('Contract moved to ' + escapeHtml(result.artifactStatus || 'next state') + '.', 'success');
        await loadContracts();
    } catch (error) {
        showAlert('Network error while updating lifecycle.', 'danger');
    }
}

async function deleteContract(id) {
    if (!id) {
        return;
    }
    if (!confirm('Delete this binding contract group?')) {
        return;
    }

    try {
        const response = await fetch('/api/binding-contracts/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: buildJsonHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || result.message || 'Failed to delete group.', 'danger');
            return;
        }
        showAlert('Binding contract group deleted.', 'success');
        await loadContracts();
    } catch (error) {
        showAlert('Network error while deleting group.', 'danger');
    }
}

function exportContract(id) {
    if (!id) {
        return;
    }
    window.location.href = '/api/binding-contracts/' + encodeURIComponent(id) + '/export';
}

async function importContract(fileInput) {
    const file = fileInput.files && fileInput.files[0] ? fileInput.files[0] : null;
    if (!file) {
        return;
    }

    try {
        const text = await file.text();
        const payload = JSON.parse(text);

        if (!payload || payload.vorkBindingContractExport !== 'vorkBindingContractExport' || !payload.contract) {
            showAlert('Not a valid binding contract export package.', 'danger');
            return;
        }

        const response = await fetch('/api/binding-contracts/import', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || result.message || 'Failed to import contract.', 'danger');
            return;
        }

        showAlert('Binding contract import complete (' + result.status + ').', 'success');
        await loadContracts();
    } catch (error) {
        showAlert('Invalid JSON file for binding contract import.', 'danger');
    } finally {
        fileInput.value = '';
    }
}

function openContractModal() {
    contractModal.classList.remove('hidden');
}

function closeContractModal() {
    contractModal.classList.add('hidden');
    contractGroupId.disabled = false;
    contractArtifactId.disabled = false;
    contractVersion.disabled = false;
    clearContractModalAlert();
}

function openToolModal() {
    toolModal.classList.remove('hidden');
}

function closeToolModal() {
    toolModal.classList.add('hidden');
    toolContractId.value = '';
    toolOriginalName.value = '';
    toolName.value = '';
    toolDescription.value = '';
    toolPubliclyVisible.checked = true;
    toolDraftParams = [];
    clearToolModalAlert();
}

function showAlert(message, type) {
    if (!alertArea) {
        return;
    }
    alertArea.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function showContractModalAlert(message, type) {
    contractModalAlert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearContractModalAlert() {
    contractModalAlert.innerHTML = '';
}

function showToolModalAlert(message, type) {
    toolModalAlert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearToolModalAlert() {
    toolModalAlert.innerHTML = '';
}

function alertClass(type) {
    switch (type) {
    case 'success':
        return 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200';
    case 'warning':
        return 'border-amber-500/40 bg-amber-500/10 text-amber-200';
    case 'danger':
    default:
        return 'border-rose-500/40 bg-rose-500/10 text-rose-200';
    }
}

function buildJsonHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    return headers;
}

async function parseJson(response) {
    const text = await response.text();
    if (!text) {
        return {};
    }
    try {
        return JSON.parse(text);
    } catch (error) {
        return { error: text };
    }
}

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escapeHtmlAttribute(value) {
    return escapeHtml(value).replace(/`/g, '&#96;');
}
