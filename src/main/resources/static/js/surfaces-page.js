/* surfaces-page.js — Vork Surfaces management page */
/* jshint esversion: 6 */

'use strict';

let surfaceModal;
let surfacePublishModal;
let allSurfaces = [];
let allUsers = [];
let autoSurfaceArtifactIdEnabled = true;
let modalAssignedUsers = [];
let modalLogoDataUrl = '';
let modalSurfaceArtifactStatus = 'SNAPSHOT';
let githubConnection;
const LOGO_MAX_UPLOAD_BYTES = 1024 * 1024;
const LOGO_TARGET_DIMENSION = 512;
const LOGO_MAX_DATA_URL_LENGTH = 1500000;
const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

function contributionPostHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    return headers;
}

// ── Init ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', function () {
    surfaceModal = new VorkModal(document.getElementById('surface-modal'));
    surfacePublishModal = new VorkModal(document.getElementById('surface-publish-modal'));
    githubConnection = window.VorkGitHubConnection
        ? window.VorkGitHubConnection.init({
            alertFn: showAlert
        })
        : null;
    loadSurfaces();
    loadUsers();

    document.getElementById('new-surface-btn').addEventListener('click', openCreate);
    document.getElementById('surface-save-btn').addEventListener('click', saveSurface);
    document.getElementById('surface-publish-submit-btn').addEventListener('click', submitSurfacePublishFromModal);
    const nameInput = document.getElementById('surface-name');
    const artifactInput = document.getElementById('surface-artifact-id');
    const groupInput = document.getElementById('surface-group-id');
    if (nameInput && artifactInput) {
        nameInput.addEventListener('input', function () {
            if (!autoSurfaceArtifactIdEnabled) return;
            artifactInput.value = generateSurfaceArtifactId(nameInput.value);
            validateIdentityField(artifactInput, 'surface-artifact-id-error', 'Artifact ID');
        });
        artifactInput.addEventListener('input', function () {
            autoSurfaceArtifactIdEnabled = false;
            validateIdentityField(artifactInput, 'surface-artifact-id-error', 'Artifact ID');
        });
    }
    if (groupInput) {
        groupInput.addEventListener('input', function () {
            validateIdentityField(groupInput, 'surface-group-id-error', 'Group ID');
        });
    }

    const assignedUserSearch = document.getElementById('surface-assigned-user-search');
    if (assignedUserSearch) {
        assignedUserSearch.addEventListener('input', function () {
            filterAssignedUserDropdown();
        });
        assignedUserSearch.addEventListener('focus', function () {
            filterAssignedUserDropdown();
        });
    }

    const logoFileInput = document.getElementById('surface-logo-file');
    if (logoFileInput) {
        logoFileInput.addEventListener('change', handleLogoFileSelected);
    }

    const navIconSelect = document.getElementById('surface-policy-nav-icon');
    if (navIconSelect) {
        navIconSelect.addEventListener('change', updateNavIconPreview);
    }

    bindToggleButton('surface-published-toggle');
    bindToggleButton('surface-policy-home-toggle');
    bindToggleButton('surface-policy-nav-toggle', updateAccessPolicyInputStates);
    bindToggleButton('surface-policy-private-toggle', updateAccessPolicyInputStates);
    bindToggleButton('surface-policy-public-toggle', updateAccessPolicyInputStates);
    bindToggleButton('surface-publish-breaking-change-toggle');
    initSurfaceModalTabs();
    setSurfaceModalTab('details');

    document.addEventListener('click', function (event) {
        const search = document.getElementById('surface-assigned-user-search');
        const dropdown = document.getElementById('surface-assigned-user-dropdown');
        if (!search || !dropdown) {
            return;
        }
        if (!event.target.closest('#surface-assigned-user-search')
            && !event.target.closest('#surface-assigned-user-dropdown')) {
            dropdown.classList.add('hidden');
        }
    });

    const importBtn = document.getElementById('import-surfaces-btn');
    const importInput = document.getElementById('import-surfaces-input');
    if (importBtn && importInput) {
        importBtn.addEventListener('click', function () {
            importInput.click();
        });
        importInput.addEventListener('change', function () {
            importSurfaces(importInput);
        });
    }

    document.getElementById('surface-modal').addEventListener('hidden.bs.modal', function () {
        clearAlert('surface-modal-alert');
        document.getElementById('surface-uuid').value = '';
        document.getElementById('surface-name').value = '';
        document.getElementById('surface-description').value = '';
        document.getElementById('surface-group-id').value = '';
        document.getElementById('surface-artifact-id').value = '';
        setToggleButtonState('surface-published-toggle', false);
        document.getElementById('surface-logo-file').value = '';
        document.getElementById('surface-assigned-user-search').value = '';
        setToggleButtonState('surface-policy-home-toggle', false);
        setToggleButtonState('surface-policy-nav-toggle', false);
        document.getElementById('surface-policy-nav-icon').value = '';
        setToggleButtonState('surface-policy-private-toggle', false);
        document.getElementById('surface-policy-private-path').value = '';
        setToggleButtonState('surface-policy-public-toggle', false);
        document.getElementById('surface-policy-public-path').value = '';
        clearIdentityValidation('surface-group-id', 'surface-group-id-error');
        clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
        document.getElementById('surface-modal-label').textContent = 'New Surface';
        modalAssignedUsers = [];
        modalLogoDataUrl = '';
        renderAssignedUserPills();
        renderLogoPreview();
        updateAccessPolicyInputStates();
        updateNavIconPreview();
        setSurfaceModalTab('details');
        autoSurfaceArtifactIdEnabled = true;
        modalSurfaceArtifactStatus = 'SNAPSHOT';
        applySurfaceModalEditability('SNAPSHOT', true);
    });

    document.getElementById('surface-publish-modal').addEventListener('hidden.bs.modal', function () {
        clearAlert('surface-publish-modal-alert');
        document.getElementById('surface-publish-id').value = '';
        document.getElementById('surface-publish-version').value = '';
        document.getElementById('surface-publish-pr-title').value = '';
        document.getElementById('surface-publish-change-summary').value = '';
        document.getElementById('surface-publish-commit-message').value = '';
        document.getElementById('surface-publish-pr-body').value = '';
        document.getElementById('surface-publish-release-notes').value = '';
        document.getElementById('surface-publish-reviewer-hints').value = '';
        setToggleButtonState('surface-publish-breaking-change-toggle', false);
        setSurfacePublishLoading(false);
    });
});

