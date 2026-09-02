/* settings-binding-contracts.js - Binding Contract management */
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
const toolsContainer = document.getElementById('tools-container');

let allContracts = [];
let draftTools = [];

function init() {
    document.getElementById('new-contract-btn').addEventListener('click', openCreateContractModal);
    document.getElementById('close-contract-modal').addEventListener('click', closeContractModal);
    document.getElementById('cancel-contract-btn').addEventListener('click', closeContractModal);
    document.getElementById('save-contract-btn').addEventListener('click', saveContract);
    document.getElementById('add-tool-btn').addEventListener('click', addToolDraft);
    document.getElementById('import-contract-btn').addEventListener('click', function () {
        document.getElementById('import-contract-input').click();
    });
    document.getElementById('import-contract-input').addEventListener('change', function () {
        importContract(this);
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && !contractModal.classList.contains('hidden')) {
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
            + '<td class="px-3 py-2 align-top text-zinc-300">' + String((contract.tools || []).length) + '</td>'
            + '<td class="px-3 py-2 align-top"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-2 py-0.5 text-xs text-zinc-300">' + escapeHtml(artifactStatus) + '</span></td>'
            + '<td class="px-3 py-2 align-top text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" data-action="export" data-id="' + escapeHtml(contract.uuid) + '" title="Export contract"><i class="fa-solid fa-file-export"></i></button>'
            + (isEditable
                ? '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" data-action="edit" data-id="' + escapeHtml(contract.uuid) + '" title="Edit contract"><i class="fa-solid fa-pen"></i></button>'
                : '')
            + buildLifecycleButtons(contract)
            + (isEditable
                ? '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-action="delete" data-id="' + escapeHtml(contract.uuid) + '" title="Delete contract"><i class="fa-solid fa-trash"></i></button>'
                : '    <button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-500 cursor-not-allowed" title="Only SNAPSHOT or REJECTED contracts can be deleted" disabled><i class="fa-solid fa-trash"></i></button>')
            + '  </div>'
            + '</td>';

        contractsBody.appendChild(row);
    });

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
    contractModalTitle.textContent = 'New Binding Contract';
    contractId.value = '';
    contractGroupId.value = 'binding';
    contractArtifactId.value = '';
    contractVersion.value = 'SNAPSHOT';
    contractName.value = '';
    contractDescription.value = '';
    draftTools = [];
    addToolDraft();
    clearContractModalAlert();
    openContractModal();
}

function openEditContractModal(id) {
    const contract = allContracts.find(function (entry) { return entry.uuid === id; });
    if (!contract) {
        showAlert('Contract not found. Reload and try again.', 'warning');
        return;
    }

    contractModalTitle.textContent = 'Edit Binding Contract';
    contractId.value = contract.uuid || '';
    contractGroupId.value = contract.groupId || '';
    contractArtifactId.value = contract.artifactId || '';
    contractVersion.value = contract.version || 'SNAPSHOT';
    contractName.value = contract.name || '';
    contractDescription.value = contract.description || '';
    draftTools = cloneTools(contract.tools || []);
    renderTools();

    const isEditable = (contract.artifactStatus || 'SNAPSHOT') === 'SNAPSHOT'
        || (contract.artifactStatus || 'SNAPSHOT') === 'REJECTED';
    contractGroupId.disabled = !isEditable;
    contractArtifactId.disabled = !isEditable;
    contractVersion.disabled = !isEditable;

    clearContractModalAlert();
    openContractModal();
}

function cloneTools(tools) {
    return (tools || []).map(function (tool) {
        return {
            name: tool.name || '',
            description: tool.description || '',
            inputParameters: (tool.inputParameters || []).map(function (parameter) {
                return {
                    name: parameter.name || '',
                    type: parameter.type || 'string',
                    description: parameter.description || '',
                    required: !!parameter.required,
                    array: !!parameter.array
                };
            })
        };
    });
}

function addToolDraft() {
    draftTools.push({
        name: '',
        description: '',
        inputParameters: []
    });
    renderTools();
}

function removeToolDraft(toolIndex) {
    draftTools.splice(toolIndex, 1);
    renderTools();
}

function addParameterDraft(toolIndex) {
    draftTools[toolIndex].inputParameters.push({
        name: '',
        type: 'string',
        description: '',
        required: false,
        array: false
    });
    renderTools();
}

