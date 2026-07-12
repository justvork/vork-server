/* notifications-page.js — My Notifications page */

let addModal;
let allMedia = [];
let allProviders = [];
let activeTgRegistrationId = null;
let tgPollTimer = null;
let activeSlackRegistrationId = null;
let slackPollTimer = null;

document.addEventListener('DOMContentLoaded', function () {
    addModal = new VorkModal(document.getElementById('addModal'));
    document.getElementById('addModal').addEventListener('hidden.bs.modal', onModalHidden);
    loadAll();
});

async function loadAll() {
    try {
        const results = await Promise.all([
            fetch('/api/user/notification-media'),
            fetch('/api/user/notification-media/providers')
        ]);
        const mRes = results[0];
        const pRes = results[1];
        allMedia = mRes.ok ? await mRes.json() : [];
        allProviders = pRes.ok ? await pRes.json() : [];
        renderAll();
    } catch (_e) {
        showAlert('Failed to load notification data.', 'warning');
    }
}

function renderAll() {
    const noProviders = document.getElementById('no-providers-state');
    const tableWrap = document.getElementById('media-table-wrap');
    const emptyState = document.getElementById('empty-state');
    const mediaCard = document.getElementById('media-card');
    const headerAddBtn = document.getElementById('header-add-button');

    if (allProviders.length === 0) {
        noProviders.classList.remove('hidden');
        mediaCard.classList.add('hidden');
        tableWrap.classList.add('hidden');
        emptyState.classList.add('hidden');
        headerAddBtn.classList.add('hidden');
        return;
    }

    noProviders.classList.add('hidden');
    mediaCard.classList.remove('hidden');
    headerAddBtn.classList.remove('hidden');

    if (allMedia.length === 0) {
        tableWrap.classList.add('hidden');
        emptyState.classList.remove('hidden');
    } else {
        emptyState.classList.add('hidden');
        tableWrap.classList.remove('hidden');
        renderTable();
    }
}

function renderTable() {
    const tbody = document.getElementById('media-tbody');
    tbody.innerHTML = '';
    allMedia.forEach(function (m) {
        const provider = allProviders.find(function (p) { return p.providerKey === m.providerKey; });
        const providerLabel = provider ? provider.configDisplayName : m.providerKey;
        const mediaTypeBadge = mediaTypeBadgeHtml(m.mediaType);
        const defaultBtn = m.isDefault
            ? '<button class="btn btn-sm btn-link p-0 text-warning" title="Default address" disabled><i class="fa-solid fa-star"></i></button>'
            : '<button class="btn btn-sm btn-link p-0 text-muted" title="Set as default" onclick="setDefault(\'' + m.uuid + '\')"><i class="fa-regular fa-star"></i></button>';
        const oobBtn = m.oobEnabled
            ? '<button class="btn btn-sm btn-link p-0 text-success" title="OOB notifications on — click to disable" onclick="toggleOob(\'' + m.uuid + '\')"><i class="fa-solid fa-bell"></i></button>'
            : '<button class="btn btn-sm btn-link p-0 text-muted"   title="OOB notifications off — click to enable"  onclick="toggleOob(\'' + m.uuid + '\')"><i class="fa-regular fa-bell-slash"></i></button>';
        const row = document.createElement('tr');
        row.id = 'row-' + m.uuid;
        row.innerHTML =
            '<td>' + mediaTypeBadge + '</td>' +
            '<td class="fw-semibold font-monospace small">' + escapeHtml(m.address) + '</td>' +
            '<td class="text-muted small">' + escapeHtml(m.label || '') + '</td>' +
            '<td><span class="badge bg-secondary">' + escapeHtml(providerLabel) + '</span></td>' +
            '<td class="text-center">' + oobBtn + '</td>' +
            '<td class="text-center">' +
            '  <div class="d-flex justify-content-center gap-2">' +
            '    ' + defaultBtn +
            '    <button class="btn btn-sm btn-link p-0 text-danger" title="Delete" onclick="deleteMedia(\'' + m.uuid + '\')">' +
            '      <i class="fa-solid fa-trash-can"></i>' +
            '    </button>' +
            '  </div>' +
            '</td>';
        tbody.appendChild(row);
    });
}

