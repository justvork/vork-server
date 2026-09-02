/* reflections.js - Reflection management */
/* jshint esversion: 6 */

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';
const isReadOnly = document.body.getAttribute('data-reflections-read-only') === 'true';

let groups = [];
let reflections = [];
let oauthTemplates = [];
let bindingContracts = [];
let modalParameters = [];
let recordModalParameters = [];
let modalHeaders = [];
let modalQueryParameters = [];
let modalGroupBindingParameters = [];
let modalGroupBindingSecrets = [];
let modalBindingParameterValues = {};
let modalBindingSecretValues = {};
let modalSelectedBindingContractUuids = [];
let oauthConnectDefaults = null;
let githubConnection = null;
let mongoWizardCollections = [];
let mongoWizardStep = 1;
let mongoWizardConnectionValidated = false;
let mongoWizardCollectionsLoaded = false;
let activeContractTool = null;

function isValidIdentity(value) {
    return /^[A-Za-z0-9]{3,64}$/.test(String(value || '').trim());
}

const alertArea = document.getElementById('alert-area');

function init() {
    bindEvents();
    initGitHubConnection();
    renderOAuthBindingCallbackAlert();
    loadAll();
}

document.addEventListener('DOMContentLoaded', init);

function bindEvents() {
    const newGroupBtn = document.getElementById('new-group-btn');
    const mongoWizardBtn = document.getElementById('mongo-wizard-btn');
    const importReflectionsBtn = document.getElementById('import-reflections-btn');
    const importReflectionsInput = document.getElementById('import-reflections-input');
    if (newGroupBtn) {
        newGroupBtn.addEventListener('click', openCreateGroupModal);
    }
    if (mongoWizardBtn) {
        mongoWizardBtn.addEventListener('click', openMongoWizardModal);
    }
    if (importReflectionsBtn && importReflectionsInput) {
        importReflectionsBtn.addEventListener('click', function () {
            importReflectionsInput.click();
        });
        importReflectionsInput.addEventListener('change', function () {
            importReflections(importReflectionsInput);
        });
    }

    const groupsBody = document.getElementById('groups-body');
    if (groupsBody) {
        groupsBody.addEventListener('click', function (event) {
            if (isReadOnly) {
                return;
            }
            const target = event.target instanceof Element ? event.target.closest('button[data-action]') : null;
            if (!target) {
                return;
            }
            const action = target.getAttribute('data-action');
            const groupUuid = target.getAttribute('data-group-id');
            if (action !== 'add-tool' && action !== 'add-reflection' && action !== 'add-mongo-tool' && action !== 'add-binding') {
                return;
            }
            event.preventDefault();
            event.stopPropagation();

            if (action === 'add-binding') {
                openCreateBindingModal(groupUuid);
                return;
            }

            if (action === 'add-mongo-tool') {
                openCreateMongoToolModal(groupUuid);
                return;
            }
            if (action === 'add-reflection') {
                openCreateReflectionModal(groupUuid);
                return;
            }

            const groupType = resolveGroupType(groupUuid);
            if (groupType === 'MONGO') {
                openCreateMongoToolModal(groupUuid);
                return;
            }
            if (groupType === 'RECORD') {
                openCreateRecordToolModal(groupUuid);
                return;
            }
            openCreateReflectionModal(groupUuid);
        });
    }

    document.getElementById('group-modal-close').addEventListener('click', closeGroupModal);
    document.getElementById('group-modal-cancel').addEventListener('click', closeGroupModal);
    document.getElementById('group-modal-save').addEventListener('click', saveGroup);
    document.getElementById('group-auth-mode').addEventListener('change', syncGroupAuthVisibility);
    document.getElementById('group-oauth-template').addEventListener('change', syncGroupAuthVisibility);
    document.querySelectorAll('.group-tab-btn').forEach(function (button) {
        button.addEventListener('click', function () {
            setGroupModalTab(button.getAttribute('data-tab') || 'general');
        });
    });
    setupGroupBindingContractSearch();
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
    document.getElementById('reflection-group').addEventListener('change', onReflectionGroupChanged);
    document.getElementById('reflection-id').addEventListener('input', onReflectionIdentityChanged);

    const mongoToolModalClose = document.getElementById('mongo-tool-modal-close');
    const mongoToolModalCancel = document.getElementById('mongo-tool-modal-cancel');
    const mongoToolModalSave = document.getElementById('mongo-tool-modal-save');
    if (mongoToolModalClose) {
        mongoToolModalClose.addEventListener('click', closeMongoToolModal);
    }
    if (mongoToolModalCancel) {
        mongoToolModalCancel.addEventListener('click', closeMongoToolModal);
    }
    if (mongoToolModalSave) {
        mongoToolModalSave.addEventListener('click', saveMongoToolReflection);
    }
    const mongoToolOperation = document.getElementById('mongo-tool-operation');
    if (mongoToolOperation) {
        mongoToolOperation.addEventListener('change', syncMongoToolSearchConfigVisibility);
    }
    const recordToolModalClose = document.getElementById('record-tool-modal-close');
    const recordToolModalCancel = document.getElementById('record-tool-modal-cancel');
    const recordToolModalSave = document.getElementById('record-tool-modal-save');
    const addRecordParamBtn = document.getElementById('add-record-param-btn');
    if (recordToolModalClose) {
        recordToolModalClose.addEventListener('click', closeRecordToolModal);
    }
    if (recordToolModalCancel) {
        recordToolModalCancel.addEventListener('click', closeRecordToolModal);
    }
    if (recordToolModalSave) {
        recordToolModalSave.addEventListener('click', saveRecordToolReflection);
    }
    if (addRecordParamBtn) {
        addRecordParamBtn.addEventListener('click', function () {
            recordModalParameters.push({ name: '', type: 'string', description: '', required: false, array: false });
            renderRecordToolParameters();
        });
    }
    const recordToolOperation = document.getElementById('record-tool-operation');
    if (recordToolOperation) {
        recordToolOperation.addEventListener('change', function () {
            syncRecordToolOperationUi();
            renderRecordToolParameters();
        });
    }
    document.getElementById('reflection-method').addEventListener('change', function () {
        updateRequestTemplateVisibility();
    });
    document.getElementById('reflection-response-content-type').addEventListener('change', function () {
        updateOutputSchemaVisibility();
    });
    document.querySelectorAll('.reflection-tab-btn').forEach(function (button) {
        button.addEventListener('click', function () {
            const tab = button.getAttribute('data-tab') || 'request';
            setReflectionTab(tab);
        });
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
        modalParameters.push({ name: '', type: 'string', description: '', required: false, array: false });
        renderParameters();
    });

    document.getElementById('binding-modal-close').addEventListener('click', closeBindingModal);
    document.getElementById('binding-modal-cancel').addEventListener('click', closeBindingModal);
    document.getElementById('binding-modal-save').addEventListener('click', saveBinding);

    const mongoWizardClose = document.getElementById('mongo-wizard-modal-close');
    const mongoWizardCancel = document.getElementById('mongo-wizard-cancel');
    const mongoWizardInspectConnection = document.getElementById('mongo-wizard-inspect-connection');
    const mongoWizardInspectDatabase = document.getElementById('mongo-wizard-inspect-database');
    const mongoWizardGenerate = document.getElementById('mongo-wizard-generate');
    const mongoWizardSelectAll = document.getElementById('mongo-wizard-select-all');
    const mongoWizardNext = document.getElementById('mongo-wizard-next');
    const mongoWizardBack = document.getElementById('mongo-wizard-back');

    if (mongoWizardClose) {
        mongoWizardClose.addEventListener('click', closeMongoWizardModal);
    }
    if (mongoWizardCancel) {
        mongoWizardCancel.addEventListener('click', closeMongoWizardModal);
    }
    if (mongoWizardInspectConnection) {
        mongoWizardInspectConnection.addEventListener('click', inspectMongoWizardConnection);
    }
    if (mongoWizardInspectDatabase) {
        mongoWizardInspectDatabase.addEventListener('click', inspectMongoWizardDatabase);
    }
    if (mongoWizardGenerate) {
        mongoWizardGenerate.addEventListener('click', generateMongoWizardReflections);
    }
    if (mongoWizardNext) {
        mongoWizardNext.addEventListener('click', moveMongoWizardStepForward);
    }
    if (mongoWizardBack) {
        mongoWizardBack.addEventListener('click', moveMongoWizardStepBack);
    }
    if (mongoWizardSelectAll) {
        mongoWizardSelectAll.addEventListener('click', toggleMongoWizardSelectAll);
    }
    const mongoWizardUri = document.getElementById('mongo-wizard-uri');
    const mongoWizardUsername = document.getElementById('mongo-wizard-username');
    const mongoWizardPassword = document.getElementById('mongo-wizard-password');
    const mongoWizardAuthDb = document.getElementById('mongo-wizard-auth-db');
    const mongoWizardTls = document.getElementById('mongo-wizard-tls');
    const mongoWizardDatabase = document.getElementById('mongo-wizard-database');

    [mongoWizardUri, mongoWizardUsername, mongoWizardPassword, mongoWizardAuthDb].forEach(function (input) {
        if (!input) {
            return;
        }
        input.addEventListener('input', function () {
            mongoWizardConnectionValidated = false;
            mongoWizardCollectionsLoaded = false;
        });
    });
    if (mongoWizardTls) {
        mongoWizardTls.addEventListener('change', function () {
            mongoWizardConnectionValidated = false;
            mongoWizardCollectionsLoaded = false;
        });
    }
    if (mongoWizardDatabase) {
        mongoWizardDatabase.addEventListener('change', function () {
            mongoWizardCollectionsLoaded = false;
        });
    }

    document.getElementById('reflection-publish-modal-close').addEventListener('click', closeReflectionPublishModal);
    document.getElementById('reflection-publish-modal-cancel').addEventListener('click', closeReflectionPublishModal);
    document.getElementById('reflection-publish-submit-btn').addEventListener('click', submitReflectionPublishFromModal);

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeGroupModal();
            closeReflectionModal();
            closeMongoToolModal();
            closeRecordToolModal();
            closeBindingModal();
            closeReflectionPublishModal();
            closeMongoWizardModal();
        }
    });
}