// ── Data loading ─────────────────────────────────────────────────────────────

async function loadSurfaces() {
    try {
        const res = await fetch('/api/surfaces');
        if (!res.ok) {
            showAlert('Failed to load surfaces.', 'warning');
            return;
        }
        allSurfaces = await res.json();
        renderTable();
    } catch (e) {
        showAlert('Failed to load surfaces.', 'warning');
    }
}

async function loadUsers() {
    try {
        const res = await fetch('/api/users');
        if (!res.ok) {
            allUsers = [];
            return;
        }
        const users = await res.json();
        allUsers = Array.isArray(users)
            ? users.map(function (u) {
                const username = (u && (u.username || u.uuid)) ? String(u.username || u.uuid).trim() : '';
                return {
                    username: username,
                    displayName: u && u.displayName ? String(u.displayName).trim() : '',
                    role: u && u.role ? String(u.role).trim() : '',
                    enabled: !(u && u.enabled === false)
                };
            }).filter(function (u) {
                return u.username && u.enabled;
            })
            : [];
    } catch (e) {
        allUsers = [];
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

function renderTable() {
    const table = document.getElementById('surface-table');
    const body = document.getElementById('surface-table-body');
    const empty = document.getElementById('no-surfaces');

    body.innerHTML = '';

    if (!allSurfaces || allSurfaces.length === 0) {
        table.classList.add('hidden');
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    table.classList.remove('hidden');

    allSurfaces.forEach(function (surface) {
        const tr = document.createElement('tr');
        tr.id = 'surface-row-' + surface.uuid;
        tr.className = 'border-b border-zinc-800/80 last:border-0';

        const surfaceIdentifier = surface.uuid;
        const contributionIdentifier = surface.uuid;
        const version = surface.version || 'SNAPSHOT';
        const artifactStatus = surface.artifactStatus || 'SNAPSHOT';
        const published = !!surface.published;
        const isSnapshot = artifactStatus === 'SNAPSHOT';
        const canDelete = artifactStatus === 'SNAPSHOT'
            || artifactStatus === 'SUBMITTED'
            || artifactStatus === 'REJECTED';

        const contributionActions = [];
            contributionActions.push('<button type="button" class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="checkSurfaceContributionDependencies(\'' + escapeJs(contributionIdentifier) + '\')" title="Dependency pre-check"><i class="fa-solid fa-list-check"></i></button>');
        if (isSnapshot) {
            contributionActions.push('<button type="button" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15 contrib-action" data-default-title="Publish to staging via PR" onclick="publishSurfaceContribution(\'' + escapeJs(contributionIdentifier) + '\')" title="Publish to staging via PR" disabled><i class="fa-solid fa-cloud-arrow-up"></i></button>');
        } else {
            if (artifactStatus === 'SUBMITTED') {
                contributionActions.push('<button type="button" class="rounded-md border border-blue-500/40 px-2 py-1 text-xs text-blue-300 transition-colors hover:bg-blue-500/15 contrib-action" data-default-title="Refresh status from GitHub" onclick="refreshSurfaceContributionStatus(\'' + escapeJs(contributionIdentifier) + '\')" title="Refresh status from GitHub" disabled><i class="fa-solid fa-rotate-right"></i></button>');
            }
            contributionActions.push('<button type="button" class="rounded-md border border-amber-500/40 px-2 py-1 text-xs text-amber-300 transition-colors hover:bg-amber-500/15 contrib-action" data-default-title="Create SNAPSHOT clone from immutable version" onclick="createSurfaceSnapshotContribution(\'' + escapeJs(contributionIdentifier) + '\')" title="Create SNAPSHOT clone from immutable version" disabled><i class="fa-solid fa-code-branch"></i></button>');
        }

        const deleteAction = canDelete
            ? '<button type="button" class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deleteSurface(\'' + escapeJs(surfaceIdentifier) + '\')" title="Delete"><i class="fa-solid fa-trash"></i></button>'
            : '<button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-500 cursor-not-allowed" title="Only SNAPSHOT, SUBMITTED, or REJECTED surfaces can be deleted" disabled><i class="fa-solid fa-trash"></i></button>';

        tr.innerHTML = ''
            + '<td class="px-3 py-2 font-semibold text-zinc-100">' + escapeHtml(surface.name || '') + '</td>'
            + '<td class="px-3 py-2 text-zinc-300">' + escapeHtml(surface.description || '') + '</td>'
            + '<td class="px-3 py-2 text-xs font-mono text-zinc-400">' + escapeHtml(version) + '</td>'
            + '<td class="px-3 py-2">'
            + '  <span class="artifact-status-pill artifact-status-' + escapeHtml(artifactStatus) + '">' + escapeHtml(artifactStatus) + '</span>'
            + '</td>'
            + '<td class="px-3 py-2 text-xs ' + (published ? 'text-emerald-300' : 'text-zinc-500') + '">' + (published ? 'Yes' : 'No') + '</td>'
            + '<td class="px-3 py-2 text-xs text-zinc-400">' + formatDate(surface.updatedAt) + '</td>'
            + '<td class="px-3 py-2 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <a href="/surface/' + encodeURIComponent(surfaceIdentifier) + '/preview" target="_blank" rel="noopener noreferrer" class="rounded-md border border-emerald-500/40 px-2 py-1 text-xs text-emerald-300 transition-colors hover:bg-emerald-500/15" title="Preview surface"><i class="fa-solid fa-eye"></i></a>'
            + '    <a href="/surfaces/' + encodeURIComponent(surfaceIdentifier) + '/editor" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" title="Open editor"><i class="fa-solid fa-pen-to-square"></i></a>'
            + '    <button type="button" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" onclick="exportSurfacePackage(\'' + escapeJs(surfaceIdentifier) + '\')" title="Export"><i class="fa-solid fa-file-export"></i></button>'
            + '    <button type="button" class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openEdit(\'' + escapeJs(surfaceIdentifier) + '\')" title="Edit"><i class="fa-solid fa-pen"></i></button>'
            + contributionActions.join('')
            + deleteAction
            + '  </div>'
            + '</td>';

        body.appendChild(tr);
    });

    if (githubConnection && typeof githubConnection.refreshStatus === 'function') {
        githubConnection.refreshStatus();
    }
}

// ── Modal actions ────────────────────────────────────────────────────────────

function openCreate() {
    document.getElementById('surface-uuid').value = '';
    document.getElementById('surface-name').value = '';
    document.getElementById('surface-description').value = '';
    document.getElementById('surface-group-id').value = '';
    document.getElementById('surface-artifact-id').value = '';
    setToggleButtonState('surface-published-toggle', false);
    document.getElementById('surface-logo-file').value = '';
    setToggleButtonState('surface-policy-home-toggle', false);
    setToggleButtonState('surface-policy-nav-toggle', false);
    document.getElementById('surface-policy-nav-icon').value = '';
    setToggleButtonState('surface-policy-private-toggle', false);
    document.getElementById('surface-policy-private-path').value = '';
    setToggleButtonState('surface-policy-public-toggle', false);
    document.getElementById('surface-policy-public-path').value = '';
    clearIdentityValidation('surface-group-id', 'surface-group-id-error');
    clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
    modalAssignedUsers = [];
    modalLogoDataUrl = '';
    renderAssignedUserPills();
    renderLogoPreview();
    updateAccessPolicyInputStates();
    updateNavIconPreview();
    setSurfaceModalTab('details');
    document.getElementById('surface-modal-label').textContent = 'New Surface';
    autoSurfaceArtifactIdEnabled = true;
    modalSurfaceArtifactStatus = 'SNAPSHOT';
    applySurfaceModalEditability('SNAPSHOT', true);
    surfaceModal.show();
}

function openEdit(identifier) {
    const surface = allSurfaces.find(function (s) {
        return s.uuid === identifier;
    });
    if (!surface) return;

    document.getElementById('surface-uuid').value = surface.uuid;
    document.getElementById('surface-name').value = surface.name || '';
    document.getElementById('surface-description').value = surface.description || '';
    document.getElementById('surface-group-id').value = surface.groupId || '';
    document.getElementById('surface-artifact-id').value = surface.artifactId || '';
    setToggleButtonState('surface-published-toggle', !!surface.published);
    setToggleButtonState('surface-policy-home-toggle', !!(surface.accessPolicy && surface.accessPolicy.homeScreenEnabled));
    setToggleButtonState('surface-policy-nav-toggle', !!(surface.accessPolicy && surface.accessPolicy.navButtonEnabled));
    document.getElementById('surface-policy-nav-icon').value = (surface.accessPolicy && surface.accessPolicy.navButtonIcon) || '';
    setToggleButtonState('surface-policy-private-toggle', !!(surface.accessPolicy && surface.accessPolicy.privateUrlEnabled));
    document.getElementById('surface-policy-private-path').value = (surface.accessPolicy && surface.accessPolicy.privateUrlPath) || '';
    setToggleButtonState('surface-policy-public-toggle', !!(surface.accessPolicy && surface.accessPolicy.publicUrlEnabled));
    document.getElementById('surface-policy-public-path').value = (surface.accessPolicy && surface.accessPolicy.publicUrlPath) || '';
    document.getElementById('surface-logo-file').value = '';
    modalLogoDataUrl = surface.logoDataUrl || '';
    modalAssignedUsers = Array.isArray(surface.assignedUserUuids) ? surface.assignedUserUuids.slice() : [];
    renderAssignedUserPills();
    renderLogoPreview();
    updateAccessPolicyInputStates();
    updateNavIconPreview();
    setSurfaceModalTab('details');
    clearIdentityValidation('surface-group-id', 'surface-group-id-error');
    clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
    document.getElementById('surface-group-id').setAttribute('disabled', 'disabled');
    document.getElementById('surface-artifact-id').setAttribute('disabled', 'disabled');
    document.getElementById('surface-modal-label').textContent = 'Edit Surface';
    autoSurfaceArtifactIdEnabled = false;
    modalSurfaceArtifactStatus = surface.artifactStatus || 'SNAPSHOT';
    applySurfaceModalEditability(modalSurfaceArtifactStatus, false);
    surfaceModal.show();
}

function applySurfaceModalEditability(artifactStatus, isCreate) {
    const status = (artifactStatus || 'SNAPSHOT').toUpperCase();
    const immutable = !isCreate && status !== 'SNAPSHOT';

    const immutableNote = document.getElementById('surface-immutable-note');
    if (immutableNote) {
        immutableNote.classList.toggle('hidden', !immutable);
    }

    setElementDisabled('surface-name', immutable);
    setElementDisabled('surface-description', immutable);

    // Group/artifact identity is create-only.
    setElementDisabled('surface-group-id', !isCreate);
    setElementDisabled('surface-artifact-id', !isCreate);

    // Publication settings remain editable on immutable versions.
    setElementDisabled('surface-published-toggle', false);
    setElementDisabled('surface-assigned-user-search', false);
    setElementDisabled('surface-policy-home-toggle', false);
    setElementDisabled('surface-policy-nav-toggle', false);
    setElementDisabled('surface-policy-private-toggle', false);
    setElementDisabled('surface-policy-public-toggle', false);

    // Logo belongs to artifact content, not publication settings.
    setElementDisabled('surface-logo-file', immutable);

    updateAccessPolicyInputStates();

    const saveBtn = document.getElementById('surface-save-btn');
    if (saveBtn) {
        saveBtn.textContent = immutable ? 'Save Publication Settings' : 'Save';
    }
}

function setElementDisabled(elementId, disabled) {
    const element = document.getElementById(elementId);
    if (!element) {
        return;
    }
    if (disabled) {
        element.setAttribute('disabled', 'disabled');
    } else {
        element.removeAttribute('disabled');
    }
}

async function saveSurface() {
    const uuid = document.getElementById('surface-uuid').value;
    const name = document.getElementById('surface-name').value.trim();
    const description = document.getElementById('surface-description').value.trim();
    const groupId = document.getElementById('surface-group-id').value.trim();
    const artifactId = document.getElementById('surface-artifact-id').value.trim();
    const published = getToggleButtonState('surface-published-toggle');
    const accessPolicy = {
        homeScreenEnabled: getToggleButtonState('surface-policy-home-toggle'),
        navButtonEnabled: getToggleButtonState('surface-policy-nav-toggle'),
        navButtonIcon: document.getElementById('surface-policy-nav-icon').value.trim(),
        privateUrlEnabled: getToggleButtonState('surface-policy-private-toggle'),
        privateUrlPath: document.getElementById('surface-policy-private-path').value.trim(),
        publicUrlEnabled: getToggleButtonState('surface-policy-public-toggle'),
        publicUrlPath: document.getElementById('surface-policy-public-path').value.trim()
    };

    if (!name) {
        setSurfaceModalTab('details');
        showAlert('Name is required.', 'danger', 'surface-modal-alert');
        return;
    }

    const isCreate = !uuid;
    if (isCreate) {
        const validGroup = validateIdentityField(document.getElementById('surface-group-id'), 'surface-group-id-error', 'Group ID');
        const validArtifact = validateIdentityField(document.getElementById('surface-artifact-id'), 'surface-artifact-id-error', 'Artifact ID');
        if (!groupId || !artifactId || !validGroup || !validArtifact) {
            setSurfaceModalTab('details');
            showAlert('Group ID and Artifact ID are required and must be alphanumeric (3-64 chars).', 'danger', 'surface-modal-alert');
            return;
        }
    }

    const body = isCreate
        ? JSON.stringify({
            name: name,
            description: description,
            groupId: groupId,
            artifactId: artifactId,
            published: published,
            logoDataUrl: modalLogoDataUrl,
            assignedUserUuids: modalAssignedUsers.slice(),
            accessPolicy: accessPolicy
        })
        : JSON.stringify({
            name: name,
            description: description,
            published: published,
            logoDataUrl: modalLogoDataUrl,
            assignedUserUuids: modalAssignedUsers.slice(),
            accessPolicy: accessPolicy
        });
    const url = isCreate ? '/api/surfaces' : '/api/surfaces/' + encodeURIComponent(uuid);

    try {
        const res = await fetch(url, {
            method: isCreate ? 'POST' : 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: body
        });
        if (!res.ok) {
            const data = await res.json().catch(function () { return {}; });
            const errorMessage = data.error || 'Save failed.';
            focusSurfaceTabForError(errorMessage);
            showAlert(errorMessage, 'danger', 'surface-modal-alert');
            return;
        }
        surfaceModal.hide();
        await loadSurfaces();
    } catch (e) {
        focusSurfaceTabForError(e && e.message ? e.message : '');
        showAlert('Save failed: ' + e.message, 'danger', 'surface-modal-alert');
    }
}

async function deleteSurface(identifier) {
    const surface = allSurfaces.find(function (s) { return s.uuid === identifier; });
    const name = surface ? surface.name : 'this surface';
    const status = (surface && surface.artifactStatus) ? surface.artifactStatus : 'SNAPSHOT';
    if (status !== 'SNAPSHOT' && status !== 'SUBMITTED' && status !== 'REJECTED') {
        showAlert('Only SNAPSHOT, SUBMITTED, or REJECTED surfaces can be deleted. This surface is ' + status + '.', 'warning');
        return;
    }
    if (!confirm('Delete "' + name + '"?')) {
        return;
    }
    try {
        const res = await fetch('/api/surfaces/' + encodeURIComponent(identifier), { method: 'DELETE' });
        if (!res.ok) {
            const data = await res.json().catch(function () { return {}; });
            showAlert(data.error || data.message || ('Delete failed (HTTP ' + res.status + ').'), 'warning');
            return;
        }
        await loadSurfaces();
    } catch (e) {
        showAlert('Delete failed: ' + e.message, 'warning');
    }
}

function exportSurfacePackage(uuid) {
    if (!uuid) {
        showAlert('Surface id is missing for export.', 'warning');
        return;
    }
    window.location.href = '/api/surfaces/' + encodeURIComponent(uuid) + '/export';
}

function slugifySurfaceArtifactId(raw) {
    const normalized = (raw || '').toString().replace(/[^A-Za-z0-9]/g, '');
    if (!normalized) return 'surface';
    if (normalized.length >= 3) return normalized;
    return (normalized + 'surface').slice(0, 7);
}

function generateSurfaceArtifactId(name) {
    return slugifySurfaceArtifactId(name);
}

function validateIdentityField(inputEl, errorId, fieldLabel) {
    const value = (inputEl.value || '').trim();
    const errorEl = document.getElementById(errorId);
    const isValid = /^[A-Za-z0-9]{3,64}$/.test(value);
    if (!isValid) {
        inputEl.classList.add('border-rose-500');
        if (errorEl) {
            errorEl.textContent = fieldLabel + ' must be alphanumeric and 3-64 characters.';
        }
    } else {
        inputEl.classList.remove('border-rose-500');
        if (errorEl) {
            errorEl.textContent = '';
        }
    }
    return isValid;
}

function clearIdentityValidation(inputId, errorId) {
    const inputEl = document.getElementById(inputId);
    const errorEl = document.getElementById(errorId);
    if (inputEl) {
        inputEl.classList.remove('border-rose-500');
        inputEl.removeAttribute('disabled');
    }
    if (errorEl) {
        errorEl.textContent = '';
    }
}

async function handleLogoFileSelected(event) {
    const input = event && event.target;
    if (!input || !input.files || input.files.length === 0) {
        modalLogoDataUrl = '';
        renderLogoPreview();
        return;
    }
    const file = input.files[0];
    if (!file || !file.type || file.type.indexOf('image/') !== 0) {
        showAlert('Logo must be an image file.', 'warning', 'surface-modal-alert');
        input.value = '';
        return;
    }

    if (file.size > LOGO_MAX_UPLOAD_BYTES) {
        showAlert('Logo is too large. Max upload size is 1 MB.', 'warning', 'surface-modal-alert');
        input.value = '';
        return;
    }

    try {
        const sourceDataUrl = await fileToDataUrl(file);
        const img = await loadImage(sourceDataUrl);
        modalLogoDataUrl = cropSquareImageToPng(img, LOGO_TARGET_DIMENSION);
        if (img.naturalWidth !== img.naturalHeight) {
            showAlert('Logo was auto-cropped to a square image.', 'success', 'surface-modal-alert');
        }
        if (modalLogoDataUrl.length > LOGO_MAX_DATA_URL_LENGTH) {
            throw new Error('Logo output is too large. Try a smaller image.');
        }
        renderLogoPreview();
    } catch (e) {
        showAlert(e && e.message ? e.message : 'Failed to process logo file.', 'warning', 'surface-modal-alert');
        modalLogoDataUrl = '';
        renderLogoPreview();
        input.value = '';
    }
}

function fileToDataUrl(file) {
    return new Promise(function (resolve, reject) {
        const reader = new FileReader();
        reader.onload = function () { resolve(String(reader.result || '')); };
        reader.onerror = function () { reject(new Error('file-read-failed')); };
        reader.readAsDataURL(file);
    });
}

function loadImage(dataUrl) {
    return new Promise(function (resolve, reject) {
        const image = new Image();
        image.onload = function () { resolve(image); };
        image.onerror = function () { reject(new Error('Failed to decode image.')); };
        image.src = dataUrl;
    });
}

function cropSquareImageToPng(image, targetDimension) {
    const sourceWidth = image.naturalWidth || image.width;
    const sourceHeight = image.naturalHeight || image.height;
    if (!sourceWidth || !sourceHeight) {
        throw new Error('Invalid image dimensions.');
    }

    const side = Math.min(sourceWidth, sourceHeight);
    const offsetX = Math.floor((sourceWidth - side) / 2);
    const offsetY = Math.floor((sourceHeight - side) / 2);
    const output = Math.min(targetDimension, side);

    const canvas = document.createElement('canvas');
    canvas.width = output;
    canvas.height = output;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
        throw new Error('Canvas is not available for image processing.');
    }
    ctx.drawImage(image, offsetX, offsetY, side, side, 0, 0, output, output);
    return canvas.toDataURL('image/png');
}

function renderLogoPreview() {
    const preview = document.getElementById('surface-logo-preview');
    if (!preview) {
        return;
    }
    if (!modalLogoDataUrl) {
        preview.removeAttribute('src');
        preview.classList.add('hidden');
        return;
    }
    preview.src = modalLogoDataUrl;
    preview.classList.remove('hidden');
}

function renderAssignedUserPills() {
    const container = document.getElementById('surface-assigned-user-pills');
    if (!container) {
        return;
    }
    container.innerHTML = '';
    if (!modalAssignedUsers.length) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No users assigned.</span>';
        return;
    }

    modalAssignedUsers.forEach(function (username) {
        const user = allUsers.find(function (u) { return u.username === username; });
        const label = user && user.displayName ? user.displayName + ' (' + username + ')' : username;
        const pill = document.createElement('span');
        pill.className = 'surface-user-pill';
        pill.innerHTML = '<span>' + escapeHtml(label) + '</span><button type="button" title="Remove user">&times;</button>';
        pill.querySelector('button').addEventListener('click', function () {
            modalAssignedUsers = modalAssignedUsers.filter(function (u) { return u !== username; });
            renderAssignedUserPills();
            filterAssignedUserDropdown();
        });
        container.appendChild(pill);
    });
}

function filterAssignedUserDropdown() {
    const search = document.getElementById('surface-assigned-user-search');
    const dropdown = document.getElementById('surface-assigned-user-dropdown');
    if (!search || !dropdown) {
        return;
    }
    const query = (search.value || '').trim().toLowerCase();
    const matches = allUsers.filter(function (u) {
        if (!u || !u.username) {
            return false;
        }
        if (modalAssignedUsers.includes(u.username)) {
            return false;
        }
        if (!query) {
            return true;
        }
        const text = ((u.username || '') + ' ' + (u.displayName || '') + ' ' + (u.role || '')).toLowerCase();
        return text.includes(query);
    }).slice(0, 20);

    dropdown.innerHTML = '';
    if (!matches.length) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (u) {
        const row = document.createElement('div');
        row.className = 'surface-assigned-user-item';
        row.innerHTML = '<div>' + escapeHtml(u.displayName || u.username) + '</div><div class="text-xs text-zinc-500">' + escapeHtml(u.username) + (u.role ? ' • ' + escapeHtml(u.role) : '') + '</div>';
        row.addEventListener('click', function () {
            modalAssignedUsers.push(u.username);
            search.value = '';
            dropdown.classList.add('hidden');
            renderAssignedUserPills();
        });
        dropdown.appendChild(row);
    });
    dropdown.classList.remove('hidden');
}