function mediaTypeBadgeHtml(mediaType) {
    const badges = {
        EMAIL_ADDRESS: '<span class="badge bg-info text-dark"><i class="fa-solid fa-envelope me-1"></i>Email</span>',
        PHONE_NUMBER: '<span class="badge bg-success"><i class="fa-solid fa-phone me-1"></i>Phone</span>',
        TELEGRAM: '<span class="badge bg-primary"><i class="fa-brands fa-telegram me-1"></i>Telegram</span>',
        SLACK: '<span class="badge bg-warning text-dark"><i class="fa-brands fa-slack me-1"></i>Slack</span>'
    };
    return badges[mediaType] || '<span class="badge bg-secondary">' + escapeHtml(mediaType) + '</span>';
}

function openAddModal() {
    resetAddModal();
    const sel = document.getElementById('add-provider-select');
    sel.innerHTML = '<option value="">— select a provider —</option>';
    allProviders.forEach(function (p) {
        const opt = document.createElement('option');
        opt.value = p.configId;
        opt.dataset.key = p.providerKey;
        opt.textContent = p.configDisplayName + ' (' + p.providerDisplayName + ')';
        sel.appendChild(opt);
    });
    addModal.show();
}

function onProviderChange() {
    const sel = document.getElementById('add-provider-select');
    const configId = sel.value;
    const stepAddr = document.getElementById('add-step-address');
    const stepTg = document.getElementById('add-step-telegram');
    const stepSlack = document.getElementById('add-step-slack');
    const saveBtn = document.getElementById('btn-add-save');

    if (!configId) {
        stepAddr.classList.add('hidden');
        stepTg.classList.add('hidden');
        stepSlack.classList.add('hidden');
        saveBtn.classList.add('hidden');
        return;
    }

    const pv = allProviders.find(function (p) { return p.configId === configId; });
    if (!pv) return;

    if (pv.mediaType === 'TELEGRAM') {
        stepAddr.classList.add('hidden');
        stepSlack.classList.add('hidden');
        saveBtn.classList.add('hidden');
        stepTg.classList.remove('hidden');
        startTelegramRegistration(pv.configId);
    } else if (pv.mediaType === 'SLACK') {
        stepAddr.classList.add('hidden');
        stepTg.classList.add('hidden');
        saveBtn.classList.add('hidden');
        stepSlack.classList.remove('hidden');
        startSlackRegistration(pv.configId);
    } else {
        stepTg.classList.add('hidden');
        stepSlack.classList.add('hidden');
        document.getElementById('add-address-label').textContent = mediaTypeLabel(pv.mediaType) + ' *';
        document.getElementById('add-address').placeholder = pv.addressPlaceholder;
        document.getElementById('add-address-hint').textContent = pv.addressHint;
        document.getElementById('add-address').value = '';
        stepAddr.classList.remove('hidden');
        saveBtn.classList.remove('hidden');
    }
}

function mediaTypeLabel(t) {
    if (t === 'EMAIL_ADDRESS') return 'Email address';
    if (t === 'PHONE_NUMBER') return 'Phone number';
    if (t === 'TELEGRAM') return 'Telegram handle / chat ID';
    if (t === 'SLACK') return 'Slack member ID';
    return 'Address';
}

async function startTelegramRegistration(configId) {
    stopTgPoll();
    activeTgRegistrationId = null;

    const loadingEl = document.getElementById('tg-loading');
    const canvas = document.getElementById('tg-qr-canvas');
    const linkWrap = document.getElementById('tg-link-wrap');
    const statusEl = document.getElementById('tg-status-msg');

    loadingEl.classList.remove('hidden');
    canvas.classList.add('hidden');
    linkWrap.classList.add('hidden');
    statusEl.textContent = '';

    const isDefault = document.getElementById('tg-default').checked;
    try {
        const res = await fetch('/api/user/notification-media/telegram/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerConfigId: configId, isDefault: isDefault })
        });
        const data = await res.json();
        if (!res.ok) {
            statusEl.textContent = data.error || 'Failed to start registration.';
            return;
        }

        activeTgRegistrationId = data.registrationId;

        loadingEl.classList.add('hidden');
        try {
            new QRious({ element: canvas, value: data.url, size: 200 });
            canvas.classList.remove('hidden');
        } catch (e) {
            console.warn('QR generation failed', e);
        }
        document.getElementById('tg-deep-link').href = data.url;
        linkWrap.classList.remove('hidden');
        statusEl.innerHTML = '<span class="text-muted"><span class="spinner-grow spinner-grow-sm me-1"></span>Waiting for scan&hellip;</span>';

        tgPollTimer = setInterval(pollTgStatus, 2000);
    } catch (_e) {
        loadingEl.classList.add('hidden');
        statusEl.textContent = 'Network error. Please try again.';
    }
}