async function loadAll() {
    await Promise.all([loadGroups(), loadReflections(), loadOAuthTemplates(), loadBindingContracts()]);
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

async function loadBindingContracts() {
    try {
        const response = await fetch('/api/binding-contracts');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        bindingContracts = await response.json();
    } catch (_error) {
        bindingContracts = [];
    }
    populateGroupBindingContractSelect();
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
        const version = resolveGroupArtifactVersion(group);
        const artifactStatus = group.artifactStatus || 'SNAPSHOT';
        const isSnapshot = artifactStatus === 'SNAPSHOT';
        const canDelete = artifactStatus === 'SNAPSHOT' || artifactStatus === 'SUBMITTED' || artifactStatus === 'REJECTED';
        const contributionActions = [];
        contributionActions.push('<button type="button" class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200" data-action="check-deps" data-id="' + escapeHtml(group.uuid) + '" title="Dependency pre-check"><i class="fa-solid fa-list-check"></i></button>');
        if (isSnapshot) {
            contributionActions.push('<button type="button" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 contrib-action" data-action="publish-group" data-id="' + escapeHtml(group.uuid) + '" data-default-title="Publish to staging via PR" title="Publish to staging via PR" disabled><i class="fa-solid fa-cloud-arrow-up"></i></button>');
        } else {
            if (artifactStatus === 'SUBMITTED') {
                contributionActions.push('<button type="button" class="rounded-md border border-blue-500/40 px-2 py-1 text-xs text-blue-300 contrib-action" data-action="refresh-status" data-id="' + escapeHtml(group.uuid) + '" data-default-title="Refresh status from GitHub" title="Refresh status from GitHub" disabled><i class="fa-solid fa-rotate-right"></i></button>');
            }
            contributionActions.push('<button type="button" class="rounded-md border border-amber-500/40 px-2 py-1 text-xs text-amber-300 contrib-action" data-action="snapshot-group" data-id="' + escapeHtml(group.uuid) + '" data-default-title="Create SNAPSHOT clone from immutable version" title="Create SNAPSHOT clone from immutable version" disabled><i class="fa-solid fa-code-branch"></i></button>');
        }

        const deleteAction = canDelete
            ? '<button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300" data-action="delete-group" data-id="' + escapeHtml(group.uuid) + '" title="Delete group"><i class="fa-solid fa-trash"></i></button>'
            : '<button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-500 cursor-not-allowed" title="Only SNAPSHOT, SUBMITTED, or REJECTED groups can be deleted" disabled><i class="fa-solid fa-trash"></i></button>';

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
            + '<td class="px-3 py-3 text-xs font-mono text-zinc-400">' + escapeHtml(version) + '</td>'
            + '<td class="px-3 py-3"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-2 py-0.5 text-xs text-zinc-300">' + escapeHtml(artifactStatus) + '</span></td>'
            + '<td class="px-3 py-3 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + (isReadOnly ? '' : ''
                + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200" data-action="edit-group" data-id="' + escapeHtml(group.uuid) + '" title="Edit group"><i class="fa-solid fa-pen"></i></button>'
                + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300" data-action="export-group" data-id="' + escapeHtml(group.uuid) + '" title="Export group"><i class="fa-solid fa-file-export"></i></button>'
                + contributionActions.join('')
                + '    ' + deleteAction)
            + '  </div>'
            + '</td>';
        body.appendChild(row);
    });

    bindDynamicTableEvents(body);
    applyReflectionContributionActionState(githubConnection && typeof githubConnection.isConnected === 'function'
        ? githubConnection.isConnected()
        : false);
}

function reflectionsForGroupUuid(groupUuid) {
    return (reflections || []).filter(function (reflection) {
        return reflection.groupUuid === groupUuid;
    });
}