function updateAccessPolicyInputStates() {
    const navEnabled = getToggleButtonState('surface-policy-nav-toggle');
    const privateEnabled = getToggleButtonState('surface-policy-private-toggle');
    const publicEnabled = getToggleButtonState('surface-policy-public-toggle');

    document.getElementById('surface-policy-nav-icon').disabled = !navEnabled;
    document.getElementById('surface-policy-private-path').disabled = !privateEnabled;
    document.getElementById('surface-policy-public-path').disabled = !publicEnabled;
    updateNavIconPreview();
}

function updateNavIconPreview() {
    const icon = document.getElementById('surface-policy-nav-icon');
    const preview = document.getElementById('surface-policy-nav-icon-preview');
    const previewIcon = document.getElementById('surface-policy-nav-icon-preview-icon');
    const classLabel = document.getElementById('surface-policy-nav-icon-class');
    if (!icon || !preview || !previewIcon || !classLabel) {
        return;
    }
    const selectedClass = (icon.value || '').trim() || 'fa-solid fa-layer-group';
    previewIcon.className = selectedClass;
    classLabel.textContent = selectedClass;
    if (icon.disabled) {
        preview.classList.add('is-disabled');
        classLabel.classList.add('is-disabled');
    } else {
        preview.classList.remove('is-disabled');
        classLabel.classList.remove('is-disabled');
    }
}