async function pollTgStatus() {
    if (!activeTgRegistrationId) return;
    try {
        const res = await fetch('/api/user/notification-media/telegram/register/' + activeTgRegistrationId);
        const data = await res.json();
        if (data.status === 'complete') {
            stopTgPoll();
            addModal.hide();
            await loadAll();
            showAlert('Telegram account linked successfully!', 'success');
        } else if (data.status === 'expired') {
            stopTgPoll();
            document.getElementById('tg-status-msg').innerHTML = '<span class="text-danger">Registration expired. Please try again.</span>';
            activeTgRegistrationId = null;
        }
    } catch (_e) {}
}

function stopTgPoll() {
    if (tgPollTimer) {
        clearInterval(tgPollTimer);
        tgPollTimer = null;
    }
}

async function startSlackRegistration(configId) {
    stopSlackPoll();
    activeSlackRegistrationId = null;

    const loadingEl = document.getElementById('slack-loading');
    const instrWrap = document.getElementById('slack-instructions-wrap');
    const codeBox = document.getElementById('slack-code-box');
    const statusEl = document.getElementById('slack-status-msg');

    loadingEl.classList.remove('hidden');
    instrWrap.classList.add('hidden');
    statusEl.textContent = '';

    const isDefault = document.getElementById('slack-default').checked;
    try {
        const res = await fetch('/api/user/notification-media/slack/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerConfigId: configId, isDefault: isDefault })
        });
        const data = await res.json();
        if (!res.ok) {
            loadingEl.classList.add('hidden');
            statusEl.textContent = data.error || 'Failed to start registration.';
            instrWrap.classList.remove('hidden');
            return;
        }

        activeSlackRegistrationId = data.registrationId;

        const match = (data.instructions || '').match(/register ([A-Z0-9]{16})/);
        codeBox.textContent = match ? 'register ' + match[1] : data.instructions;

        loadingEl.classList.add('hidden');
        instrWrap.classList.remove('hidden');
        statusEl.innerHTML = '<span class="text-muted"><span class="spinner-grow spinner-grow-sm me-1"></span>Waiting for confirmation&hellip;</span>';

        slackPollTimer = setInterval(pollSlackStatus, 2000);
    } catch (_e) {
        loadingEl.classList.add('hidden');
        instrWrap.classList.remove('hidden');
        statusEl.textContent = 'Network error. Please try again.';
    }
}

async function pollSlackStatus() {
    if (!activeSlackRegistrationId) return;
    try {
        const res = await fetch('/api/user/notification-media/slack/register/' + activeSlackRegistrationId);
        const data = await res.json();
        if (data.status === 'complete') {
            stopSlackPoll();
            addModal.hide();
            await loadAll();
            showAlert('Slack account linked successfully!', 'success');
        } else if (data.status === 'expired') {
            stopSlackPoll();
            document.getElementById('slack-status-msg').innerHTML = '<span class="text-danger">Registration expired. Please try again.</span>';
            activeSlackRegistrationId = null;
        }
    } catch (_e) {}
}

function stopSlackPoll() {
    if (slackPollTimer) {
        clearInterval(slackPollTimer);
        slackPollTimer = null;
    }
}

function copySlackCode(btn) {
    const text = document.getElementById('slack-code-box').textContent.trim();
    navigator.clipboard.writeText(text).then(function () {
        const icon = btn.querySelector('i');
        icon.className = 'fa-solid fa-check text-success';
        setTimeout(function () { icon.className = 'fa-regular fa-copy'; }, 1500);
    });
}

function onModalHidden() {
    stopTgPoll();
    stopSlackPoll();
    if (activeTgRegistrationId) {
        fetch('/api/user/notification-media/telegram/register/' + activeTgRegistrationId, { method: 'DELETE' }).catch(function () {});
        activeTgRegistrationId = null;
    }
    if (activeSlackRegistrationId) {
        fetch('/api/user/notification-media/slack/register/' + activeSlackRegistrationId, { method: 'DELETE' }).catch(function () {});
        activeSlackRegistrationId = null;
    }
    resetAddModal();
}