function renderReflectionPillsHtml(groupReflections, groupUuid) {
    const groupType = resolveGroupType(groupUuid);
    const isMongoGroup = groupType === 'MONGO';

    if (!groupReflections || groupReflections.length === 0) {
        if (isReadOnly) {
            return '<span class="text-xs text-zinc-500">No reflections</span>';
        }
        if (isMongoGroup) {
            return isReadOnly
                ? '<span class="text-xs text-zinc-500">Managed by Mongo wizard/custom Mongo tools.</span>'
                : '<button class="rounded-full border border-dashed border-zinc-600 px-3 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" data-action="add-tool" data-group-id="' + escapeHtml(groupUuid) + '"><i class="fa-solid fa-plus mr-1"></i>Add tool</button>';
        }
        return ''
            + '<button class="rounded-full border border-dashed border-zinc-600 px-3 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" '
            + 'data-action="add-tool" data-group-id="' + escapeHtml(groupUuid) + '">'
            + '<i class="fa-solid fa-plus mr-1"></i>Add first tool</button>';
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
        : (isMongoGroup
            ? '<button class="mb-1 inline-flex items-center rounded-full border border-dashed border-zinc-600 px-2.5 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" data-action="add-tool" data-group-id="' + escapeHtml(groupUuid) + '" title="Add tool to group"><i class="fa-solid fa-plus mr-1"></i>Add tool</button>'
            : '<button class="mb-1 inline-flex items-center rounded-full border border-dashed border-zinc-600 px-2.5 py-1 text-xs text-zinc-300 transition-colors hover:border-[#fdaa02] hover:text-[#fdaa02]" data-action="add-tool" data-group-id="' + escapeHtml(groupUuid) + '" title="Add tool to group"><i class="fa-solid fa-plus mr-1"></i>Add tool</button>');

    return pills + addButton;
}

function resolveGroupType(groupUuid) {
    const entry = findGroupEntry(groupUuid);
    const group = entry ? (entry.group || entry) : null;
    return String((group && group.type) ? group.type : 'REST').toUpperCase();
}

function renderBindingPillsHtml(bindings, groupUuid) {
    const groupType = resolveGroupType(groupUuid);
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
        const bindingUuid = escapeHtml(binding.uuid || '');
        const baseUrl = escapeHtml(binding.baseUrl || '');
        const pillTitle = baseUrl ? ('Binding: ' + bindingName + ' (' + baseUrl + ')') : ('Binding: ' + bindingName);
        return ''
            + '<span class="mr-1 mb-1 inline-flex items-center gap-1 rounded-full border border-zinc-700 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200">'
            + '  <button class="inline-flex items-center gap-1 transition-colors hover:text-cyan-300" data-action="edit-binding" data-group-id="' + escapeHtml(groupUuid) + '" data-name="' + bindingName + '" title="' + escapeHtml(pillTitle) + '">'
            + '    <span class="font-mono">' + bindingName + '</span>'
            + '  </button>'
            + '  <button class="px-1 py-0.5 text-[10px] text-emerald-300 transition-colors hover:text-emerald-200" data-action="explore-binding" data-group-id="' + escapeHtml(groupUuid) + '" data-name="' + bindingName + '" data-binding-uuid="' + bindingUuid + '" data-group-type="' + escapeHtml(groupType) + '" title="Open Reflection Explorer with this binding"><i class="fa-solid fa-flask-vial"></i></button>'
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
    root.querySelectorAll('button[data-action="publish-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            publishReflectionGroupContribution(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="check-deps"]').forEach(function (button) {
        button.addEventListener('click', function () {
            checkReflectionGroupContributionDependencies(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="refresh-status"]').forEach(function (button) {
        button.addEventListener('click', function () {
            refreshReflectionGroupContributionStatus(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="snapshot-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            createReflectionGroupSnapshotContribution(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="export-group"]').forEach(function (button) {
        button.addEventListener('click', function () {
            exportGroup(button.getAttribute('data-id'));
        });
    });
    root.querySelectorAll('button[data-action="add-tool"]').forEach(function (button) {
        button.addEventListener('click', function () {
            const groupUuid = button.getAttribute('data-group-id');
            const groupType = resolveGroupType(groupUuid);
            if (groupType === 'MONGO') {
                openCreateMongoToolModal(groupUuid);
                return;
            }
            if (groupType === 'RECORD') {
                openCreateRecordToolModal(groupUuid);
                return;
            }
            openCreateReflectionModal(groupUuid);
        });
    });

    // Legacy action names kept for compatibility with older rendered rows.
    root.querySelectorAll('button[data-action="add-reflection"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCreateReflectionModal(button.getAttribute('data-group-id'));
        });
    });
    root.querySelectorAll('button[data-action="add-mongo-tool"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openCreateMongoToolModal(button.getAttribute('data-group-id'));
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
    root.querySelectorAll('button[data-action="explore-binding"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openReflectionExplorer(
                button.getAttribute('data-group-id'),
                button.getAttribute('data-name'),
                button.getAttribute('data-binding-uuid'));
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

function openReflectionExplorer(groupUuid, bindingName, bindingUuid) {
    const params = new URLSearchParams();
    const currentParams = new URLSearchParams(window.location.search || '');
    const sessionUuid = currentParams.get('sessionUuid');
    if (groupUuid) {
        params.set('groupUuid', groupUuid);
    }
    if (bindingName) {
        params.set('bindingName', bindingName);
    }
    if (bindingUuid) {
        params.set('bindingUuid', bindingUuid);
    }
    if (sessionUuid) {
        params.set('sessionUuid', sessionUuid);
    }
    const suffix = params.toString();
    window.location.href = '/data-inspector' + (suffix ? ('?' + suffix) : '');
}

function initGitHubConnection() {
    if (!window.VorkGitHubConnection || typeof window.VorkGitHubConnection.init !== 'function') {
        return;
    }
    githubConnection = window.VorkGitHubConnection.init({
        connectButtonId: 'github-connect-btn',
        statusLabelId: 'github-connection-status',
        alertFn: showAlert,
        onStatusChange: function (status) {
            applyReflectionContributionActionState(!!(status && status.connected));
        }
    });
}

function applyReflectionContributionActionState(isConnected) {
    const reason = 'Connect GitHub before publishing or creating snapshots.';
    document.querySelectorAll('.contrib-action').forEach(function (btn) {
        if (!(btn instanceof HTMLButtonElement)) {
            return;
        }
        btn.disabled = !isConnected;
        if (!isConnected) {
            btn.setAttribute('title', reason);
        } else {
            const defaultTitle = btn.getAttribute('data-default-title');
            if (defaultTitle) {
                btn.setAttribute('title', defaultTitle);
            }
        }
    });
}

function resolveGroupArtifactVersion(group) {
    const explicit = (group && (group.artifactVersion || group.version) ? String(group.artifactVersion || group.version) : '').trim();
    if (!explicit) return 'SNAPSHOT';
    if (explicit.toUpperCase() === 'SNAPSHOT') return 'SNAPSHOT';
    if (/^[0-9]+\.[0-9]+$/.test(explicit)) return explicit;
    if (/^[0-9]+$/.test(explicit)) return 'SNAPSHOT';
    return explicit;
}

async function publishReflectionGroupContribution(id) {
    if (!id) {
        showAlert('Group id is required for publish.', 'warning');
        return;
    }

    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndGate === 'function') {
        const ready = await window.VorkDependencyPrecheck.runAndGate('reflections', id, 'Reflection group', showAlert);
        if (!ready) {
            return;
        }
    }

    document.getElementById('reflection-publish-id').value = id;
    clearReflectionPublishModalAlert();
    setReflectionPublishLoading(true);
    showModal('reflection-publish-modal');

    try {
        const draftRes = await fetch('/api/contributions/reflections/' + encodeURIComponent(id) + '/publish-draft', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify({})
        });
        const draftResult = await parseJson(draftRes);
        if (!draftRes.ok) {
            showReflectionPublishModalAlert(draftResult.error || 'Failed to prepare publish draft.', 'danger');
            setReflectionPublishLoading(false);
            return;
        }

        const draft = draftResult.draft || {};

        document.getElementById('reflection-publish-version').value = String(draft.version || '').trim();
        document.getElementById('reflection-publish-pr-title').value = String(draft.prTitle || '').trim();
        document.getElementById('reflection-publish-change-summary').value = String(draft.changeSummary || '').trim();
        document.getElementById('reflection-publish-commit-message').value = String(draft.commitMessage || '').trim();
        document.getElementById('reflection-publish-pr-body').value = String(draft.prBody || '').trim();
        document.getElementById('reflection-publish-release-notes').value = String(draft.releaseNotes || '').trim();
        document.getElementById('reflection-publish-reviewer-hints').value = String(draft.reviewerHints || '').trim();
        document.getElementById('reflection-publish-breaking-change').checked = !!draft.breakingChange;

        if (draft.latestVersion) {
            showReflectionPublishModalAlert('Latest in staging: ' + draft.latestVersion + '. Draft generated and ready to edit.', 'success');
        }

        setReflectionPublishLoading(false);
    } catch (_error) {
        showReflectionPublishModalAlert('Network error during draft generation.', 'danger');
        setReflectionPublishLoading(false);
    }
}

async function checkReflectionGroupContributionDependencies(id) {
    if (!id) {
        showAlert('Reflection group id is required for dependency pre-check.', 'warning');
        return;
    }
    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndDisplay === 'function') {
        await window.VorkDependencyPrecheck.runAndDisplay('reflections', id, 'Reflection group', showAlert);
        return;
    }
    showAlert('Dependency pre-check helper is not available on this page.', 'warning');
}

async function submitReflectionPublishFromModal() {
    const id = (document.getElementById('reflection-publish-id').value || '').trim();
    const version = (document.getElementById('reflection-publish-version').value || '').trim();
    const prTitle = (document.getElementById('reflection-publish-pr-title').value || '').trim();
    const changeSummary = (document.getElementById('reflection-publish-change-summary').value || '').trim();
    const commitMessage = (document.getElementById('reflection-publish-commit-message').value || '').trim();
    const prBody = (document.getElementById('reflection-publish-pr-body').value || '').trim();
    const releaseNotes = (document.getElementById('reflection-publish-release-notes').value || '').trim();
    const reviewerHints = (document.getElementById('reflection-publish-reviewer-hints').value || '').trim();
    const breakingChange = !!document.getElementById('reflection-publish-breaking-change').checked;

    if (!id) {
        showReflectionPublishModalAlert('Group id is missing for publish.', 'danger');
        return;
    }
    if (!/^[0-9]+\.[0-9]+$/.test(version) || version.toUpperCase() === 'SNAPSHOT') {
        showReflectionPublishModalAlert('Version must follow major.minor and cannot be SNAPSHOT.', 'danger');
        return;
    }
    if (!prTitle) {
        showReflectionPublishModalAlert('PR title is required.', 'danger');
        return;
    }
    if (!changeSummary) {
        showReflectionPublishModalAlert('Change summary is required.', 'danger');
        return;
    }

    setReflectionPublishLoading(true, 'Creating PR...');
    clearReflectionPublishModalAlert();

    try {
        const publishRes = await fetch('/api/contributions/reflections/' + encodeURIComponent(id) + '/publish', {
            method: 'POST',
            headers: buildJsonHeaders(),
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
        const publishResult = await parseJson(publishRes);
        if (!publishRes.ok) {
            showReflectionPublishModalAlert(publishResult.error || 'Failed to publish reflection group.', 'danger');
            setReflectionPublishLoading(false);
            return;
        }

        const pullRequest = publishResult.pullRequest || {};
        showAlert('Published. PR: ' + (pullRequest.url || 'created'), 'success');
        closeReflectionPublishModal();
        await loadAll();
    } catch (_error) {
        showReflectionPublishModalAlert('Network error during publish.', 'danger');
        setReflectionPublishLoading(false);
    }
}

function setReflectionPublishLoading(isLoading, loadingLabel) {
    const fields = [
        'reflection-publish-version',
        'reflection-publish-pr-title',
        'reflection-publish-change-summary',
        'reflection-publish-commit-message',
        'reflection-publish-pr-body',
        'reflection-publish-release-notes',
        'reflection-publish-reviewer-hints',
        'reflection-publish-breaking-change'
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

    const submitBtn = document.getElementById('reflection-publish-submit-btn');
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

async function createReflectionGroupSnapshotContribution(id) {
    if (!id) {
        showAlert('Group id is required for snapshot.', 'warning');
        return;
    }
    if (!window.confirm('Create a SNAPSHOT clone from this immutable reflection group?')) {
        return;
    }
    try {
        const response = await fetch('/api/contributions/reflections/' + encodeURIComponent(id) + '/snapshot', {
            method: 'POST',
            headers: buildCsrfHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to create snapshot.', 'danger');
            return;
        }
        showAlert('SNAPSHOT clone created.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error creating snapshot.', 'danger');
    }
}

async function refreshReflectionGroupContributionStatus(id) {
    if (!id) {
        showAlert('Group id is required to refresh status.', 'warning');
        return;
    }
    try {
        const response = await fetch('/api/contributions/promotions/reconcile', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify({})
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to refresh contribution status.', 'danger');
            return;
        }
        const summary = result.summary || {};
        showAlert('Status refresh complete. Reflection groups promoted to STAGED: ' + (summary.reflectionsPromotedToStaged || 0) + '.', 'success');
        await loadAll();
    } catch (_error) {
        showAlert('Network error refreshing contribution status.', 'danger');
    }
}

async function openCreateGroupModal() {
    await Promise.all([loadOAuthTemplates(), loadBindingContracts()]);
    document.getElementById('group-modal-title').textContent = 'New REST/OAuth Reflection Group';
    document.getElementById('group-id').value = '';
    document.getElementById('group-kind').value = 'REST';
    document.getElementById('group-name').value = '';
    document.getElementById('group-group-id').value = '';
    document.getElementById('group-artifact-id').value = '';
    document.getElementById('group-group-id').disabled = false;
    document.getElementById('group-artifact-id').disabled = false;
    document.getElementById('group-description').value = '';
    document.getElementById('group-base-url').value = '';
    document.getElementById('group-url-override-enabled').checked = true;
    document.getElementById('group-auth-mode').value = 'OAUTH';
    populateGroupOAuthTemplateSelect('');
    populateGroupBindingContractSelect([]);
    modalGroupBindingParameters = [];
    modalGroupBindingSecrets = [];
    renderGroupBindingParameters();
    renderGroupBindingSecrets();
    setGroupModalTab('general');
    syncGroupAuthVisibility();
    clearGroupModalAlert();
    if (!oauthTemplates || oauthTemplates.length === 0) {
        showGroupModalAlert('No OAuth templates are available. Create/import an OAuth template first, then select it here.', 'warning');
    }
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
    document.getElementById('group-kind').value = group.type || 'REST';
    document.getElementById('group-name').value = group.name || '';
    document.getElementById('group-group-id').value = group.groupId || '';
    document.getElementById('group-artifact-id').value = group.artifactId || '';
    document.getElementById('group-group-id').disabled = true;
    document.getElementById('group-artifact-id').disabled = true;
    document.getElementById('group-description').value = group.description || '';
    document.getElementById('group-base-url').value = group.baseUrl || '';
    document.getElementById('group-url-override-enabled').checked = group.urlOverrideEnabled !== false;
    document.getElementById('group-auth-mode').value = group.authenticationMode || 'NONE';
    populateGroupOAuthTemplateSelect(group.oauthTemplateId || '');
    populateGroupBindingContractSelect(group.bindingContractUuids || []);
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
    setGroupModalTab('general');
    syncGroupAuthVisibility();
    clearGroupModalAlert();
    showModal('group-modal');
}

function openMongoWizardModal() {
    clearMongoWizardAlert();
    mongoWizardCollections = [];
    mongoWizardStep = 1;
    mongoWizardConnectionValidated = false;
    mongoWizardCollectionsLoaded = false;
    document.getElementById('mongo-wizard-uri').value = '';
    document.getElementById('mongo-wizard-username').value = '';
    document.getElementById('mongo-wizard-password').value = '';
    document.getElementById('mongo-wizard-auth-db').value = '';
    document.getElementById('mongo-wizard-tls').checked = false;
    document.getElementById('mongo-wizard-group-name').value = '';
    document.getElementById('mongo-wizard-group-description').value = '';
    document.getElementById('mongo-wizard-group-id').value = '';
    document.getElementById('mongo-wizard-artifact-id').value = '';
    document.getElementById('mongo-wizard-database').innerHTML = '';
    document.getElementById('mongo-wizard-collections').innerHTML = '<p class="text-xs text-zinc-500">Inspect a database to list collections.</p>';
    syncMongoWizardStepUi();
    showModal('mongo-wizard-modal');
}

function closeMongoWizardModal() {
    hideModal('mongo-wizard-modal');
}

function syncMongoWizardStepUi() {
    const step1 = document.getElementById('mongo-wizard-step-1');
    const step2 = document.getElementById('mongo-wizard-step-2');
    const step3 = document.getElementById('mongo-wizard-step-3');
    const backBtn = document.getElementById('mongo-wizard-back');
    const nextBtn = document.getElementById('mongo-wizard-next');
    const generateBtn = document.getElementById('mongo-wizard-generate');
    const indicator = document.getElementById('mongo-wizard-step-indicator');
    const stepTitle = document.getElementById('mongo-wizard-step-title');
    const chips = [
        document.getElementById('mongo-step-chip-1'),
        document.getElementById('mongo-step-chip-2'),
        document.getElementById('mongo-step-chip-3')
    ];

    if (step1) step1.classList.toggle('hidden', mongoWizardStep !== 1);
    if (step2) step2.classList.toggle('hidden', mongoWizardStep !== 2);
    if (step3) step3.classList.toggle('hidden', mongoWizardStep !== 3);

    if (backBtn) backBtn.classList.toggle('hidden', mongoWizardStep === 1);
    if (nextBtn) nextBtn.classList.toggle('hidden', mongoWizardStep === 3);
    if (generateBtn) generateBtn.classList.toggle('hidden', mongoWizardStep !== 3);

    if (indicator) {
        indicator.textContent = 'Step ' + mongoWizardStep + ' of 3';
    }
    if (stepTitle) {
        stepTitle.textContent = mongoWizardStep === 1
            ? 'Connection'
            : (mongoWizardStep === 2 ? 'Database' : 'Collections');
    }

    chips.forEach(function (chip, index) {
        if (!chip) {
            return;
        }
        const stepNumber = index + 1;
        chip.classList.remove(
            'border-cyan-500/50', 'bg-cyan-500/10', 'text-cyan-300',
            'border-emerald-500/40', 'bg-emerald-500/10', 'text-emerald-300',
            'border-zinc-700', 'bg-zinc-950', 'text-zinc-400'
        );

        if (stepNumber === mongoWizardStep) {
            chip.classList.add('border-cyan-500/50', 'bg-cyan-500/10', 'text-cyan-300');
            return;
        }
        if (stepNumber < mongoWizardStep) {
            chip.classList.add('border-emerald-500/40', 'bg-emerald-500/10', 'text-emerald-300');
            return;
        }
        chip.classList.add('border-zinc-700', 'bg-zinc-950', 'text-zinc-400');
    });
}

function moveMongoWizardStepForward() {
    clearMongoWizardAlert();
    if (mongoWizardStep === 1) {
        const connectionUri = document.getElementById('mongo-wizard-uri').value.trim();
        const groupId = document.getElementById('mongo-wizard-group-id').value.trim();
        const artifactId = document.getElementById('mongo-wizard-artifact-id').value.trim();
        if (!connectionUri) {
            showMongoWizardAlert('Connection URI is required.', 'warning');
            return;
        }
        if (!isValidIdentity(groupId)) {
            showMongoWizardAlert('Group ID must be alphanumeric and 3-64 characters.', 'warning');
            return;
        }
        if (!isValidIdentity(artifactId)) {
            showMongoWizardAlert('Artifact ID must be alphanumeric and 3-64 characters.', 'warning');
            return;
        }
        if (!mongoWizardConnectionValidated) {
            showMongoWizardAlert('Validate connection before proceeding to the next step.', 'warning');
            return;
        }
        mongoWizardStep = 2;
        syncMongoWizardStepUi();
        return;
    }

    if (mongoWizardStep === 2) {
        const database = document.getElementById('mongo-wizard-database').value;
        if (!database) {
            showMongoWizardAlert('Select a database before proceeding.', 'warning');
            return;
        }
        if (!mongoWizardCollectionsLoaded || !mongoWizardCollections || mongoWizardCollections.length === 0) {
            showMongoWizardAlert('Load collections before proceeding to the final step.', 'warning');
            return;
        }
        mongoWizardStep = 3;
        syncMongoWizardStepUi();
    }
}

function moveMongoWizardStepBack() {
    clearMongoWizardAlert();
    mongoWizardStep = Math.max(1, mongoWizardStep - 1);
    syncMongoWizardStepUi();
}

function mongoWizardConnectionPayload() {
    return {
        connectionUri: document.getElementById('mongo-wizard-uri').value.trim(),
        username: document.getElementById('mongo-wizard-username').value.trim(),
        password: document.getElementById('mongo-wizard-password').value,
        authDatabase: document.getElementById('mongo-wizard-auth-db').value.trim(),
        tlsEnabled: document.getElementById('mongo-wizard-tls').checked
    };
}

async function inspectMongoWizardConnection() {
    clearMongoWizardAlert();
    const payload = mongoWizardConnectionPayload();
    if (!payload.connectionUri) {
        showMongoWizardAlert('Connection URI is required.', 'warning');
        return;
    }

    try {
        const response = await fetch('/api/reflection-groups/mongo/inspect-connection', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showMongoWizardAlert(result.error || 'Failed to inspect Mongo connection.', 'danger');
            return;
        }

        const databaseSelect = document.getElementById('mongo-wizard-database');
        databaseSelect.innerHTML = '';
        (result.databases || []).forEach(function (name) {
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            databaseSelect.appendChild(option);
        });

        if ((result.databases || []).length === 0) {
            mongoWizardConnectionValidated = false;
            showMongoWizardAlert('Connection successful but no databases were returned.', 'warning');
            return;
        }

        mongoWizardConnectionValidated = true;
        mongoWizardCollectionsLoaded = false;
        mongoWizardCollections = [];
        document.getElementById('mongo-wizard-collections').innerHTML = '<p class="text-xs text-zinc-500">Load collections for the selected database.</p>';
        showMongoWizardAlert('Connection validated. Continue to step 2.', 'success');
    } catch (_error) {
        mongoWizardConnectionValidated = false;
        showMongoWizardAlert('Network error inspecting Mongo connection.', 'danger');
    }
}

async function inspectMongoWizardDatabase() {
    clearMongoWizardAlert();
    const payload = mongoWizardConnectionPayload();
    payload.database = document.getElementById('mongo-wizard-database').value;
    payload.sampleSize = 20;

    if (!payload.connectionUri) {
        showMongoWizardAlert('Connection URI is required.', 'warning');
        return;
    }
    if (!payload.database) {
        showMongoWizardAlert('Select a database first.', 'warning');
        return;
    }

    try {
        const response = await fetch('/api/reflection-groups/mongo/inspect-database', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showMongoWizardAlert(result.error || 'Failed to inspect Mongo database.', 'danger');
            return;
        }

        mongoWizardCollections = (result.collections || []).map(function (collection) {
            return {
                name: collection.name,
                estimatedCount: collection.estimatedCount || 0,
                inferredSchema: collection.inferredSchema || ''
            };
        });
        mongoWizardCollectionsLoaded = true;
        renderMongoWizardCollections();
        showMongoWizardAlert('Collections loaded. Continue to step 3 and select one or more collections.', 'success');
    } catch (_error) {
        mongoWizardCollectionsLoaded = false;
        showMongoWizardAlert('Network error inspecting Mongo database.', 'danger');
    }
}

function renderMongoWizardCollections() {
    const container = document.getElementById('mongo-wizard-collections');
    if (!container) {
        return;
    }
    container.innerHTML = '';
    if (!mongoWizardCollections || mongoWizardCollections.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No collections found.</p>';
        return;
    }

    mongoWizardCollections.forEach(function (collection, index) {
        const row = document.createElement('label');
        row.className = 'mb-2 flex items-center justify-between rounded-md border border-zinc-800 px-2 py-1';
        row.innerHTML = ''
            + '<span class="inline-flex items-center gap-2">'
            + '  <input type="checkbox" class="mongo-wizard-collection-check h-4 w-4 rounded border-zinc-700 bg-zinc-950 text-[#fdaa02]" data-index="' + index + '">'
            + '  <span>' + escapeHtml(collection.name) + '</span>'
            + '</span>'
            + '<span class="text-xs text-zinc-500">~' + escapeHtml(String(collection.estimatedCount)) + ' docs</span>';
        container.appendChild(row);
    });
}

function toggleMongoWizardSelectAll() {
    const checks = Array.from(document.querySelectorAll('.mongo-wizard-collection-check'));
    if (checks.length === 0) {
        return;
    }
    const allChecked = checks.every(function (input) { return input.checked; });
    checks.forEach(function (input) {
        input.checked = !allChecked;
    });
}

async function generateMongoWizardReflections() {
    clearMongoWizardAlert();
    const payload = mongoWizardConnectionPayload();
    payload.database = document.getElementById('mongo-wizard-database').value;
    payload.sampleSize = 20;
    payload.groupName = document.getElementById('mongo-wizard-group-name').value.trim();
    payload.groupDescription = document.getElementById('mongo-wizard-group-description').value.trim();
    payload.groupId = document.getElementById('mongo-wizard-group-id').value.trim();
    payload.artifactId = document.getElementById('mongo-wizard-artifact-id').value.trim();

    const selectedCollections = Array.from(document.querySelectorAll('.mongo-wizard-collection-check'))
        .filter(function (input) { return input.checked; })
        .map(function (input) {
            const index = Number(input.getAttribute('data-index'));
            return mongoWizardCollections[index] ? mongoWizardCollections[index].name : '';
        })
        .filter(function (name) { return !!name; });

    payload.collections = selectedCollections;

    if (!payload.connectionUri) {
        showMongoWizardAlert('Connection URI is required.', 'warning');
        return;
    }
    if (!isValidIdentity(payload.groupId)) {
        showMongoWizardAlert('Group ID must be alphanumeric and 3-64 characters.', 'warning');
        return;
    }
    if (!isValidIdentity(payload.artifactId)) {
        showMongoWizardAlert('Artifact ID must be alphanumeric and 3-64 characters.', 'warning');
        return;
    }
    if (!payload.database) {
        showMongoWizardAlert('Select a database first.', 'warning');
        return;
    }
    if (selectedCollections.length === 0) {
        showMongoWizardAlert('Select at least one collection.', 'warning');
        return;
    }

    try {
        const response = await fetch('/api/reflection-groups/mongo/wizard-generate', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showMongoWizardAlert(result.error || 'Failed to generate Mongo reflections.', 'danger');
            return;
        }
        closeMongoWizardModal();
        showAlert('Mongo reflections generated for ' + selectedCollections.length + ' collection(s).', 'success');
        await loadAll();
    } catch (_error) {
        showMongoWizardAlert('Network error generating Mongo reflections.', 'danger');
    }
}

function showMongoWizardAlert(message, level) {
    const alert = document.getElementById('mongo-wizard-alert');
    if (!alert) {
        return;
    }
    alert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(level) + '">' + escapeHtml(message || '') + '</div>';
}

function clearMongoWizardAlert() {
    const alert = document.getElementById('mongo-wizard-alert');
    if (alert) {
        alert.innerHTML = '';
    }
}

async function saveGroup() {
    const groupType = (document.getElementById('group-kind').value || 'REST').trim().toUpperCase();
    const authenticationMode = document.getElementById('group-auth-mode').value;
    const oauthTemplateId = document.getElementById('group-oauth-template').value;
    const groupId = document.getElementById('group-id').value.trim();
    const vidGroupId = document.getElementById('group-group-id').value.trim();
    const artifactId = document.getElementById('group-artifact-id').value.trim();
    const payload = {
        name: document.getElementById('group-name').value.trim(),
        description: document.getElementById('group-description').value.trim(),
        type: groupType,
        baseUrl: groupType === 'MONGO' ? '' : document.getElementById('group-base-url').value.trim(),
        urlOverrideEnabled: groupType === 'MONGO' ? false : document.getElementById('group-url-override-enabled').checked,
        bindingParameters: sanitizeBindingParameterSchema(modalGroupBindingParameters),
        bindingSecrets: sanitizeBindingSecretSchema(modalGroupBindingSecrets),
        authenticationMode: authenticationMode,
        oauthTemplateId: authenticationMode === 'OAUTH' ? oauthTemplateId : '',
        bindingContractUuids: selectedGroupBindingContractIds(),
        groupId: vidGroupId,
        artifactId: artifactId
    };

    if (!payload.name) {
        showGroupModalAlert('Group name is required.', 'warning');
        return;
    }
    if (!payload.groupId || !/^[A-Za-z0-9]{3,64}$/.test(payload.groupId)) {
        showGroupModalAlert('Group ID must be alphanumeric and 3-64 characters.', 'warning');
        return;
    }
    if (!payload.artifactId || !/^[A-Za-z0-9]{3,64}$/.test(payload.artifactId)) {
        showGroupModalAlert('Artifact ID must be alphanumeric and 3-64 characters.', 'warning');
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
    syncGroupAuthVisibility();
}

function populateGroupBindingContractSelect(selectedContractIds) {
    const selected = new Set((selectedContractIds || []).map(function (id) {
        return String(id || '').trim();
    }).filter(function (id) { return !!id; }));

    modalSelectedBindingContractUuids = (bindingContracts || [])
        .map(function (contract) { return String(contract.uuid || '').trim(); })
        .filter(function (id) { return selected.has(id); });

    const input = document.getElementById('group-binding-contract-search');
    const results = document.getElementById('group-binding-contract-results');
    if (input) {
        input.value = '';
    }
    if (results) {
        results.classList.add('hidden');
    }

    renderGroupBindingContractPills();
    renderGroupBindingContractSearchResults('');
}

function selectedGroupBindingContractIds() {
    return (modalSelectedBindingContractUuids || [])
        .map(function (id) { return String(id || '').trim(); })
        .filter(function (id) { return !!id; });
}

function setupGroupBindingContractSearch() {
    const input = document.getElementById('group-binding-contract-search');
    const results = document.getElementById('group-binding-contract-results');
    if (!input || !results) {
        return;
    }

    input.addEventListener('input', function () {
        renderGroupBindingContractSearchResults(input.value || '');
    });
    input.addEventListener('focus', function () {
        renderGroupBindingContractSearchResults(input.value || '');
    });

    document.addEventListener('click', function (event) {
        const target = event.target;
        if (!(target instanceof Element)) {
            return;
        }
        if (input.contains(target) || results.contains(target)) {
            return;
        }
        results.classList.add('hidden');
    });
}

function renderGroupBindingContractSearchResults(query) {
    const input = document.getElementById('group-binding-contract-search');
    const results = document.getElementById('group-binding-contract-results');
    if (!input || !results) {
        return;
    }

    const term = String(query || '').trim().toLowerCase();
    const selectedSet = new Set(selectedGroupBindingContractIds());
    const available = (bindingContracts || []).filter(function (contract) {
        const uuid = String(contract.uuid || '').trim();
        if (!uuid || selectedSet.has(uuid)) {
            return false;
        }
        if (!term) {
            return true;
        }
        const name = String(contract.name || '').toLowerCase();
        const id = String(contract.id || '').toLowerCase();
        const groupId = String(contract.groupId || '').toLowerCase();
        const artifactId = String(contract.artifactId || '').toLowerCase();
        const tools = Array.isArray(contract.tools)
            ? contract.tools.map(function (tool) {
                return [tool.id, tool.name, tool.description].join(' ').toLowerCase();
            }).join(' ')
            : '';
        return name.includes(term)
            || id.includes(term)
            || groupId.includes(term)
            || artifactId.includes(term)
            || tools.includes(term);
    }).slice(0, 12);

    results.innerHTML = '';
    if (available.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'skills-search-item text-zinc-500';
        empty.textContent = term ? 'No matching contracts.' : 'No more contracts to add.';
        results.appendChild(empty);
        results.classList.remove('hidden');
        return;
    }

    available.forEach(function (contract) {
        const item = document.createElement('div');
        item.className = 'skills-search-item';
        const toolCount = Array.isArray(contract.tools) ? contract.tools.length : 0;
        const status = contract.artifactStatus ? (' [' + contract.artifactStatus + ']') : '';
        item.textContent = (contract.name || contract.uuid || 'Unnamed contract')
            + ' (' + toolCount + ' tool' + (toolCount === 1 ? '' : 's') + ')' + status;
        item.title = [contract.uuid, contract.groupId, contract.artifactId].filter(Boolean).join(' / ');
        item.addEventListener('click', function () {
            addGroupBindingContractSelection(contract.uuid);
            input.value = '';
            renderGroupBindingContractSearchResults('');
            input.focus();
        });
        results.appendChild(item);
    });

    results.classList.remove('hidden');
}

function renderGroupBindingContractPills() {
    const container = document.getElementById('group-binding-contract-selected');
    if (!container) {
        return;
    }

    const selectedIds = selectedGroupBindingContractIds();
    container.innerHTML = '';
    if (selectedIds.length === 0) {
        const empty = document.createElement('span');
        empty.className = 'text-xs text-zinc-500';
        empty.textContent = 'No contracts selected.';
        container.appendChild(empty);
        return;
    }

    selectedIds.forEach(function (uuid) {
        const contract = (bindingContracts || []).find(function (entry) {
            return String(entry.uuid || '').trim() === uuid;
        });
        const pill = document.createElement('span');
        pill.className = 'extra-pill tool-pill';
        const label = contract ? (contract.name || contract.uuid || uuid) : uuid;
        const toolCount = contract && Array.isArray(contract.tools) ? contract.tools.length : 0;
        pill.textContent = label + (toolCount > 0 ? (' (' + toolCount + ')') : '');

        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'pill-remove';
        remove.setAttribute('aria-label', 'Remove contract ' + label);
        remove.innerHTML = '<i class="fa-solid fa-xmark"></i>';
        remove.addEventListener('click', function () {
            removeGroupBindingContractSelection(uuid);
        });

        pill.appendChild(remove);
        container.appendChild(pill);
    });
}

function addGroupBindingContractSelection(contractUuid) {
    const normalized = String(contractUuid || '').trim();
    if (!normalized) {
        return;
    }
    if (!modalSelectedBindingContractUuids.includes(normalized)) {
        modalSelectedBindingContractUuids.push(normalized);
    }
    renderGroupBindingContractPills();
}

function removeGroupBindingContractSelection(contractUuid) {
    const normalized = String(contractUuid || '').trim();
    modalSelectedBindingContractUuids = (modalSelectedBindingContractUuids || []).filter(function (id) {
        return id !== normalized;
    });
    renderGroupBindingContractPills();
    const input = document.getElementById('group-binding-contract-search');
    if (input) {
        renderGroupBindingContractSearchResults(input.value || '');
    }
}

function setGroupModalTab(tab) {
    const normalized = String(tab || 'general').toLowerCase();
    const allowed = ['general', 'authentication', 'bindings'];
    const active = allowed.includes(normalized) ? normalized : 'general';

    allowed.forEach(function (name) {
        const button = document.getElementById('group-tab-' + name);
        const panel = document.getElementById('group-tab-panel-' + name);
        const isActive = name === active;
        if (button) {
            button.classList.toggle('bg-zinc-900', isActive);
            button.classList.toggle('text-zinc-100', isActive);
            button.classList.toggle('bg-zinc-950', !isActive);
            button.classList.toggle('text-zinc-400', !isActive);
        }
        if (panel) {
            panel.classList.toggle('hidden', !isActive);
        }
    });
}

function getActiveGroupTab() {
    const active = document.querySelector('.group-tab-btn.bg-zinc-900');
    return active ? String(active.getAttribute('data-tab') || 'general').toLowerCase() : 'general';
}

function syncGroupAuthVisibility() {
    const typeValue = (document.getElementById('group-kind').value || 'REST').trim().toUpperCase();
    const modeSelect = document.getElementById('group-auth-mode');
    const oauthWrap = document.getElementById('group-oauth-template-wrap');
    const baseUrlWrap = document.getElementById('group-base-url-wrap');
    const urlOverrideWrap = document.getElementById('group-url-override-wrap');
    const authModeWrap = document.getElementById('group-auth-mode-wrap');
    const contractsWrap = document.getElementById('group-binding-contracts-wrap');
    const authTabButton = document.getElementById('group-tab-authentication');

    if (!modeSelect || !oauthWrap) {
        return;
    }

    const isMongo = typeValue === 'MONGO';

    if (baseUrlWrap) {
        baseUrlWrap.classList.toggle('hidden', isMongo);
    }
    if (urlOverrideWrap) {
        urlOverrideWrap.classList.toggle('hidden', isMongo);
    }
    if (authModeWrap) {
        authModeWrap.classList.toggle('hidden', isMongo);
    }

    if (isMongo) {
        document.getElementById('group-base-url').value = '';
        document.getElementById('group-url-override-enabled').checked = false;
        modeSelect.value = 'NONE';
        if (authTabButton) {
            authTabButton.classList.add('hidden');
        }
        if (getActiveGroupTab() === 'authentication') {
            setGroupModalTab('general');
        }
    } else if (authTabButton) {
        authTabButton.classList.remove('hidden');
    }

    if (typeValue !== 'REST' && modeSelect.value === 'OAUTH') {
        modeSelect.value = 'NONE';
    }

    const isRest = typeValue === 'REST';
    const usesOAuth = modeSelect.value === 'OAUTH';
    oauthWrap.classList.toggle('hidden', !isRest);
    if (contractsWrap) {
        contractsWrap.classList.toggle('hidden', !isRest);
    }
    if (!isRest) {
        modalSelectedBindingContractUuids = [];
        renderGroupBindingContractPills();
    }

    const oauthSelect = document.getElementById('group-oauth-template');
    if (oauthSelect) {
        oauthSelect.disabled = !isRest || !usesOAuth;
    }

    const saveButton = document.getElementById('group-modal-save');
    if (saveButton) {
        const oauthMissingSelection = isRest && usesOAuth && (!oauthSelect || !oauthSelect.value);
        saveButton.disabled = oauthMissingSelection;
        if (oauthMissingSelection) {
            saveButton.classList.add('opacity-60', 'cursor-not-allowed');
            saveButton.title = 'Select an OAuth template to save this group.';
        } else {
            saveButton.classList.remove('opacity-60', 'cursor-not-allowed');
            saveButton.title = '';
        }
    }
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
            + '  <option value="date">date</option>'
            + '  <option value="timestamp">timestamp</option>'
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
    const entry = findGroupEntry(uuid);
    const reflectionCount = entry && Array.isArray(entry.reflections) ? entry.reflections.length : 0;
    const bindingCount = entry && Array.isArray(entry.bindings) ? entry.bindings.length : 0;
    const hasChildren = reflectionCount > 0 || bindingCount > 0;

    if (!confirm('Delete this reflection group?')) {
        return;
    }

    let purge = false;
    if (hasChildren) {
        const message = 'This group contains ' + reflectionCount + ' reflection(s) and ' + bindingCount + ' binding(s).\n\n'
            + 'Delete EVERYTHING in this group (tools, bindings, and group) permanently?';
        if (!confirm(message)) {
            return;
        }
        purge = true;
    }

    try {
        const url = '/api/reflection-groups/' + encodeURIComponent(uuid) + (purge ? '?purge=true' : '');
        const response = await fetch(url, {
            method: 'DELETE',
            headers: buildCsrfHeaders()
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showAlert(result.error || 'Failed to delete group.', 'danger');
            return;
        }
        showAlert(purge ? 'Group and all contents deleted.' : 'Group deleted.', 'success');
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

    if (defaultGroupUuid && resolveGroupType(defaultGroupUuid) === 'MONGO') {
        showAlert('Mongo reflections are managed through the Mongo Wizard.', 'info');
        openMongoWizardModal();
        return;
    }

    if (defaultGroupUuid && resolveGroupType(defaultGroupUuid) === 'RECORD') {
        openCreateRecordToolModal(defaultGroupUuid);
        return;
    }

    const supportedGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        const type = String(group.type || 'REST').toUpperCase();
        return type === 'REST';
    });
    if (supportedGroups.length === 0) {
        showAlert('Create a REST group before adding REST/OAuth reflections.', 'warning');
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'New REST/OAuth Reflection';
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
    activeContractTool = null;
    modalParameters = [];
    modalHeaders = [];
    modalQueryParameters = [];
    populateGroupSelect(defaultGroupUuid || '');
    updateRequestTemplateVisibility();
    updateOutputSchemaVisibility();
    setReflectionTab('request');
    renderKeyValueRows('headers-list', modalHeaders, 'header');
    renderKeyValueRows('query-params-list', modalQueryParameters, 'query');

    const selectedGroupUuid = document.getElementById('reflection-group').value;
    const pendingContractTools = missingContractToolsForGroup(selectedGroupUuid);
    const hasContracts = contractsForGroup(selectedGroupUuid).length > 0;
    if (hasContracts && pendingContractTools.length === 0) {
        showAlert('All contract tools for this group already exist. Contract-bound groups do not allow non-contract REST tools.', 'warning');
        return;
    }
    if (pendingContractTools.length > 0) {
        applyContractToolToModal(pendingContractTools[0], true);
    }

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

    if (resolveGroupType(reflection.groupUuid) === 'MONGO') {
        openEditMongoToolModal(uuid);
        return;
    }

    if (resolveGroupType(reflection.groupUuid) === 'RECORD') {
        openEditRecordToolModal(uuid);
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'Edit REST/OAuth Reflection';
    populateReflectionModalFromReflection(reflection, false);
    showModal('reflection-modal');
}

function openCopyReflectionModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Reflection not found. Reload and try again.', 'warning');
        return;
    }

    if (resolveGroupType(reflection.groupUuid) === 'MONGO') {
        showAlert('Copy for Mongo tools is not supported in this modal. Create a new Mongo tool and adjust values.', 'info');
        return;
    }

    if (resolveGroupType(reflection.groupUuid) === 'RECORD') {
        openCopyRecordToolModal(uuid);
        return;
    }

    document.getElementById('reflection-modal-title').textContent = 'Copy REST/OAuth Reflection';
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
            required: !!parameter.required,
            array: !!parameter.array
        };
    });
    populateGroupSelect(reflection.groupUuid || '');
    updateRequestTemplateVisibility();
    updateOutputSchemaVisibility();
    setReflectionTab('request');
    renderKeyValueRows('headers-list', modalHeaders, 'header');
    renderKeyValueRows('query-params-list', modalQueryParameters, 'query');
    const contractTool = contractToolForReflection(reflection.groupUuid || '', reflection.id || '');
    if (contractTool) {
        applyContractToolToModal(contractTool, true);
    } else {
        activeContractTool = null;
        renderParameters();
        updateReflectionContractUiLock(false, null);
    }
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

    const groupType = resolveGroupType(payload.groupUuid);

    if (!payload.id || !payload.name || !payload.groupUuid) {
        showReflectionModalAlert('ID, Name, and Group are required.', 'warning');
        return;
    }

    if (groupType === 'REST' && !payload.url) {
        showReflectionModalAlert('URL is required for REST reflections.', 'warning');
        return;
    }

    if (groupType !== 'REST') {
        showReflectionModalAlert('This modal is for REST/OAuth reflections only.', 'warning');
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
    const eligibleGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        const type = String(group.type || 'REST').toUpperCase();
        return type === 'REST';
    });
    eligibleGroups.forEach(function (entry) {
        const group = entry.group || entry;
        const type = String(group.type || 'REST').toUpperCase();
        const option = document.createElement('option');
        option.value = group.uuid;
        option.textContent = group.name + ' [' + type + ']';
        if (selectedUuid && selectedUuid === group.uuid) {
            option.selected = true;
        }
        select.appendChild(option);
    });
    if (select.options.length > 0 && select.selectedIndex < 0) {
        select.selectedIndex = 0;
    }
}

function contractsForGroup(groupUuid) {
    const entry = findGroupEntry(groupUuid);
    const group = entry ? (entry.group || entry) : null;
    const ids = (group && Array.isArray(group.bindingContractUuids)) ? group.bindingContractUuids : [];
    if (!ids || ids.length === 0) {
        return [];
    }
    const idSet = new Set(ids.map(function (id) {
        return String(id || '').trim();
    }).filter(function (id) { return !!id; }));
    return (bindingContracts || []).filter(function (contract) {
        return idSet.has(String(contract.uuid || '').trim());
    });
}

function contractToolForReflection(groupUuid, reflectionId) {
    const normalizedSuffix = canonicalContractToolSuffix(reflectionId);
    if (!normalizedSuffix) {
        return null;
    }
    const contracts = contractsForGroup(groupUuid);
    for (let i = 0; i < contracts.length; i += 1) {
        const tools = Array.isArray(contracts[i].tools) ? contracts[i].tools : [];
        for (let j = 0; j < tools.length; j += 1) {
            const tool = tools[j] || {};
            const toolName = canonicalContractToolId(tool.name || '');
            if (canonicalContractToolSuffix(toolName) === normalizedSuffix) {
                return tool;
            }
        }
    }
    return null;
}

function missingContractToolsForGroup(groupUuid) {
    const contractTools = [];
    contractsForGroup(groupUuid).forEach(function (contract) {
        const tools = Array.isArray(contract.tools) ? contract.tools : [];
        tools.forEach(function (tool) {
            if (tool && tool.name) {
                contractTools.push(tool);
            }
        });
    });

    const existingIds = new Set(reflectionsForGroupUuid(groupUuid).map(function (reflection) {
        const suffix = canonicalContractToolSuffix(reflection.id || '');
        if (!suffix) {
            return [];
        }
        return suffix;
    }).filter(function (value) {
        return !!value;
    }));

    return contractTools.filter(function (tool) {
        const toolSuffix = canonicalContractToolSuffix(tool.name || '');
        if (!toolSuffix) {
            return false;
        }
        return !existingIds.has(toolSuffix);
    });
}

function canonicalContractToolId(toolId) {
    return String(toolId || '').trim().toLowerCase();
}

function canonicalContractToolSuffix(toolId) {
    const normalized = canonicalContractToolId(toolId);
    if (!normalized) {
        return '';
    }
    const dot = normalized.lastIndexOf('.');
    if (dot <= -1 || dot >= normalized.length - 1) {
        return normalized;
    }
    return normalized.substring(dot + 1);
}

function applyContractToolToModal(contractTool, lockFields) {
    if (!contractTool) {
        activeContractTool = null;
        renderParameters();
        updateReflectionContractUiLock(false, null);
        return;
    }

    activeContractTool = contractTool;
    document.getElementById('reflection-id').value = contractTool.name || '';
    if (!document.getElementById('reflection-name').value.trim()) {
        document.getElementById('reflection-name').value = contractTool.name || '';
    }
    modalParameters = (contractTool.inputParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            required: !!parameter.required,
            array: !!parameter.array
        };
    });
    renderParameters();
    updateReflectionContractUiLock(lockFields, contractTool.name || null);
}

function updateReflectionContractUiLock(locked, toolId) {
    const idInput = document.getElementById('reflection-id');
    const addParamButton = document.getElementById('add-param-btn');
    const hintId = 'reflection-contract-lock-hint';
    let hint = document.getElementById(hintId);

    if (idInput) {
        idInput.readOnly = !!locked;
        idInput.classList.toggle('opacity-70', !!locked);
    }
    if (addParamButton) {
        addParamButton.disabled = !!locked;
        addParamButton.classList.toggle('opacity-60', !!locked);
        addParamButton.classList.toggle('cursor-not-allowed', !!locked);
    }

    if (!hint) {
        hint = document.createElement('div');
        hint.id = hintId;
        hint.className = 'mt-1 text-xs';
        const paramsList = document.getElementById('params-list');
        if (paramsList && paramsList.parentElement) {
            paramsList.parentElement.appendChild(hint);
        }
    }

    if (hint) {
        if (locked) {
            hint.className = 'mt-1 text-xs text-amber-300';
            if (toolId) {
                hint.textContent = 'Contract-enforced tool: ' + toolId + '. Tool ID and input parameters are locked by the group binding contract.';
            } else {
                hint.textContent = 'This group has attached binding contracts. Tool ID must match a contract tool and input parameters are contract-enforced.';
            }
        } else {
            if (toolId === 'optional-extra-tool') {
                hint.className = 'mt-1 text-xs text-zinc-500';
                hint.textContent = 'This group has attached binding contracts. Contract tools are required and schema-locked; additional non-contract tools are allowed.';
            } else {
                hint.className = 'mt-1 text-xs text-zinc-500';
                hint.textContent = '';
            }
        }
    }
}

function onReflectionGroupChanged() {
    const groupUuid = document.getElementById('reflection-group').value;
    const reflectionUuid = document.getElementById('reflection-uuid').value.trim();
    const reflectionId = document.getElementById('reflection-id').value.trim();
    if (!reflectionUuid && !reflectionId) {
        const pendingContractTools = missingContractToolsForGroup(groupUuid);
        if (pendingContractTools.length > 0) {
            applyContractToolToModal(pendingContractTools[0], true);
            return;
        }
    }
    const tool = contractToolForReflection(groupUuid, reflectionId);
    if (tool) {
        applyContractToolToModal(tool, true);
        return;
    }
    const hasContracts = contractsForGroup(groupUuid).length > 0;
    activeContractTool = null;
    updateReflectionContractUiLock(false, hasContracts ? 'optional-extra-tool' : null);
}

function onReflectionIdentityChanged() {
    const groupUuid = document.getElementById('reflection-group').value;
    const reflectionId = document.getElementById('reflection-id').value.trim();
    const tool = contractToolForReflection(groupUuid, reflectionId);
    if (tool) {
        applyContractToolToModal(tool, true);
        return;
    }
    const hasContracts = contractsForGroup(groupUuid).length > 0;
    activeContractTool = null;
    updateReflectionContractUiLock(false, hasContracts ? 'optional-extra-tool' : null);
}

function populateRecordGroupSelect(selectedUuid) {
    const select = document.getElementById('record-tool-group');
    if (!select) {
        return;
    }
    select.innerHTML = '';
    const recordGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        return String(group.type || 'REST').toUpperCase() === 'RECORD';
    });
    recordGroups.forEach(function (entry) {
        const group = entry.group || entry;
        const option = document.createElement('option');
        option.value = group.uuid;
        option.textContent = group.name + ' [RECORD]';
        if (selectedUuid && selectedUuid === group.uuid) {
            option.selected = true;
        }
        select.appendChild(option);
    });
    if (select.options.length > 0 && select.selectedIndex < 0) {
        select.selectedIndex = 0;
    }
}