function initSurfaceModalTabs() {
    const tabButtons = document.querySelectorAll('[data-surface-tab-btn]');
    tabButtons.forEach(function (button) {
        button.addEventListener('click', function () {
            setSurfaceModalTab(button.getAttribute('data-surface-tab-btn') || 'details');
        });
    });
}

function setSurfaceModalTab(tabName) {
    const target = tabName || 'details';
    const tabButtons = document.querySelectorAll('[data-surface-tab-btn]');
    const tabPanels = document.querySelectorAll('[data-surface-tab-panel]');

    tabButtons.forEach(function (button) {
        const isActive = button.getAttribute('data-surface-tab-btn') === target;
        button.classList.toggle('is-active', isActive);
        button.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });

    tabPanels.forEach(function (panel) {
        const isActive = panel.getAttribute('data-surface-tab-panel') === target;
        panel.classList.toggle('hidden', !isActive);
    });
}

function focusSurfaceTabForError(errorMessage) {
    const text = (errorMessage || '').toLowerCase();
    if (!text) {
        return;
    }

    if (text.includes('group id') || text.includes('artifact id') || text.includes('description') || text.includes('name')) {
        setSurfaceModalTab('details');
        return;
    }

    if (text.includes('assigned user') || text.includes('logo') || text.includes('published')) {
        setSurfaceModalTab('publication');
        return;
    }

    if (text.includes('private') || text.includes('public') || text.includes('path') || text.includes('route')
        || text.includes('nav button') || text.includes('icon') || text.includes('home screen') || text.includes('policy')) {
        setSurfaceModalTab('routes');
        return;
    }
}

