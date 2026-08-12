/* surfaces-page.js — Vork Surfaces management page */
/* jshint esversion: 6 */

'use strict';

let surfaceModal;
let allSurfaces = [];
let autoSurfaceArtifactIdEnabled = true;

// ── Init ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', function () {
    surfaceModal = new VorkModal(document.getElementById('surface-modal'));
    loadSurfaces();

    document.getElementById('new-surface-btn').addEventListener('click', openCreate);
    document.getElementById('surface-save-btn').addEventListener('click', saveSurface);
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

        const surfaceIdentifier = surface.toolId || surface.uuid;
        const version = surface.version || 'SNAPSHOT';
        const artifactStatus = surface.artifactStatus || 'SNAPSHOT';

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
            + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" onclick="exportSurfacePackage(\'' + escapeJs(surfaceIdentifier) + '\')" title="Export"><i class="fa-solid fa-file-export"></i></button>'
            + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openEdit(\'' + escapeJs(surfaceIdentifier) + '\')" title="Edit"><i class="fa-solid fa-pen"></i></button>'
            + '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deleteSurface(\'' + escapeJs(surfaceIdentifier) + '\')" title="Delete"><i class="fa-solid fa-trash"></i></button>'
            + '  </div>'
            + '</td>';

        body.appendChild(tr);
    });
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
        return s.uuid === identifier || s.toolId === identifier;
    });
    if (!surface) return;

    document.getElementById('surface-uuid').value = surface.toolId || surface.uuid;
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
    const surface = allSurfaces.find(function (s) { return s.uuid === identifier || s.toolId === identifier; });
    const name = surface ? surface.name : 'this surface';
    if (!confirm('Delete "' + name + '"?')) {
        return;
    }
    try {
        const res = await fetch('/api/surfaces/' + encodeURIComponent(identifier), { method: 'DELETE' });
        if (!res.ok) {
            showAlert('Delete failed.', 'warning');
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