function parseRecordToolMetadataFromOutputSchema(outputSchema) {
    if (!outputSchema) {
        return { recordFqn: '', operation: 'SEARCH', queryType: 'SQL' };
    }
    try {
        const parsed = JSON.parse(outputSchema);
        return {
            recordFqn: String(parsed.recordFqn || '').trim(),
            operation: String(parsed.operation || 'SEARCH').trim().toUpperCase(),
            queryType: String(parsed.queryType || 'SQL').trim().toUpperCase()
        };
    } catch (_error) {
        return { recordFqn: '', operation: 'SEARCH', queryType: 'SQL' };
    }
}

function mapRecordBackendOperationToModal(operation) {
    const normalized = String(operation || 'SEARCH').toUpperCase();
    if (normalized === 'READ') {
        return 'GET';
    }
    if (normalized === 'LIST' || normalized === 'SEARCH' || normalized === 'GET') {
        return normalized;
    }
    return 'SEARCH';
}

function mapRecordModalOperationToBackend(operation) {
    const normalized = String(operation || 'SEARCH').toUpperCase();
    if (normalized === 'GET') {
        return 'READ';
    }
    return normalized;
}

function currentRecordModalOperation() {
    const value = document.getElementById('record-tool-operation')?.value || 'SEARCH';
    return String(value).toUpperCase();
}