function resetAddModal() {
    document.getElementById('add-provider-select').value = '';
    document.getElementById('add-address').value = '';
    document.getElementById('add-address').classList.remove('is-invalid');
    document.getElementById('add-address-error').textContent = '';
    document.getElementById('add-label').value = '';
    document.getElementById('add-default').checked = true;
    document.getElementById('add-step-address').classList.add('hidden');
    document.getElementById('add-step-telegram').classList.add('hidden');
    document.getElementById('add-step-slack').classList.add('hidden');
    document.getElementById('btn-add-save').classList.add('hidden');

    document.getElementById('tg-loading').classList.remove('hidden');
    document.getElementById('tg-qr-canvas').classList.add('hidden');
    document.getElementById('tg-link-wrap').classList.add('hidden');
    document.getElementById('tg-status-msg').textContent = '';

    document.getElementById('slack-loading').classList.remove('hidden');
    document.getElementById('slack-instructions-wrap').classList.add('hidden');
    document.getElementById('slack-code-box').textContent = '';
    document.getElementById('slack-status-msg').textContent = '';
}

async function saveMedia() {
    const sel = document.getElementById('add-provider-select');
    const providerKey = sel.options[sel.selectedIndex] ? sel.options[sel.selectedIndex].dataset.key || '' : '';
    const address = document.getElementById('add-address').value.trim();
    const label = document.getElementById('add-label').value.trim();
    const isDefault = document.getElementById('add-default').checked;

    clearFieldError('add-address');
    if (!address) {
        setFieldError('add-address', 'add-address-error', 'Address is required.');
        return;
    }

    const btn = document.getElementById('btn-add-save');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving&hellip;';

    try {
        const res = await fetch('/api/user/notification-media', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providerKey: providerKey, address: address, label: label, isDefault: isDefault })
        });
        const data = await res.json();
        if (!res.ok) {
            if (data.addressError) {
                setFieldError('add-address', 'add-address-error', data.addressError);
            } else {
                showAlert(data.error || 'Failed to save.', 'danger');
            }
            return;
        }

        addModal.hide();
        await loadAll();
        showAlert('Address saved.', 'success');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save me-1"></i>Save';
    }
}

async function setDefault(id) {
    try {
        const res = await fetch('/api/user/notification-media/' + id + '/default', { method: 'PUT' });
        if (res.ok) {
            await loadAll();
            showAlert('Default address updated.', 'success');
        } else {
            showAlert('Failed to update default.', 'danger');
        }
    } catch (_e) {
        showAlert('Network error.', 'danger');
    }
}

async function toggleOob(id) {
    try {
        const res = await fetch('/api/user/notification-media/' + id + '/oob', { method: 'PUT' });
        if (res.ok) {
            const data = await res.json();
            const m = allMedia.find(function (x) { return x.uuid === id; });
            if (m) m.oobEnabled = data.oobEnabled;
            renderTable();
            showAlert(data.oobEnabled ? 'OOB notifications enabled for this address.' : 'OOB notifications disabled for this address.', 'success');
        } else {
            showAlert('Failed to toggle OOB setting.', 'danger');
        }
    } catch (_e) {
        showAlert('Network error.', 'danger');
    }
}

async function deleteMedia(id) {
    if (!confirm('Remove this notification address?')) return;
    try {
        const res = await fetch('/api/user/notification-media/' + id, { method: 'DELETE' });
        if (res.ok) {
            await loadAll();
        } else {
            showAlert('Failed to delete.', 'danger');
        }
    } catch (_e) {
        showAlert('Network error.', 'danger');
    }
}

function showAlert(msg, type) {
    const area = document.getElementById('alert-area');
    area.innerHTML = '<div class="alert alert-' + type + ' alert-dismissible fade show" role="alert">' +
        escapeHtml(msg) + '<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>';
}

function setFieldError(inputId, errorId, msg) {
    document.getElementById(inputId).classList.add('is-invalid');
    document.getElementById(errorId).textContent = msg;
}

function clearFieldError(inputId) {
    document.getElementById(inputId).classList.remove('is-invalid');
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
