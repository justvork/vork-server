/* setup-notifications.js */

let providers = [];
let stepIndex = 0;
let stepMedia = {};
let savedThisRun = [];

document.addEventListener('DOMContentLoaded', async function () {
    try {
        const res = await fetch('/api/user/notification-media/providers');
        providers = res.ok ? await res.json() : [];
    } catch (_e) {
        providers = [];
    }
    buildWizard();
});

function buildWizard() {
    if (providers.length === 0) {
        document.getElementById('panel-no-providers').classList.add('active');
        return;
    }

    providers.forEach(function (p) {
        stepMedia[p.providerKey] = [];
    });

    buildStepIndicator();
    buildProviderPanels();
    showStep(0);
}

function buildStepIndicator() {
    const container = document.getElementById('step-indicator');
    const totalSteps = providers.length + 1;

    for (let i = 0; i < totalSteps; i++) {
        const dot = document.createElement('div');
        dot.className = 'step-dot';
        dot.id = 'dot-' + i;
        dot.textContent = i < providers.length ? (i + 1) : '';
        if (i === providers.length) {
            dot.innerHTML = '<i class="fa-solid fa-check fa-xs"></i>';
        }
        container.appendChild(dot);

        if (i < totalSteps - 1) {
            const line = document.createElement('div');
            line.className = 'step-line mx-1';
            container.appendChild(line);
        }
    }
}

function buildProviderPanels() {
    const container = document.getElementById('provider-panels');
    providers.forEach(function (p, i) {
        const panel = document.createElement('div');
        panel.className = 'step-panel';
        panel.id = 'panel-' + i;

        const mediaTypeIcon = {
            EMAIL_ADDRESS: 'fa-envelope',
            PHONE_NUMBER: 'fa-phone',
            TELEGRAM: 'fa-telegram fa-brands'
        };
        const icon = mediaTypeIcon[p.mediaType] || 'fa-bell';
        const typeLabel = mediaTypeLabel(p.mediaType);

        panel.innerHTML =
            '<div class="card">' +
            '  <div class="card-body p-4">' +
            '    <h5 class="card-title mb-1">' +
            '      <i class="fa-solid ' + icon + ' me-2 text-primary"></i>' +
            '      ' + escapeHtml(p.configDisplayName) +
            '    </h5>' +
            '    <p class="text-muted small mb-4">' +
            '      Add your ' + escapeHtml(typeLabel.toLowerCase()) + ' for <strong>' +
            escapeHtml(p.providerDisplayName) + '</strong>. You can add multiple.' +
            '    </p>' +
            '    <div class="mb-3">' +
            '      <label class="form-label" for="addr-' + i + '">' + escapeHtml(typeLabel) + '</label>' +
            '      <div class="input-group">' +
            '        <input type="text" id="addr-' + i + '" class="form-control"' +
            '               placeholder="' + escapeHtml(p.addressPlaceholder) + '"' +
            '               onkeydown="if(event.key===\'Enter\'){event.preventDefault();addChip(' + i + ')}">' +
            '        <button class="btn btn-outline-secondary" type="button" onclick="addChip(' + i + ')">' +
            '          <i class="fa-solid fa-plus"></i>' +
            '        </button>' +
            '      </div>' +
            '      <div id="addr-error-' + i + '" class="text-danger small mt-1 hidden"></div>' +
            '      <div class="form-text text-muted">' + escapeHtml(p.addressHint) + '</div>' +
            '    </div>' +
            '    <div class="mb-3">' +
            '      <label class="form-label" for="lbl-' + i + '">Label <span class="text-muted small">(optional)</span></label>' +
            '      <input type="text" id="lbl-' + i + '" class="form-control" placeholder="e.g. Work email">' +
            '    </div>' +
            '    <div id="chips-' + i + '" class="mb-3"></div>' +
            '    <div class="d-flex gap-2 mt-2">' +
            '      <button class="btn btn-secondary" onclick="skipStep(' + i + ')">Skip</button>' +
            '      <button id="btn-next-' + i + '" class="btn btn-primary ms-auto" onclick="nextStep(' + i + ')">' +
            '        <i class="fa-solid fa-arrow-right me-1"></i>Next' +
            '      </button>' +
            '    </div>' +
            '  </div>' +
            '</div>';

        container.appendChild(panel);
    });
}

function showStep(index) {
    stepIndex = index;

    document.querySelectorAll('.step-panel').forEach(function (p) {
        p.classList.remove('active');
    });

    if (index < providers.length) {
        document.getElementById('panel-' + index).classList.add('active');
    } else {
        document.getElementById('panel-summary').classList.add('active');
        renderSummary();
    }

    document.querySelectorAll('.step-dot').forEach(function (dot, i) {
        dot.classList.remove('active', 'done');
        if (i < index) {
            dot.classList.add('done');
        } else if (i === index) {
            dot.classList.add('active');
        }
    });
}

function skipStep(i) {
    stepMedia[providers[i].providerKey] = [];
    showStep(i + 1);
}

