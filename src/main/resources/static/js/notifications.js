/* notifications.js — Vork Notifications settings page */
/* jshint esversion: 6 */

let providerModal;
let allProviders = [];   // [{providerKey, displayName, settingDefinitions}]
let allConfigs   = [];   // [{uuid, providerKey, displayName, settings}]
let editingId    = null; // uuid of config being edited, or null for create

// -- Init --------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', function () {
    providerModal = new VorkModal(document.getElementById('providerModal'));
    document.getElementById('providerModal').addEventListener('hidden.bs.modal', resetModal);
    loadAll();
});

async function loadAll() {
    try {
        const results = await Promise.all([
            fetch('/api/notifications/providers'),
            fetch('/api/notifications/configs')
        ]);
        const pRes = results[0];
        const cRes = results[1];

        allProviders = pRes.ok ? await pRes.json() : [];
        allConfigs = cRes.ok ? await cRes.json() : [];

        const sel = document.getElementById('provider-type-select');
        sel.innerHTML = '<option value="">-- select a provider --</option>';
        allProviders.forEach(function (p) {
            const opt = document.createElement('option');
            opt.value = p.providerKey;
            opt.textContent = p.displayName;
            sel.appendChild(opt);
        });

        renderConfigs();
    } catch (_e) {
        showAlert('Failed to load notification data.', 'warning');
    }
}

// -- Render config list ------------------------------------------------------
function renderConfigs() {
    const list = document.getElementById('config-list');
    const empty = document.getElementById('empty-state');
    list.innerHTML = '';

    if (allConfigs.length === 0) {
        list.classList.add('hidden');
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    list.classList.remove('hidden');

    allConfigs.forEach(function (cfg) {
        const provider = allProviders.find(function (p) { return p.providerKey === cfg.providerKey; });
        const providerLabel = provider ? provider.displayName : cfg.providerKey;

        const card = document.createElement('div');
        card.className = 'mb-3 rounded-xl border border-zinc-800 bg-zinc-900 p-3';
        card.id = 'card-' + cfg.uuid;
        card.innerHTML =
            '<div class="flex items-center justify-between gap-3">' +
            '  <div>' +
            '    <div class="font-semibold text-zinc-100">' + escapeHtml(cfg.displayName) + '</div>' +
            '    <div class="mt-1 text-xs text-zinc-400">' +
            '      <span class="mr-1 inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-1.5 py-0.5 text-[0.7rem] text-zinc-300">' + escapeHtml(providerLabel) + '</span>' +
            buildSettingsPills(cfg, provider) +
            '    </div>' +
            '  </div>' +
            '  <div class="flex shrink-0 gap-2">' +
            '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openEditModal(\'' + cfg.uuid + '\')" title="Edit">' +
            '      <i class="fa-solid fa-pen"></i>' +
            '    </button>' +
            '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deleteConfig(\'' + cfg.uuid + '\')" title="Delete">' +
            '      <i class="fa-solid fa-trash"></i>' +
            '    </button>' +
            '  </div>' +
            '</div>';
        list.appendChild(card);
    });
}

function buildSettingsPills(cfg, provider) {
    if (!provider) return '';
    return provider.settingDefinitions
        .filter(function (d) { return d.type !== 'password'; })
        .map(function (d) {
            const val = cfg.settings[d.key];
            if (!val) return '';
            return '<span class="mr-2">' +
                escapeHtml(d.label) + ': <span class="text-zinc-300">' +
                escapeHtml(val) + '</span></span>';
        }).join('');
}

// -- Modal: Add --------------------------------------------------------------
function openAddModal() {
    editingId = null;
    document.getElementById('providerModalLabel').textContent = 'Add Notification Provider';
    document.getElementById('config-id').value = '';
    document.getElementById('config-provider-key').value = '';
    document.getElementById('config-display-name').value = '';
    document.getElementById('provider-type-select').value = '';
    document.getElementById('step-choose').classList.remove('hidden');
    document.getElementById('step-settings').classList.add('hidden');
    document.getElementById('btn-save').classList.add('hidden');
    document.getElementById('dynamic-fields').innerHTML = '';
    providerModal.show();
}

function onProviderTypeChange() {
    const key = document.getElementById('provider-type-select').value;
    if (!key) {
        document.getElementById('step-settings').classList.add('hidden');
        document.getElementById('btn-save').classList.add('hidden');
        return;
    }

    const provider = allProviders.find(function (p) { return p.providerKey === key; });
    if (!provider) return;

    document.getElementById('config-display-name').value = provider.displayName;
    renderSettingsFields(provider.settingDefinitions, {});
    document.getElementById('step-settings').classList.remove('hidden');
    document.getElementById('btn-save').classList.remove('hidden');
}

// -- Modal: Edit -------------------------------------------------------------
function openEditModal(id) {
    const cfg = allConfigs.find(function (c) { return c.uuid === id; });
    if (!cfg) {
        showAlert('Config not found -- reload the page.', 'warning');
        return;
    }

    const provider = allProviders.find(function (p) { return p.providerKey === cfg.providerKey; });

    editingId = id;
    document.getElementById('providerModalLabel').textContent = 'Edit: ' + cfg.displayName;
    document.getElementById('config-id').value = cfg.uuid;
    document.getElementById('config-provider-key').value = cfg.providerKey;
    document.getElementById('config-display-name').value = cfg.displayName;

    document.getElementById('step-choose').classList.add('hidden');
    document.getElementById('step-settings').classList.remove('hidden');
    document.getElementById('btn-save').classList.remove('hidden');

    renderSettingsFields(provider ? provider.settingDefinitions : [], cfg.settings);
    providerModal.show();
}

// -- Dynamic fields ----------------------------------------------------------
function renderSettingsFields(defs, currentValues) {
    const container = document.getElementById('dynamic-fields');
    container.innerHTML = '';

    defs.forEach(function (d) {
        const wrapper = document.createElement('div');
        wrapper.className = 'mb-3';

        const label = document.createElement('label');
        label.className = 'mb-1 block text-sm font-medium text-zinc-300';
        label.htmlFor = 'field-' + d.key;
        label.textContent = d.label;

        if (d.required) {
            const star = document.createElement('span');
            star.className = 'ml-1 text-rose-400';
            star.textContent = '*';
            label.appendChild(star);
        }

        const input = document.createElement('input');
        input.type = d.type === 'email' ? 'email' : (d.type === 'password' ? 'password' : 'text');
        input.id = 'field-' + d.key;
        input.dataset.key = d.key;
        input.className = 'w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/25';
        input.placeholder = d.placeholder || '';
        input.value = currentValues[d.key] || '';

        const feedback = document.createElement('div');
        feedback.id = 'err-' + d.key;
        feedback.className = 'mt-1 hidden text-xs text-rose-300';

        wrapper.appendChild(label);
        wrapper.appendChild(input);
        wrapper.appendChild(feedback);
        container.appendChild(wrapper);
    });
}

// -- Save --------------------------------------------------------------------
async function saveConfig() {
    const id = document.getElementById('config-id').value.trim();
    const displayName = document.getElementById('config-display-name').value.trim();
    const providerKey = id
        ? document.getElementById('config-provider-key').value
        : document.getElementById('provider-type-select').value;

    if (!displayName) {
        showAlert('Display Name is required.', 'warning');
        return;
    }
    if (!providerKey) {
        showAlert('Please select a provider type.', 'warning');
        return;
    }

    const settings = {};
    document.querySelectorAll('#dynamic-fields input[data-key]').forEach(function (inp) {
        settings[inp.dataset.key] = inp.value;
    });

    document.querySelectorAll('#dynamic-fields input[data-key]').forEach(function (el) {
        el.classList.remove('border-rose-500', 'ring-1', 'ring-rose-500/40');
    });
    document.querySelectorAll('#dynamic-fields [id^="err-"]').forEach(function (el) {
        el.textContent = '';
        el.classList.add('hidden');
    });

    const btn = document.getElementById('btn-save');
    btn.disabled = true;
    btn.innerHTML = '<span class="mr-1 inline-block h-3 w-3 animate-spin rounded-full border border-current border-t-transparent align-[-0.1em]"></span>Saving...';

    try {
        const url = id ? '/api/notifications/configs/' + id : '/api/notifications/configs';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerKey: providerKey, displayName: displayName, settings: settings })
        });
        const data = await res.json();

        if (data.fieldErrors) {
            Object.entries(data.fieldErrors).forEach(function (entry) {
                const key = entry[0];
                const msg = entry[1];
                const input = document.getElementById('field-' + key);
                const err = document.getElementById('err-' + key);
                if (input) {
                    input.classList.add('border-rose-500', 'ring-1', 'ring-rose-500/40');
                }
                if (err) {
                    err.textContent = msg;
                    err.classList.remove('hidden');
                }
            });
            return;
        }
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }

        if (id) {
            allConfigs = allConfigs.map(function (c) { return c.uuid === id ? data : c; });
        } else {
            allConfigs.push(data);
        }
        renderConfigs();
        providerModal.hide();
        showAlert(id ? 'Provider updated.' : 'Provider added.', 'success');
    } catch (_e) {
        showAlert('Network error saving provider.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save mr-1"></i>Save';
    }
}