function syncRecordToolOperationUi() {
    const operation = currentRecordModalOperation();
    const searchConfig = document.getElementById('record-tool-search-config');
    if (searchConfig) {
        searchConfig.classList.toggle('hidden', operation !== 'SEARCH');
    }
}

function openCreateRecordToolModal(defaultGroupUuid) {
    const recordGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        return String(group.type || 'REST').toUpperCase() === 'RECORD';
    });
    if (recordGroups.length === 0) {
        showAlert('Create a RECORD group first before adding a record tool.', 'warning');
        return;
    }

    document.getElementById('record-tool-modal-title').textContent = 'New Record Search Tool';
    document.getElementById('record-tool-uuid').value = '';
    document.getElementById('record-tool-id').value = '';
    document.getElementById('record-tool-name').value = '';
    document.getElementById('record-tool-description').value = '';
    document.getElementById('record-tool-operation').value = 'SEARCH';
    document.getElementById('record-tool-query-type').value = 'SQL';
    document.getElementById('record-tool-query-template').value = '';
    populateRecordGroupSelect(defaultGroupUuid || '');
    recordModalParameters = [];
    syncRecordToolOperationUi();
    renderRecordToolParameters();
    clearRecordToolModalAlert();
    showModal('record-tool-modal');
}

function openEditRecordToolModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Record reflection not found. Reload and try again.', 'warning');
        return;
    }
    const metadata = parseRecordToolMetadataFromOutputSchema(reflection.outputSchema || '');

    document.getElementById('record-tool-modal-title').textContent = 'Edit Record Search Tool';
    document.getElementById('record-tool-uuid').value = reflection.uuid || '';
    document.getElementById('record-tool-id').value = reflection.id || '';
    document.getElementById('record-tool-name').value = reflection.name || '';
    document.getElementById('record-tool-description').value = reflection.description || '';
    document.getElementById('record-tool-operation').value = mapRecordBackendOperationToModal(metadata.operation || 'SEARCH');
    document.getElementById('record-tool-query-type').value = 'SQL';
    document.getElementById('record-tool-query-template').value = reflection.bodyTemplate || '';
    populateRecordGroupSelect(reflection.groupUuid || '');
    recordModalParameters = (reflection.inputParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            required: !!parameter.required,
            array: !!parameter.array
        };
    });
    syncRecordToolOperationUi();
    renderRecordToolParameters();
    clearRecordToolModalAlert();
    showModal('record-tool-modal');
}

function openCopyRecordToolModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Record reflection not found. Reload and try again.', 'warning');
        return;
    }
    const metadata = parseRecordToolMetadataFromOutputSchema(reflection.outputSchema || '');

    document.getElementById('record-tool-modal-title').textContent = 'Copy Record Search Tool';
    document.getElementById('record-tool-uuid').value = '';
    document.getElementById('record-tool-id').value = '';
    document.getElementById('record-tool-name').value = reflection.name || '';
    document.getElementById('record-tool-description').value = reflection.description || '';
    document.getElementById('record-tool-operation').value = mapRecordBackendOperationToModal(metadata.operation || 'SEARCH');
    document.getElementById('record-tool-query-type').value = 'SQL';
    document.getElementById('record-tool-query-template').value = reflection.bodyTemplate || '';
    populateRecordGroupSelect(reflection.groupUuid || '');
    recordModalParameters = (reflection.inputParameters || []).map(function (parameter) {
        return {
            name: parameter.name || '',
            type: parameter.type || 'string',
            description: parameter.description || '',
            required: !!parameter.required,
            array: !!parameter.array
        };
    });
    syncRecordToolOperationUi();
    renderRecordToolParameters();
    clearRecordToolModalAlert();
    showModal('record-tool-modal');
}

function closeRecordToolModal() {
    hideModal('record-tool-modal');
}

