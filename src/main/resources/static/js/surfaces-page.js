/* surfaces-page.js — Vork Surfaces management page */
/* jshint esversion: 6 */

'use strict';

let surfaceModal;
let allSurfaces = [];

// ── Init ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', function () {
    surfaceModal = new VorkModal(document.getElementById('surface-modal'));
    loadSurfaces();

    document.getElementById('new-surface-btn').addEventListener('click', openCreate);
    document.getElementById('surface-save-btn').addEventListener('click', saveSurface);

    document.getElementById('surface-modal').addEventListener('hidden.bs.modal', function () {
        clearAlert('surface-modal-alert');
        document.getElementById('surface-uuid').value = '';
        document.getElementById('surface-name').value = '';
        document.getElementById('surface-description').value = '';
        document.getElementById('surface-modal-label').textContent = 'New Surface';
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

        tr.innerHTML = ''
            + '<td class="px-3 py-2 font-semibold text-zinc-100">' + escapeHtml(surface.name || '') + '</td>'
            + '<td class="px-3 py-2 text-zinc-300">' + escapeHtml(surface.description || '') + '</td>'
            + '<td class="px-3 py-2 text-xs text-zinc-400">' + formatDate(surface.updatedAt) + '</td>'
            + '<td class="px-3 py-2 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <a href="/surfaces/' + encodeURIComponent(surface.uuid) + '/editor" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" title="Open editor"><i class="fa-solid fa-pen-to-square"></i></a>'
            + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openEdit(\'' + escapeJs(surface.uuid) + '\')" title="Edit"><i class="fa-solid fa-pen"></i></button>'
            + '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deleteSurface(\'' + escapeJs(surface.uuid) + '\')" title="Delete"><i class="fa-solid fa-trash"></i></button>'
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
    document.getElementById('surface-modal-label').textContent = 'New Surface';
    surfaceModal.show();
}

function openEdit(uuid) {
    const surface = allSurfaces.find(function (s) { return s.uuid === uuid; });
    if (!surface) return;

    document.getElementById('surface-uuid').value = surface.uuid;
    document.getElementById('surface-name').value = surface.name || '';
    document.getElementById('surface-description').value = surface.description || '';
    document.getElementById('surface-modal-label').textContent = 'Edit Surface';
    surfaceModal.show();
}

async function saveSurface() {
    const uuid = document.getElementById('surface-uuid').value;
    const name = document.getElementById('surface-name').value.trim();
    const description = document.getElementById('surface-description').value.trim();

    if (!name) {
        showAlert('Name is required.', 'danger', 'surface-modal-alert');
        return;
    }

    const body = JSON.stringify({ name: name, description: description });
    const isCreate = !uuid;
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

async function deleteSurface(uuid) {
    const surface = allSurfaces.find(function (s) { return s.uuid === uuid; });
    const name = surface ? surface.name : 'this surface';
    if (!confirm('Delete "' + name + '"?')) {
        return;
    }
    try {
        const res = await fetch('/api/surfaces/' + encodeURIComponent(uuid), { method: 'DELETE' });
        if (!res.ok) {
            showAlert('Delete failed.', 'warning');
            return;
        }
        await loadSurfaces();
    } catch (e) {
        showAlert('Delete failed: ' + e.message, 'warning');
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
