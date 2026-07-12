/* settings-ai-models.js — Vork AI Models settings page */

function saveGlobalDefault() {
    const sel = document.getElementById('global-default-select');
    const val = sel ? sel.value : '';
    if (!val) {
        showAlert('Please select a provider and model.', 'warning');
        return;
    }

    const sep = val.indexOf(':');
    const provider = val.substring(0, sep);
    const modelId = val.substring(sep + 1);

    fetch('/api/system/settings')
        .then(function (r) { return r.json(); })
        .then(function (current) {
            return fetch('/api/system/settings', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    defaultProvider: provider,
                    defaultModelId: modelId,
                    relayHost: current.relayHost || '',
                    defaultOobTimeoutMinutes: current.defaultOobTimeoutMinutes || 15
                })
            });
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.ok) {
                showAlert('Global default saved: ' + val.replace(':', ' -- '), 'success');
            } else {
                showAlert('Error: ' + (data.error || 'Unknown error'), 'danger');
            }
        })
        .catch(function () {
            showAlert('Network error saving global default.', 'danger');
        });
}

function toggleForm(providerKey) {
    const form = document.getElementById('form-' + providerKey);
    if (!form) return;
    const visible = !form.classList.contains('hidden');
    form.classList.toggle('hidden', visible);

    if (!visible) {
        loadConfig(providerKey);
        loadDiscoveredModels(providerKey);
    }
}

function loadConfig(providerKey) {
    fetch('/api/ai/providers/' + providerKey + '/config')
        .then(function (r) { return r.json(); })
        .then(function (data) {
            const baseUrlEl = document.getElementById('baseUrl-' + providerKey);
            const modelEl = document.getElementById('model-' + providerKey);
            const enabledEl = document.getElementById('enabled-' + providerKey);
            if (baseUrlEl && data.baseUrl) baseUrlEl.value = data.baseUrl;
            if (modelEl && data.defaultModel) modelEl.value = data.defaultModel;
            if (enabledEl) enabledEl.checked = data.enabled !== false;
        })
        .catch(function () {});
}

function loadDiscoveredModels(providerKey) {
    const modelEl = document.getElementById('model-' + providerKey);
    if (!modelEl) return Promise.resolve();

    return fetch('/api/ai/models/' + providerKey.toLowerCase())
        .then(function (r) { return r.ok ? r.json() : Promise.reject(); })
        .then(function (models) {
            if (!models || models.length === 0) return;
            const current = modelEl.value;
            modelEl.innerHTML = '<option value="">— use provider default —</option>';
            models.forEach(function (m) {
                const opt = document.createElement('option');
                opt.value = m.id;
                opt.textContent = m.displayName || m.id;
                if (m.id === current) opt.selected = true;
                modelEl.appendChild(opt);
            });

            modelEl.classList.remove('hidden');

            const container = modelEl.closest('.mb-3');
            const hint = container ? container.querySelector('.configure-hint') : null;
            if (hint) hint.classList.add('hidden');
        })
        .catch(function () {});
}

function saveConfig(event, providerKey) {
    event.preventDefault();
    const apiKeyEl = document.getElementById('apiKey-' + providerKey);
    const baseUrlEl = document.getElementById('baseUrl-' + providerKey);
    const modelEl = document.getElementById('model-' + providerKey);
    const enabledEl = document.getElementById('enabled-' + providerKey);

    const body = {
        apiKey: apiKeyEl ? apiKeyEl.value.trim() : null,
        baseUrl: baseUrlEl ? baseUrlEl.value.trim() : null,
        defaultModel: modelEl ? modelEl.value : null,
        enabled: enabledEl ? enabledEl.checked : true
    };

    fetch('/api/ai/providers/' + providerKey + '/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.status === 'OK') {
                showAlert('Provider configuration saved.', 'success');
                loadDiscoveredModels(providerKey).then(function () { location.reload(); });
            } else {
                showAlert('Error: ' + (data.message || 'Unknown error'), 'danger');
            }
        })
        .catch(function () {
            showAlert('Network error saving configuration.', 'danger');
        });
}

function deleteConfig(providerKey) {
    if (!confirm('Remove configuration for ' + providerKey + '? This will disconnect the provider.')) return;
    fetch('/api/ai/providers/' + providerKey + '/config', { method: 'DELETE' })
        .then(function (r) { return r.json(); })
        .then(function (data) {
            if (data.status === 'OK') {
                showAlert('Provider configuration removed.', 'warning');
                location.reload();
            } else {
                showAlert('Error: ' + (data.message || 'Unknown error'), 'danger');
            }
        })
        .catch(function () {
            showAlert('Network error removing configuration.', 'danger');
        });
}

function showAlert(message, type) {
    const area = document.getElementById('alert-area');
    const map = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = map[type] || map.info;
    area.innerHTML = '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
        '<div>' + message + '</div>' +
        '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>' +
        '</div>';

    const closeBtn = area.querySelector('button[aria-label="Dismiss alert"]');
    if (closeBtn) {
        closeBtn.addEventListener('click', function () {
            area.innerHTML = '';
        });
    }
}

async function initTranscription() {
    try {
        const results = await Promise.all([
            fetch('/api/transcription/providers').then(function (r) { return r.json(); }),
            fetch('/api/transcription/config').then(function (r) { return r.ok ? r.json() : { configured: false }; })
        ]);
        const tpList = results[0];
        const cfg = results[1];

        const currentKey = cfg.configured ? cfg.providerKey : null;
        tpList.forEach(function (tp) {
            if (!tp.backedByAiProvider) return;
            const card = document.getElementById('card-' + tp.backedByAiProvider);
            if (!card) return;
            const footer = card.querySelector('.card-footer');
            if (!footer) return;

            const wrap = document.createElement('div');
            wrap.className = 'px-3 py-2';
            wrap.innerHTML = '<div class="flex items-center gap-2">' +
                '<input class="h-4 w-4 rounded border-zinc-600 bg-zinc-900 text-[#fdaa02] focus:ring-[#fdaa02]/30" type="checkbox" role="switch"' +
                       ' id="transcription-' + tp.backedByAiProvider + '"' +
                       ' data-provider-key="' + tp.providerKey + '"' +
                       (currentKey === tp.providerKey ? ' checked' : '') +
                       ' onchange="toggleTranscriptionProvider(this)">' +
                '<label class="text-xs text-zinc-300" for="transcription-' + tp.backedByAiProvider + '">' +
                    'Use ' + tp.displayName + ' to transcribe audio messages' +
                '</label>' +
            '</div>';
            footer.before(wrap);
        });
    } catch (_e) {
        // Non-critical — transcription checkboxes are optional UI.
    }
}

async function toggleTranscriptionProvider(checkbox) {
    if (checkbox.checked) {
        document.querySelectorAll('[id^="transcription-"]').forEach(function (cb) {
            if (cb !== checkbox) cb.checked = false;
        });

        const resp = await fetch('/api/transcription/config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerKey: checkbox.dataset.providerKey, settings: {} })
        }).then(function (r) { return r.json(); });

        if (resp.status === 'OK') {
            showAlert('Transcription provider set to ' + checkbox.dataset.providerKey + '.', 'success');
        } else {
            checkbox.checked = false;
            showAlert('Error: ' + (resp.message || 'Could not save transcription config'), 'danger');
        }
    } else {
        await fetch('/api/transcription/config', { method: 'DELETE' });
        showAlert('Transcription provider cleared.', 'warning');
    }
}

document.addEventListener('DOMContentLoaded', initTranscription);