function bindToggleButton(buttonId, onChange) {
    if (window.VorkToggleUtil && typeof window.VorkToggleUtil.bind === 'function') {
        window.VorkToggleUtil.bind(buttonId, function () {
            if (typeof onChange === 'function') {
                onChange();
            }
        });
        return;
    }

    const control = document.getElementById(buttonId);
    if (!control) {
        return;
    }

    if (control.matches('input[type="checkbox"]')) {
        control.addEventListener('change', function () {
            if (typeof onChange === 'function') {
                onChange();
            }
        });
    } else {
        control.addEventListener('click', function () {
            const next = !getToggleButtonState(buttonId);
            setToggleButtonState(buttonId, next);
            if (typeof onChange === 'function') {
                onChange();
            }
        });
    }

    setToggleButtonState(buttonId, getToggleButtonState(buttonId));
}

function setToggleButtonState(buttonId, enabled) {
    if (window.VorkToggleUtil && typeof window.VorkToggleUtil.setState === 'function') {
        window.VorkToggleUtil.setState(buttonId, enabled);
        return;
    }

    const control = document.getElementById(buttonId);
    if (!control) {
        return;
    }

    if (control.matches('input[type="checkbox"]')) {
        control.checked = !!enabled;
        return;
    }

    control.setAttribute('aria-pressed', enabled ? 'true' : 'false');
    control.textContent = enabled ? 'On' : 'Off';
}