function removeParameterDraft(toolIndex, parameterIndex) {
    draftTools[toolIndex].inputParameters.splice(parameterIndex, 1);
    renderTools();
}

function renderTools() {
    toolsContainer.innerHTML = '';

    if (draftTools.length === 0) {
        const message = document.createElement('div');
        message.className = 'rounded-md border border-zinc-700 bg-zinc-900 px-3 py-2 text-xs text-zinc-400';
        message.textContent = 'No tools defined yet. Add at least one tool.';
        toolsContainer.appendChild(message);
        return;
    }

    draftTools.forEach(function (tool, toolIndex) {
        const card = document.createElement('div');
        card.className = 'contract-tool-card';
        card.innerHTML = ''
            + '<div class="mb-2 flex items-center justify-between">'
            + '  <h5 class="text-sm font-semibold text-zinc-200">Tool ' + (toolIndex + 1) + '</h5>'
            + '  <button type="button" class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-remove-tool="' + toolIndex + '"><i class="fa-solid fa-trash"></i></button>'
            + '</div>'
            + '<div class="grid grid-cols-1 gap-2 md:grid-cols-2">'
            + '  <div>'
            + '    <label class="mb-1 block text-xs font-medium text-zinc-300">Name <span class="text-rose-400">*</span></label>'
            + '    <input data-tool-name="' + toolIndex + '" type="text" class="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100" value="' + escapeHtmlAttribute(tool.name) + '">'
            + '  </div>'
            + '  <div>'
            + '    <label class="mb-1 block text-xs font-medium text-zinc-300">Description</label>'
            + '    <input data-tool-description="' + toolIndex + '" type="text" class="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100" value="' + escapeHtmlAttribute(tool.description) + '">'
            + '  </div>'
            + '</div>'
            + '<div class="mt-3">'
            + '  <div class="mb-2 flex items-center justify-between">'
            + '    <h6 class="text-xs font-semibold uppercase tracking-wide text-zinc-400">Input Parameters</h6>'
            + '    <button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" data-add-param="' + toolIndex + '"><i class="fa-solid fa-plus mr-1"></i>Add Parameter</button>'
            + '  </div>'
            + renderParameterRows(tool, toolIndex)
            + '</div>';
        toolsContainer.appendChild(card);
    });

    wireToolEditorEvents();
}

function renderParameterRows(tool, toolIndex) {
    const params = tool.inputParameters || [];
    if (params.length === 0) {
        return '<div class="rounded-md border border-zinc-700 bg-zinc-900 px-3 py-2 text-xs text-zinc-400">No parameters defined.</div>';
    }

    return params.map(function (param, paramIndex) {
        return ''
            + '<div class="contract-param-grid mb-2 rounded-md border border-zinc-800 bg-zinc-900 p-2">'
            + '  <input data-param-name="' + toolIndex + ':' + paramIndex + '" type="text" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100" placeholder="name" value="' + escapeHtmlAttribute(param.name || '') + '">'
            + '  <select data-param-type="' + toolIndex + ':' + paramIndex + '" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100">'
            + typeOptions(param.type)
            + '  </select>'
            + '  <input data-param-description="' + toolIndex + ':' + paramIndex + '" type="text" class="rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100" placeholder="description" value="' + escapeHtmlAttribute(param.description || '') + '">'
            + '  <label class="inline-flex items-center gap-1 text-xs text-zinc-300"><input data-param-required="' + toolIndex + ':' + paramIndex + '" type="checkbox" ' + (param.required ? 'checked' : '') + '>required</label>'
            + '  <label class="inline-flex items-center gap-1 text-xs text-zinc-300"><input data-param-array="' + toolIndex + ':' + paramIndex + '" type="checkbox" ' + (param.array ? 'checked' : '') + '>array</label>'
            + '  <button type="button" class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-remove-param="' + toolIndex + ':' + paramIndex + '"><i class="fa-solid fa-trash"></i></button>'
            + '</div>';
    }).join('');
}

function typeOptions(selectedType) {
    const types = ['string', 'int', 'double', 'boolean', 'date', 'timestamp'];
    return types.map(function (type) {
        const selected = (selectedType || 'string') === type ? ' selected' : '';
        return '<option value="' + type + '"' + selected + '>' + type + '</option>';
    }).join('');
}