function showRecordToolModalAlert(message, level) {
    const alert = document.getElementById('record-tool-modal-alert');
    if (!alert) {
        return;
    }
    alert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(level) + '">' + escapeHtml(message || '') + '</div>';
}

function clearRecordToolModalAlert() {
    const alert = document.getElementById('record-tool-modal-alert');
    if (alert) {
        alert.innerHTML = '';
    }
}

function renderRecordToolParameters() {
    const container = document.getElementById('record-tool-params-list');
    if (!container) {
        return;
    }
    container.innerHTML = '';

    if (!recordModalParameters || recordModalParameters.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No explicit parameters. Runtime input is still accepted.</p>';
        return;
    }

    const header = document.createElement('div');
    header.className = 'grid grid-cols-12 gap-2 mb-1 text-xs text-zinc-500';
    header.innerHTML = ''
        + '<div class="col-span-3">Name</div>'
        + '<div class="col-span-2">Type</div>'
        + '<div class="col-span-3">Description</div>'
        + '<div class="col-span-1">Array</div>'
        + '<div class="col-span-2">Required</div>'
        + '<div class="col-span-1"></div>';
    container.appendChild(header);

    recordModalParameters.forEach(function (parameter, index) {
        const row = document.createElement('div');
        row.className = 'grid grid-cols-12 gap-2 mb-2';
        row.innerHTML = ''
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 record-param-name" data-index="' + index + '" value="' + escapeHtml(parameter.name || '') + '" placeholder="query">'
            + '<select class="col-span-2 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 record-param-type" data-index="' + index + '">'
            + '  <option value="string">string</option>'
            + '  <option value="int">int</option>'
            + '  <option value="double">double</option>'
            + '  <option value="boolean">boolean</option>'
            + '  <option value="date">date</option>'
            + '  <option value="timestamp">timestamp</option>'
            + '</select>'
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 record-param-description" data-index="' + index + '" value="' + escapeHtml(parameter.description || '') + '" placeholder="parameter purpose">'
            + '<label class="col-span-1 inline-flex items-center justify-center text-xs text-zinc-300"><input type="checkbox" class="record-param-array" data-index="' + index + '" ' + (parameter.array ? 'checked' : '') + '></label>'
            + '<label class="col-span-2 inline-flex items-center gap-1 text-xs text-zinc-300"><input type="checkbox" class="record-param-required" data-index="' + index + '" ' + (parameter.required ? 'checked' : '') + '>Required</label>'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 remove-record-param" data-index="' + index + '" title="Remove"><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(row);
        const typeSelect = row.querySelector('.record-param-type');
        if (typeSelect) {
            typeSelect.value = parameter.type || 'string';
        }
    });

    container.querySelectorAll('.record-param-name').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            recordModalParameters[index].name = input.value;
        });
    });
    container.querySelectorAll('.record-param-type').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            recordModalParameters[index].type = input.value;
        });
    });
    container.querySelectorAll('.record-param-description').forEach(function (input) {
        input.addEventListener('input', function () {
            const index = Number(input.getAttribute('data-index'));
            recordModalParameters[index].description = input.value;
        });
    });
    container.querySelectorAll('.record-param-array').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            recordModalParameters[index].array = !!input.checked;
        });
    });
    container.querySelectorAll('.record-param-required').forEach(function (input) {
        input.addEventListener('change', function () {
            const index = Number(input.getAttribute('data-index'));
            recordModalParameters[index].required = !!input.checked;
        });
    });
    container.querySelectorAll('.remove-record-param').forEach(function (button) {
        button.addEventListener('click', function () {
            const index = Number(button.getAttribute('data-index'));
            recordModalParameters.splice(index, 1);
            renderRecordToolParameters();
        });
    });
}

async function saveRecordToolReflection() {
    const uuid = document.getElementById('record-tool-uuid').value.trim();
    const groupUuid = document.getElementById('record-tool-group').value;
    const recordFqn = resolveRecordFqnForGroup(groupUuid);
    const queryType = 'SQL';
    const modalOperation = currentRecordModalOperation();
    const backendOperation = mapRecordModalOperationToBackend(modalOperation);
    const queryTemplate = document.getElementById('record-tool-query-template').value.trim();

    if (!recordFqn) {
        showRecordToolModalAlert('Unable to resolve record type for this group.', 'warning');
        return;
    }

    const inputParameters = sanitizeParameters(recordModalParameters);

    const payload = {
        id: document.getElementById('record-tool-id').value.trim(),
        name: document.getElementById('record-tool-name').value.trim(),
        description: document.getElementById('record-tool-description').value.trim(),
        groupUuid: groupUuid,
        inputParameters: inputParameters,
        method: 'POST',
        url: '',
        requestContentType: 'application/json',
        responseContentType: 'application/json',
        outputSchema: JSON.stringify({
            '$schema': 'https://json-schema.org/draft/2020-12/schema',
            type: 'object',
            'x-vork-record-tool': true,
            'x-vork-mandatory-record-tool': false,
            recordFqn: recordFqn,
            operation: backendOperation,
            queryType: queryType
        }),
        headers: {},
        queryParameters: {},
        bodyTemplate: modalOperation === 'SEARCH' ? queryTemplate : ''
    };

    if (!payload.id || !payload.name || !payload.groupUuid) {
        showRecordToolModalAlert('ID, Name, and Record Group are required.', 'warning');
        return;
    }
    if (!/^[A-Za-z0-9]+$/.test(payload.id)) {
        showRecordToolModalAlert('ID must be alphanumeric.', 'warning');
        return;
    }
    if (resolveGroupType(payload.groupUuid) !== 'RECORD') {
        showRecordToolModalAlert('Selected group must be a RECORD group.', 'warning');
        return;
    }

    const hasUuidParam = inputParameters.some(function (parameter) {
        return String(parameter.name || '').trim().toLowerCase() === 'uuid';
    });
    if (modalOperation === 'GET' && !hasUuidParam) {
        showRecordToolModalAlert('GET tools require an input parameter named uuid.', 'warning');
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
            showRecordToolModalAlert(result.error || 'Failed to save record tool.', 'danger');
            return;
        }

        closeRecordToolModal();
        showAlert(uuid ? 'Record tool updated.' : 'Record tool created.', 'success');
        await loadAll();
    } catch (_error) {
        showRecordToolModalAlert('Network error while saving record tool.', 'danger');
    }
}

function resolveRecordFqnForGroup(groupUuid) {
    const groupReflections = reflectionsForGroupUuid(groupUuid);
    for (let i = 0; i < groupReflections.length; i += 1) {
        const reflection = groupReflections[i] || {};
        const outputSchema = reflection.outputSchema || '';
        if (!outputSchema) {
            continue;
        }
        try {
            const parsed = JSON.parse(outputSchema);
            const recordFqn = parsed && parsed.recordFqn ? String(parsed.recordFqn).trim() : '';
            if (recordFqn) {
                return recordFqn;
            }
        } catch (_err) {
            const match = outputSchema.match(/"recordFqn"\s*:\s*"([^"]+)"/);
            if (match && match[1]) {
                return match[1].trim();
            }
        }
    }
    return '';
}

function populateMongoGroupSelect(selectedUuid) {
    const select = document.getElementById('mongo-tool-group');
    if (!select) {
        return;
    }
    select.innerHTML = '';
    const mongoGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        return String(group.type || 'REST').toUpperCase() === 'MONGO';
    });
    mongoGroups.forEach(function (entry) {
        const group = entry.group || entry;
        const option = document.createElement('option');
        option.value = group.uuid;
        option.textContent = group.name + ' [MONGO]';
        if (selectedUuid && selectedUuid === group.uuid) {
            option.selected = true;
        }
        select.appendChild(option);
    });
    if (select.options.length > 0 && select.selectedIndex < 0) {
        select.selectedIndex = 0;
    }
}

function parseMongoMetadataFromOutputSchema(outputSchema) {
    if (!outputSchema) {
        return { database: '', collection: '', operation: 'READ', queryType: 'MONGO', queryTemplate: '' };
    }
    try {
        const parsed = JSON.parse(outputSchema);
        return {
            database: String(parsed.database || '').trim(),
            collection: String(parsed.collection || '').trim(),
            operation: String(parsed.operation || 'READ').trim().toUpperCase(),
            queryType: String(parsed.queryType || 'MONGO').trim().toUpperCase(),
            queryTemplate: String(parsed.queryTemplate || '').trim()
        };
    } catch (_error) {
        return { database: '', collection: '', operation: 'READ', queryType: 'MONGO', queryTemplate: '' };
    }
}

function openCreateMongoToolModal(defaultGroupUuid) {
    const mongoGroups = (groups || []).filter(function (entry) {
        const group = entry.group || entry;
        return String(group.type || 'REST').toUpperCase() === 'MONGO';
    });
    if (mongoGroups.length === 0) {
        showAlert('Create a MONGO group first, or use Mongo Wizard to generate one.', 'warning');
        return;
    }

    document.getElementById('mongo-tool-modal-title').textContent = 'New Mongo Tool';
    document.getElementById('mongo-tool-uuid').value = '';
    document.getElementById('mongo-tool-id').value = '';
    document.getElementById('mongo-tool-name').value = '';
    document.getElementById('mongo-tool-description').value = '';
    document.getElementById('mongo-tool-database').value = '';
    document.getElementById('mongo-tool-collection').value = '';
    document.getElementById('mongo-tool-operation').value = 'READ';
    document.getElementById('mongo-tool-query-type').value = 'MONGO';
    document.getElementById('mongo-tool-query-template').value = '';
    document.getElementById('mongo-tool-inferred-schema').value = '';
    populateMongoGroupSelect(defaultGroupUuid || '');
    syncMongoToolSearchConfigVisibility();
    clearMongoToolModalAlert();
    showModal('mongo-tool-modal');
}