async function nextStep(i) {
    const p = providers[i];
    const addrEl = document.getElementById('addr-' + i);
    const pending = addrEl.value.trim();

    if (pending) {
        const added = await addChip(i, true);
        if (!added) return;
    }

    const btn = document.getElementById('btn-next-' + i);
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>';

    const items = stepMedia[p.providerKey] || [];
    const isFirst = (await fetchUserMediaCount()) === 0 && savedThisRun.length === 0;

    for (let j = 0; j < items.length; j++) {
        const makeDefault = isFirst && j === 0;
        try {
            const res = await fetch('/api/user/notification-media', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    providerKey: p.providerKey,
                    address: items[j].address,
                    label: items[j].label,
                    isDefault: makeDefault
                })
            });
            const data = await res.json();
            if (res.ok) {
                savedThisRun.push(data);
            } else {
                console.warn('Failed to save', items[j], data);
            }
        } catch (e) {
            console.warn('Error saving', e);
        }
    }

    btn.disabled = false;
    btn.innerHTML = '<i class="fa-solid fa-arrow-right me-1"></i>Next';
    showStep(i + 1);
}

async function fetchUserMediaCount() {
    try {
        const r = await fetch('/api/user/notification-media');
        const d = r.ok ? await r.json() : [];
        return d.length;
    } catch (_e) {
        return 0;
    }
}

async function addChip(i, required) {
    const p = providers[i];
    const addrEl = document.getElementById('addr-' + i);
    const lblEl = document.getElementById('lbl-' + i);
    const errEl = document.getElementById('addr-error-' + i);
    const address = addrEl.value.trim();
    errEl.classList.add('hidden');

    if (!address) {
        if (required) {
            errEl.textContent = 'Address is required.';
            errEl.classList.remove('hidden');
        }
        return !required;
    }

    try {
        await fetch('/api/user/notification-media', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerKey: p.providerKey, address: address, label: '', isDefault: false, validateOnly: true })
        });
    } catch (_e) {
        // Validation endpoint/network issues are ignored here to preserve current flow.
    }

    const label = lblEl ? lblEl.value.trim() : '';
    stepMedia[p.providerKey].push({ address: address, label: label });

    addrEl.value = '';
    if (lblEl) lblEl.value = '';

    renderChips(i, p.providerKey);
    return true;
}

function removeChip(providerKey, index, stepI) {
    if (stepMedia[providerKey]) {
        stepMedia[providerKey].splice(index, 1);
    }
    renderChips(stepI, providerKey);
}

function renderChips(stepI, providerKey) {
    const container = document.getElementById('chips-' + stepI);
    if (!container) return;
    const items = stepMedia[providerKey] || [];
    container.innerHTML = '';
    items.forEach(function (item, idx) {
        const chip = document.createElement('span');
        chip.className = 'media-chip';
        chip.innerHTML =
            '<span>' + escapeHtml(item.address) + (item.label ? ' <em class="text-muted">(' + escapeHtml(item.label) + ')</em>' : '') + '</span>' +
            '<span class="remove-chip" onclick="removeChip(\'' + escapeHtml(providerKey) + '\',' + idx + ',' + stepI + ')">' +
            '  <i class="fa-solid fa-xmark"></i>' +
            '</span>';
        container.appendChild(chip);
    });
}

function renderSummary() {
    const container = document.getElementById('summary-list');
    container.innerHTML = '';
    if (savedThisRun.length === 0) {
        container.innerHTML = '<p class="text-muted small text-center">No addresses were added in this setup.</p>';
        return;
    }
    savedThisRun.forEach(function (m) {
        const pv = providers.find(function (p) { return p.providerKey === m.providerKey; });
        const providerLabel = pv ? pv.configDisplayName : m.providerKey;
        const li = document.createElement('div');
        li.className = 'd-flex align-items-center gap-2 py-2 border-bottom';
        li.innerHTML =
            mediaTypeBadgeHtml(m.mediaType) +
            '<span class="fw-semibold font-monospace small">' + escapeHtml(m.address) + '</span>' +
            '<span class="badge bg-secondary ms-auto">' + escapeHtml(providerLabel) + '</span>' +
            (m.isDefault ? '<span class="text-warning"><i class="fa-solid fa-star" title="Default"></i></span>' : '');
        container.appendChild(li);
    });
}

function mediaTypeLabel(t) {
    if (t === 'EMAIL_ADDRESS') return 'Email address';
    if (t === 'PHONE_NUMBER') return 'Phone number';
    if (t === 'TELEGRAM') return 'Telegram handle / chat ID';
    return 'Address';
}

function mediaTypeBadgeHtml(mediaType) {
    const badges = {
        EMAIL_ADDRESS: '<span class="badge bg-info text-dark"><i class="fa-solid fa-envelope me-1"></i>Email</span>',
        PHONE_NUMBER: '<span class="badge bg-success"><i class="fa-solid fa-phone me-1"></i>Phone</span>',
        TELEGRAM: '<span class="badge bg-primary"><i class="fa-brands fa-telegram me-1"></i>Telegram</span>'
    };
    return badges[mediaType] || '<span class="badge bg-secondary">' + escapeHtml(mediaType) + '</span>';
}

function escapeHtml(s) {
    if (!s) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