// -- Delete ------------------------------------------------------------------
async function deleteConfig(id) {
    if (!confirm('Remove this notification provider? This cannot be undone.')) return;
    try {
        const res = await fetch('/api/notifications/configs/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        allConfigs = allConfigs.filter(function (c) { return c.uuid !== id; });
        const card = document.getElementById('card-' + id);
        if (card) card.remove();
        renderConfigs();
        showAlert('Provider removed.', 'success');
    } catch (_e) {
        showAlert('Network error deleting provider.', 'danger');
    }
}

// -- Helpers -----------------------------------------------------------------
function resetModal() {
    editingId = null;
    document.getElementById('config-id').value = '';
    document.getElementById('config-provider-key').value = '';
    document.getElementById('config-display-name').value = '';
    document.getElementById('provider-type-select').value = '';
    document.getElementById('dynamic-fields').innerHTML = '';
    document.getElementById('step-choose').classList.remove('hidden');
    document.getElementById('step-settings').classList.add('hidden');
    document.getElementById('btn-save').classList.add('hidden');
}

function showAlert(msg, type) {
    const area = document.getElementById('alert-area');
    if (!area) return;

    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;

    area.innerHTML =
        '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
        '<div>' + escapeHtml(msg) + '</div>' +
        '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>' +
        '</div>';

    const closeBtn = area.querySelector('button[aria-label="Dismiss alert"]');
    if (closeBtn) {
        closeBtn.addEventListener('click', function () {
            area.innerHTML = '';
        });
    }
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