function getToggleButtonState(buttonId) {
    if (window.VorkToggleUtil && typeof window.VorkToggleUtil.getState === 'function') {
        return window.VorkToggleUtil.getState(buttonId);
    }

    const control = document.getElementById(buttonId);
    if (!control) {
        return false;
    }

    if (control.matches('input[type="checkbox"]')) {
        return !!control.checked;
    }

    return control.getAttribute('aria-pressed') === 'true';
}

async function importSurfaces(input) {
    const file = input && input.files && input.files[0];
    if (!file) return;

    try {
        const formData = new FormData();
        formData.append('file', file);
        const res = await fetch('/api/surfaces/import', {
            method: 'POST',
            body: formData
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.status === 'error') {
            showAlert(data.message || 'Surface import failed.', 'danger');
            return;
        }
        showAlert('Surface imported successfully.', 'success');
        await loadSurfaces();
    } catch (e) {
        showAlert('Network error during surface import: ' + (e.message || 'Unknown error'), 'danger');
    } finally {
        if (input) input.value = '';
    }
}

async function checkSurfaceContributionDependencies(id) {
    if (!id) {
        showAlert('Surface id is required for dependency pre-check.', 'warning');
        return;
    }
    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndDisplay === 'function') {
        await window.VorkDependencyPrecheck.runAndDisplay('surfaces', id, 'Surface', showAlert);
        return;
    }
    showAlert('Dependency pre-check helper is not available on this page.', 'warning');
}