function wireToolEditorEvents() {
    toolsContainer.querySelectorAll('button[data-remove-tool]').forEach(function (button) {
        button.addEventListener('click', function () {
            removeToolDraft(Number(button.getAttribute('data-remove-tool')));
        });
    });

    toolsContainer.querySelectorAll('button[data-add-param]').forEach(function (button) {
        button.addEventListener('click', function () {
            addParameterDraft(Number(button.getAttribute('data-add-param')));
        });
    });

    toolsContainer.querySelectorAll('button[data-remove-param]').forEach(function (button) {
        button.addEventListener('click', function () {
            const parts = (button.getAttribute('data-remove-param') || '').split(':');
            removeParameterDraft(Number(parts[0]), Number(parts[1]));
        });
    });

    toolsContainer.querySelectorAll('input[data-tool-name]').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-tool-name'));
            draftTools[index].name = input.value;
        });
    });

    toolsContainer.querySelectorAll('input[data-tool-description]').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-tool-description'));
            draftTools[index].description = input.value;
        });
    });

    toolsContainer.querySelectorAll('input[data-param-name]').forEach(function (input) {
        input.addEventListener('input', function () {
            const parts = (input.getAttribute('data-param-name') || '').split(':');
            draftTools[Number(parts[0])].inputParameters[Number(parts[1])].name = input.value;
        });
    });

    toolsContainer.querySelectorAll('select[data-param-type]').forEach(function (select) {
        select.addEventListener('change', function () {
            const parts = (select.getAttribute('data-param-type') || '').split(':');
            draftTools[Number(parts[0])].inputParameters[Number(parts[1])].type = select.value;
        });
    });

    toolsContainer.querySelectorAll('input[data-param-description]').forEach(function (input) {
        input.addEventListener('input', function () {
            const parts = (input.getAttribute('data-param-description') || '').split(':');
            draftTools[Number(parts[0])].inputParameters[Number(parts[1])].description = input.value;
        });
    });

    toolsContainer.querySelectorAll('input[data-param-required]').forEach(function (input) {
        input.addEventListener('change', function () {
            const parts = (input.getAttribute('data-param-required') || '').split(':');
            draftTools[Number(parts[0])].inputParameters[Number(parts[1])].required = input.checked;
        });
    });

    toolsContainer.querySelectorAll('input[data-param-array]').forEach(function (input) {
        input.addEventListener('change', function () {
            const parts = (input.getAttribute('data-param-array') || '').split(':');
            draftTools[Number(parts[0])].inputParameters[Number(parts[1])].array = input.checked;
        });
    });
}

function sanitizeTools(tools) {
    return (tools || [])
        .filter(function (tool) {
            return tool && tool.name && tool.name.trim();
        })
        .map(function (tool) {
            return {
                name: tool.name.trim(),
                description: (tool.description || '').trim(),
                inputParameters: (tool.inputParameters || [])
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
        });
}

async function saveContract() {
    const id = contractId.value.trim();
    const payload = {
        uuid: id || null,
        name: contractName.value.trim(),
        description: contractDescription.value.trim(),
        tools: sanitizeTools(draftTools),
        groupId: contractGroupId.value.trim(),
        artifactId: contractArtifactId.value.trim(),
        version: contractVersion.value.trim()
    };

    if (!payload.name || !payload.groupId || !payload.artifactId || !payload.version) {
        showContractModalAlert('Name, groupId, artifactId, and version are required.', 'warning');
        return;
    }
    if (!payload.tools || payload.tools.length === 0) {
        showContractModalAlert('At least one tool definition is required.', 'warning');
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
            showContractModalAlert(result.error || result.message || 'Failed to save contract.', 'danger');
            return;
        }

        closeContractModal();
        showAlert(id ? 'Binding contract updated.' : 'Binding contract created.', 'success');
        await loadContracts();
    } catch (error) {
        showContractModalAlert('Network error while saving contract.', 'danger');
    }
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
    if (!confirm('Delete this binding contract?')) {
        return;
    }

    try {
        const response = await fetch('/api/binding-contracts/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: buildJsonHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || result.message || 'Failed to delete contract.', 'danger');
            return;
        }
        showAlert('Binding contract deleted.', 'success');
        await loadContracts();
    } catch (error) {
        showAlert('Network error while deleting contract.', 'danger');
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