function openEditMongoToolModal(uuid) {
    const reflection = reflections.find(function (item) { return item.uuid === uuid; });
    if (!reflection) {
        showAlert('Mongo reflection not found. Reload and try again.', 'warning');
        return;
    }
    const metadata = parseMongoMetadataFromOutputSchema(reflection.outputSchema || '');
    const schemaText = (function () {
        try {
            const parsed = JSON.parse(reflection.outputSchema || '{}');
            const inferred = parsed.properties && parsed.properties.document ? parsed.properties.document : null;
            return inferred ? JSON.stringify(inferred, null, 2) : '';
        } catch (_error) {
            return '';
        }
    })();

    document.getElementById('mongo-tool-modal-title').textContent = 'Edit Mongo Tool';
    document.getElementById('mongo-tool-uuid').value = reflection.uuid || '';
    document.getElementById('mongo-tool-id').value = reflection.id || '';
    document.getElementById('mongo-tool-name').value = reflection.name || '';
    document.getElementById('mongo-tool-description').value = reflection.description || '';
    document.getElementById('mongo-tool-database').value = metadata.database || '';
    document.getElementById('mongo-tool-collection').value = metadata.collection || '';
    document.getElementById('mongo-tool-operation').value = metadata.operation || 'READ';
    document.getElementById('mongo-tool-query-type').value = metadata.queryType === 'SQL' ? 'SQL' : 'MONGO';
    document.getElementById('mongo-tool-query-template').value = metadata.queryTemplate || '';
    document.getElementById('mongo-tool-inferred-schema').value = schemaText;
    populateMongoGroupSelect(reflection.groupUuid || '');
    syncMongoToolSearchConfigVisibility();
    clearMongoToolModalAlert();
    showModal('mongo-tool-modal');
}

function syncMongoToolSearchConfigVisibility() {
    const operation = (document.getElementById('mongo-tool-operation')?.value || '').toUpperCase();
    const block = document.getElementById('mongo-tool-search-config');
    if (!block) {
        return;
    }
    block.classList.toggle('hidden', operation !== 'SEARCH');
}

function closeMongoToolModal() {
    hideModal('mongo-tool-modal');
}

function showMongoToolModalAlert(message, level) {
    const alert = document.getElementById('mongo-tool-modal-alert');
    if (!alert) {
        return;
    }
    alert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(level) + '">' + escapeHtml(message || '') + '</div>';
}

function clearMongoToolModalAlert() {
    const alert = document.getElementById('mongo-tool-modal-alert');
    if (alert) {
        alert.innerHTML = '';
    }
}

async function saveMongoToolReflection() {
    const uuid = document.getElementById('mongo-tool-uuid').value.trim();
    const inferredSchemaRaw = document.getElementById('mongo-tool-inferred-schema').value.trim();
    if (inferredSchemaRaw) {
        try {
            JSON.parse(inferredSchemaRaw);
        } catch (_error) {
            showMongoToolModalAlert('Inferred schema must be valid JSON.', 'warning');
            return;
        }
    }

    const payload = {
        id: document.getElementById('mongo-tool-id').value.trim(),
        name: document.getElementById('mongo-tool-name').value.trim(),
        description: document.getElementById('mongo-tool-description').value.trim(),
        groupUuid: document.getElementById('mongo-tool-group').value,
        database: document.getElementById('mongo-tool-database').value.trim(),
        collection: document.getElementById('mongo-tool-collection').value.trim(),
        operation: document.getElementById('mongo-tool-operation').value,
        inferredSchema: inferredSchemaRaw,
        queryType: document.getElementById('mongo-tool-query-type').value,
        queryTemplate: document.getElementById('mongo-tool-query-template').value.trim()
    };

    if (!payload.id || !payload.name || !payload.groupUuid || !payload.collection || !payload.operation) {
        showMongoToolModalAlert('ID, Name, Mongo Group, Collection, and Operation are required.', 'warning');
        return;
    }
    if (!/^[A-Za-z0-9]+$/.test(payload.id)) {
        showMongoToolModalAlert('ID must be alphanumeric.', 'warning');
        return;
    }

    if (String(payload.operation || '').toUpperCase() !== 'SEARCH') {
        payload.queryType = '';
        payload.queryTemplate = '';
    }

    try {
        const response = await fetch(uuid ? '/api/mongo-reflections/' + encodeURIComponent(uuid) : '/api/mongo-reflections', {
            method: uuid ? 'PUT' : 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok) {
            showMongoToolModalAlert(result.error || 'Failed to save Mongo tool.', 'danger');
            return;
        }

        closeMongoToolModal();
        showAlert(uuid ? 'Mongo tool updated.' : 'Mongo tool created.', 'success');
        await loadAll();
    } catch (_error) {
        showMongoToolModalAlert('Network error while saving Mongo tool.', 'danger');
    }
}

function renderParameters() {
    const container = document.getElementById('params-list');
    container.innerHTML = '';
    const contractLocked = !!activeContractTool;

    if (!modalParameters || modalParameters.length === 0) {
        container.innerHTML = '<p class="text-xs text-zinc-500">No explicit parameters. Runtime input is still accepted.</p>';
        return;
    }

    const header = document.createElement('div');
    header.className = 'grid grid-cols-12 gap-2 mb-1 text-xs text-zinc-500';
    header.innerHTML = ''
        + '<div class="col-span-3">Name</div>'
        + '<div class="col-span-2">Type</div>'
        + '<div class="col-span-3">Description</div>'
        + '<div class="col-span-1">Array</div>'
        + '<div class="col-span-2">Required</div>'
        + '<div class="col-span-1"></div>';
    container.appendChild(header);

    modalParameters.forEach(function (parameter, index) {
        const row = document.createElement('div');
        row.className = 'grid grid-cols-12 gap-2 mb-2';
        row.innerHTML = ''
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-name" data-index="' + index + '" value="' + escapeHtml(parameter.name || '') + '" placeholder="city" ' + (contractLocked ? 'readonly' : '') + '>'
            + '<select class="col-span-2 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-type" data-index="' + index + '" ' + (contractLocked ? 'disabled' : '') + '>'
            + '  <option value="string">string</option>'
            + '  <option value="int">int</option>'
            + '  <option value="double">double</option>'
            + '  <option value="boolean">boolean</option>'
            + '  <option value="date">date</option>'
            + '  <option value="timestamp">timestamp</option>'
            + '</select>'
            + '<input class="col-span-3 rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 param-description" data-index="' + index + '" value="' + escapeHtml(parameter.description || '') + '" placeholder="parameter purpose" ' + (contractLocked ? 'readonly' : '') + '>'
            + '<label class="col-span-1 inline-flex items-center justify-center text-xs text-zinc-300"><input type="checkbox" class="param-array" data-index="' + index + '" ' + (parameter.array ? 'checked' : '') + ' ' + (contractLocked ? 'disabled' : '') + '></label>'
            + '<label class="col-span-2 inline-flex items-center gap-1 text-xs text-zinc-300"><input type="checkbox" class="param-required" data-index="' + index + '" ' + (parameter.required ? 'checked' : '') + ' ' + (contractLocked ? 'disabled' : '') + '>Required</label>'
            + '<button type="button" class="col-span-1 rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 remove-param" data-index="' + index + '" title="Remove" ' + (contractLocked ? 'disabled' : '') + '><i class="fa-solid fa-xmark"></i></button>';
        container.appendChild(row);

        const typeSelect = row.querySelector('.param-type');
        if (typeSelect) {
            typeSelect.value = parameter.type || 'string';
        }
    });

    container.querySelectorAll('.param-name').forEach(function (input) {
        input.addEventListener('input', function () {
            if (contractLocked) {
                return;
            }
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].name = input.value;
        });
    });
    container.querySelectorAll('.param-type').forEach(function (input) {
        input.addEventListener('change', function () {
            if (contractLocked) {
                return;
            }
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].type = input.value;
        });
    });
    container.querySelectorAll('.param-description').forEach(function (input) {
        input.addEventListener('input', function () {
            if (contractLocked) {
                return;
            }
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].description = input.value;
        });
    });
    container.querySelectorAll('.param-array').forEach(function (input) {
        input.addEventListener('change', function () {
            if (contractLocked) {
                return;
            }
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].array = input.checked;
        });
    });
    container.querySelectorAll('.param-required').forEach(function (input) {
        input.addEventListener('change', function () {
            if (contractLocked) {
                return;
            }
            const index = Number(input.getAttribute('data-index'));
            modalParameters[index].required = input.checked;
        });
    });
    container.querySelectorAll('.remove-param').forEach(function (button) {
        button.addEventListener('click', function () {
            if (contractLocked) {
                return;
            }
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
                required: !!parameter.required,
                array: !!parameter.array
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

function setReflectionTab(tab) {
    const requestBtn = document.getElementById('reflection-tab-request');
    const responseBtn = document.getElementById('reflection-tab-response');
    const requestPanel = document.getElementById('reflection-tab-panel-request');
    const responsePanel = document.getElementById('reflection-tab-panel-response');
    const active = tab === 'response' ? 'response' : 'request';

    if (!requestBtn || !responseBtn || !requestPanel || !responsePanel) {
        return;
    }

    const activate = function (button) {
        button.classList.remove('bg-zinc-950', 'text-zinc-400');
        button.classList.add('bg-zinc-900', 'text-zinc-100');
    };
    const deactivate = function (button) {
        button.classList.remove('bg-zinc-900', 'text-zinc-100');
        button.classList.add('bg-zinc-950', 'text-zinc-400');
    };

    if (active === 'request') {
        activate(requestBtn);
        deactivate(responseBtn);
        requestPanel.classList.remove('hidden');
        responsePanel.classList.add('hidden');
    } else {
        activate(responseBtn);
        deactivate(requestBtn);
        responsePanel.classList.remove('hidden');
        requestPanel.classList.add('hidden');
    }
}

function closeBindingModal() {
    hideModal('binding-modal');
}

function closeReflectionPublishModal() {
    hideModal('reflection-publish-modal');
    document.getElementById('reflection-publish-id').value = '';
    document.getElementById('reflection-publish-version').value = '';
    document.getElementById('reflection-publish-pr-title').value = '';
    document.getElementById('reflection-publish-change-summary').value = '';
    document.getElementById('reflection-publish-commit-message').value = '';
    document.getElementById('reflection-publish-pr-body').value = '';
    document.getElementById('reflection-publish-release-notes').value = '';
    document.getElementById('reflection-publish-reviewer-hints').value = '';
    document.getElementById('reflection-publish-breaking-change').checked = false;
    clearReflectionPublishModalAlert();
    setReflectionPublishLoading(false);
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

function showReflectionPublishModalAlert(message, type) {
    document.getElementById('reflection-publish-modal-alert').innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + alertClass(type) + '">' + escapeHtml(message) + '</div>';
}

function clearReflectionPublishModalAlert() {
    document.getElementById('reflection-publish-modal-alert').innerHTML = '';
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