async function publishSurfaceContribution(id) {
    if (!id) {
        showAlert('Surface id is required for publish.', 'warning');
        return;
    }

    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndGate === 'function') {
        const ready = await window.VorkDependencyPrecheck.runAndGate('surfaces', id, 'Surface', showAlert);
        if (!ready) {
            return;
        }
    }

    document.getElementById('surface-publish-id').value = id;
    clearAlert('surface-publish-modal-alert');
    setSurfacePublishLoading(true);
    surfacePublishModal.show();

    try {
        const draftRes = await fetch('/api/contributions/surfaces/' + encodeURIComponent(id) + '/publish-draft', {
            method: 'POST',
            headers: contributionPostHeaders(),
            body: JSON.stringify({})
        });
        const draftData = await draftRes.json().catch(function () { return {}; });
        if (!draftRes.ok || draftData.error) {
            showAlert(draftData.error || draftData.message || 'Failed to generate publish draft.', 'danger', 'surface-publish-modal-alert');
            setSurfacePublishLoading(false);
            return;
        }

        const draft = draftData.draft || {};
        document.getElementById('surface-publish-version').value = (draft.version || '').trim();
        document.getElementById('surface-publish-pr-title').value = (draft.prTitle || '').trim();
        document.getElementById('surface-publish-change-summary').value = (draft.changeSummary || '').trim();
        document.getElementById('surface-publish-commit-message').value = (draft.commitMessage || '').trim();
        document.getElementById('surface-publish-pr-body').value = (draft.prBody || '').trim();
        document.getElementById('surface-publish-release-notes').value = (draft.releaseNotes || '').trim();
        document.getElementById('surface-publish-reviewer-hints').value = (draft.reviewerHints || '').trim();
        setToggleButtonState('surface-publish-breaking-change-toggle', !!draft.breakingChange);

        if (draft.latestVersion) {
            showAlert('Latest in staging: ' + draft.latestVersion + '. Draft generated and ready to edit.', 'success', 'surface-publish-modal-alert');
        }

        setSurfacePublishLoading(false);
    } catch (_e) {
        showAlert('Network error during draft generation.', 'danger', 'surface-publish-modal-alert');
        setSurfacePublishLoading(false);
    }
}

