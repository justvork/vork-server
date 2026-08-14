/* surfaces-page.js — Vork Surfaces management page */
/* jshint esversion: 6 */

'use strict';

let surfaceModal;
let surfacePublishModal;
let allSurfaces = [];
let autoSurfaceArtifactIdEnabled = true;
let githubConnection;
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
        clearIdentityValidation('surface-group-id', 'surface-group-id-error');
        clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
        document.getElementById('surface-modal-label').textContent = 'New Surface';
        autoSurfaceArtifactIdEnabled = true;
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
        document.getElementById('surface-publish-breaking-change').checked = false;
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
    clearIdentityValidation('surface-group-id', 'surface-group-id-error');
    clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
    document.getElementById('surface-modal-label').textContent = 'New Surface';
    autoSurfaceArtifactIdEnabled = true;
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
    clearIdentityValidation('surface-group-id', 'surface-group-id-error');
    clearIdentityValidation('surface-artifact-id', 'surface-artifact-id-error');
    document.getElementById('surface-group-id').setAttribute('disabled', 'disabled');
    document.getElementById('surface-artifact-id').setAttribute('disabled', 'disabled');
    document.getElementById('surface-modal-label').textContent = 'Edit Surface';
    autoSurfaceArtifactIdEnabled = false;
    surfaceModal.show();
}

async function saveSurface() {
    const uuid = document.getElementById('surface-uuid').value;
    const name = document.getElementById('surface-name').value.trim();
    const description = document.getElementById('surface-description').value.trim();
    const groupId = document.getElementById('surface-group-id').value.trim();
    const artifactId = document.getElementById('surface-artifact-id').value.trim();

    if (!name) {
        showAlert('Name is required.', 'danger', 'surface-modal-alert');
        return;
    }

    const isCreate = !uuid;
    if (isCreate) {
        const validGroup = validateIdentityField(document.getElementById('surface-group-id'), 'surface-group-id-error', 'Group ID');
        const validArtifact = validateIdentityField(document.getElementById('surface-artifact-id'), 'surface-artifact-id-error', 'Artifact ID');
        if (!groupId || !artifactId || !validGroup || !validArtifact) {
            showAlert('Group ID and Artifact ID are required and must be alphanumeric (3-64 chars).', 'danger', 'surface-modal-alert');
            return;
        }
    }

    const body = isCreate
        ? JSON.stringify({ name: name, description: description, groupId: groupId, artifactId: artifactId })
        : JSON.stringify({ name: name, description: description });
    const url = isCreate ? '/api/surfaces' : '/api/surfaces/' + encodeURIComponent(uuid);

    try {
        const res = await fetch(url, {
            method: isCreate ? 'POST' : 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: body
        });
        if (!res.ok) {
            const data = await res.json().catch(function () { return {}; });
            showAlert(data.error || 'Save failed.', 'danger', 'surface-modal-alert');
            return;
        }
        surfaceModal.hide();
        await loadSurfaces();
    } catch (e) {
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
        document.getElementById('surface-publish-breaking-change').checked = !!draft.breakingChange;

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
    const breakingChange = !!document.getElementById('surface-publish-breaking-change').checked;

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
        'surface-publish-breaking-change'
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