async function submitSurfacePublishFromModal() {
    const id = document.getElementById('surface-publish-id').value;
    const version = document.getElementById('surface-publish-version').value.trim();
    const prTitle = document.getElementById('surface-publish-pr-title').value.trim();
    const changeSummary = document.getElementById('surface-publish-change-summary').value.trim();
    const commitMessage = document.getElementById('surface-publish-commit-message').value.trim();
    const prBody = document.getElementById('surface-publish-pr-body').value.trim();
    const releaseNotes = document.getElementById('surface-publish-release-notes').value.trim();
    const reviewerHints = document.getElementById('surface-publish-reviewer-hints').value.trim();
    const breakingChange = getToggleButtonState('surface-publish-breaking-change-toggle');

    if (!id) {
        showAlert('Surface id is missing for publish.', 'danger', 'surface-publish-modal-alert');
        return;
    }
    if (!/^[0-9]+\.[0-9]+$/.test(version) || version.toUpperCase() === 'SNAPSHOT') {
        showAlert('Version must follow major.minor and cannot be SNAPSHOT.', 'danger', 'surface-publish-modal-alert');
        return;
    }
    if (!prTitle) {
        showAlert('PR title is required.', 'danger', 'surface-publish-modal-alert');
        return;
    }
    if (!changeSummary) {
        showAlert('Change summary is required.', 'danger', 'surface-publish-modal-alert');
        return;
    }

    setSurfacePublishLoading(true, 'Creating PR...');
    clearAlert('surface-publish-modal-alert');

    try {
        const publishRes = await fetch('/api/contributions/surfaces/' + encodeURIComponent(id) + '/publish', {
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
            showAlert(publishData.error || publishData.message || 'Publish failed.', 'danger', 'surface-publish-modal-alert');
            setSurfacePublishLoading(false);
            return;
        }

        const pullRequest = publishData.pullRequest || {};
        showAlert('Published. PR: ' + (pullRequest.url || 'created'), 'success');
        surfacePublishModal.hide();
        setTimeout(function () { location.reload(); }, 900);
    } catch (_e) {
        showAlert('Network error during publish.', 'danger', 'surface-publish-modal-alert');
        setSurfacePublishLoading(false);
    }
}

function setSurfacePublishLoading(isLoading, loadingLabel) {
    const fields = [
        'surface-publish-version',
        'surface-publish-pr-title',
        'surface-publish-change-summary',
        'surface-publish-commit-message',
        'surface-publish-pr-body',
        'surface-publish-release-notes',
        'surface-publish-reviewer-hints',
        'surface-publish-breaking-change-toggle'
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

    const submitBtn = document.getElementById('surface-publish-submit-btn');
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

async function createSurfaceSnapshotContribution(id) {
    if (!id) {
        showAlert('Surface id is required for snapshot.', 'warning');
        return;
    }
    if (!window.confirm('Create a SNAPSHOT clone from this immutable surface?')) {
        return;
    }
    try {
        const res = await fetch('/api/contributions/surfaces/' + encodeURIComponent(id) + '/snapshot', {
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

async function refreshSurfaceContributionStatus(id) {
    if (!id) {
        showAlert('Surface id is required to refresh status.', 'warning');
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
        const promoted = (summary.surfacesPromotedToStaged || 0);
        showAlert('Status refresh complete. Surfaces promoted to STAGED: ' + promoted + '.', 'success');
        await loadSurfaces();
    } catch (_e) {
        showAlert('Network error refreshing contribution status.', 'danger');
    }
}

// ── Utilities ────────────────────────────────────────────────────────────────

function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function escapeJs(s) {
    if (s == null) return '';
    return String(s)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/"/g, '\\"')
        .replace(/\n/g, '\\n')
        .replace(/\r/g, '\\r');
}

function formatDate(ts) {
    if (!ts) return '—';
    try {
        return new Date(ts).toLocaleString();
    } catch (e) {
        return '—';
    }
}

function showAlert(message, level, targetId) {
    const id = targetId || 'alert-area';
    const el = document.getElementById(id);
    if (!el) return;

    const colors = {
        danger: 'border-rose-500/40 bg-rose-500/10 text-rose-200',
        warning: 'border-amber-500/40 bg-amber-500/10 text-amber-200',
        success: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
    };

    const alert = document.createElement('div');
    alert.className = 'alert rounded-lg border px-3 py-2 text-sm ' + (colors[level] || colors.warning);
    alert.innerHTML = '<span>' + escapeHtml(message) + '</span>'
        + '<button type="button" class="ml-2 text-zinc-400 hover:text-zinc-200" data-bs-dismiss="alert" aria-label="Close">×</button>';
    el.appendChild(alert);
}

function clearAlert(targetId) {
    const el = document.getElementById(targetId);
    if (el) el.innerHTML = '';
}
