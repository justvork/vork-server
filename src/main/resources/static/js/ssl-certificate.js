// ssl-certificate.js

const alertArea = document.getElementById('alert-area');

function showAlert(msg, type) {
    const map = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = map[type] || map.info;
    alertArea.innerHTML = `<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ${tone}" role="alert">
        <div>${msg}</div>
        <button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>
    </div>`;
    const closeBtn = alertArea.querySelector('button[aria-label="Dismiss alert"]');
    if (closeBtn) {
        closeBtn.addEventListener('click', () => {
            alertArea.innerHTML = '';
        });
    }
    alertArea.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

// ── Load certificate info ─────────────────────────────────────────────────────

function loadCertInfo() {
    fetch('/api/ssl/certificate')
        .then(r => r.json())
        .then(data => {
            const certInfo = document.getElementById('cert-info-section');
            const notConfigured = document.getElementById('cert-not-configured');
            if (data.status === 'not-configured') {
                certInfo.classList.add('hidden');
                notConfigured.classList.remove('hidden');
                return;
            }
            certInfo.classList.remove('hidden');
            notConfigured.classList.add('hidden');
            renderCertInfo(data);
            prefillSelfSignedForm(data);
        })
        .catch(() => showAlert('Could not load certificate information.', 'danger'));
}

function renderCertInfo(d) {
    const typeLabel = d.type === 'lets-encrypt'
        ? '<span class="inline-flex rounded-md bg-emerald-600/20 px-2 py-0.5 text-xs font-semibold text-emerald-300 ring-1 ring-emerald-500/30 cert-badge">Let\'s Encrypt</span>'
        : '<span class="inline-flex rounded-md bg-zinc-700/30 px-2 py-0.5 text-xs font-semibold text-zinc-300 ring-1 ring-zinc-600 cert-badge">Self-Signed</span>';

    const now         = Date.now();
    const expiresIn   = Math.floor((d.validUntil - now) / 86400000);
    const expiryClass = expiresIn <= 30 ? 'text-rose-400 font-bold' : expiresIn <= 60 ? 'text-amber-300' : 'text-emerald-300';

    const sanList = (d.subjectAltNames && d.subjectAltNames.length)
        ? d.subjectAltNames.join(', ')
        : '—';

    document.getElementById('cert-type').innerHTML = typeLabel;
    setField('cert-cn',        d.commonName         || '—');
    setField('cert-o',         d.organization        || '—');
    setField('cert-ou',        d.organizationalUnit  || '—');
    setField('cert-l',         d.locality            || '—');
    setField('cert-st',        d.state               || '—');
    setField('cert-c',         d.country             || '—');
    setField('cert-issuer',    d.issuerName          || '—');
    setField('cert-serial',    d.serialNumber        || '—');
    setField('cert-algorithm', d.signatureAlgorithm  || '—');
    setField('cert-keysize',   d.keySize ? d.keySize + ' bits' : '—');
    setField('cert-sans',      sanList);
    setField('cert-valid-from', d.validFrom ? new Date(d.validFrom).toLocaleString() : '—');
    setField('cert-valid-until', d.validUntil ? new Date(d.validUntil).toLocaleString() : '—');

    const expiryEl = document.getElementById('cert-expiry-info');
    expiryEl.className = 'text-xs ' + expiryClass;
    expiryEl.textContent = expiresIn >= 0
        ? `Expires in ${expiresIn} day${expiresIn !== 1 ? 's' : ''}`
        : `Expired ${Math.abs(expiresIn)} days ago`;
}

function spinnerLabel(label) {
    return `<span class="mr-1 inline-block h-3 w-3 animate-spin rounded-full border border-current border-t-transparent align-[-0.1em]"></span>${label}`;
}

function setField(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}

function prefillSelfSignedForm(d) {
    setValue('ss-cn', d.commonName);
    setValue('ss-o',  d.organization);
    setValue('ss-ou', d.organizationalUnit);
    setValue('ss-l',  d.locality);
    setValue('ss-st', d.state);
    setValue('ss-c',  d.country);
}

function setValue(id, value) {
    const el = document.getElementById(id);
    if (el && value) el.value = value;
}

// ── Self-signed regeneration ──────────────────────────────────────────────────

function regenerateSelfSigned() {
    const cn = document.getElementById('ss-cn').value.trim();
    if (!cn) { showAlert('Common Name (CN) is required.', 'warning'); return; }

    const btn = document.getElementById('btn-regen-ss');
    btn.disabled = true;
    btn.innerHTML = spinnerLabel('Generating...');

    fetch('/api/ssl/certificate/self-signed', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            cn:  cn,
            o:   document.getElementById('ss-o').value.trim()  || null,
            ou:  document.getElementById('ss-ou').value.trim() || null,
            l:   document.getElementById('ss-l').value.trim()  || null,
            st:  document.getElementById('ss-st').value.trim() || null,
            c:   document.getElementById('ss-c').value.trim()  || null
        })
    })
    .then(r => r.json())
    .then(data => {
        if (data.status === 'ok') {
            showAlert('<strong>Certificate regenerated.</strong> ' + data.message, 'success');
            setTimeout(loadCertInfo, 2000);
        } else {
            showAlert('Error: ' + (data.message || 'Unknown error'), 'danger');
        }
    })
    .catch(() => showAlert('Request failed — check console.', 'danger'))
    .finally(() => {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-rotate mr-1"></i>Regenerate Certificate';
    });
}

// ── CSR download ──────────────────────────────────────────────────────────────

function downloadCsr() {
    const cn = document.getElementById('ss-cn').value.trim();
    const o  = document.getElementById('ss-o').value.trim();
    const ou = document.getElementById('ss-ou').value.trim();
    const l  = document.getElementById('ss-l').value.trim();
    const st = document.getElementById('ss-st').value.trim();
    const c  = document.getElementById('ss-c').value.trim();

    const params = new URLSearchParams();
    if (cn) params.set('cn', cn);
    if (o)  params.set('o',  o);
    if (ou) params.set('ou', ou);
    if (l)  params.set('l',  l);
    if (st) params.set('st', st);
    if (c)  params.set('c',  c);

    const url = '/api/ssl/certificate/csr?' + params.toString();
    // Trigger a file download
    const a = document.createElement('a');
    a.href = url;
    a.download = 'server.csr';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

// ── Let's Encrypt ─────────────────────────────────────────────────────────────

let leStatusPollInterval = null;

function requestLetsEncrypt() {
    const hostname = document.getElementById('le-hostname').value.trim();
    const email    = document.getElementById('le-email').value.trim();
    if (!hostname) { showAlert('Hostname is required.', 'warning'); return; }
    if (!email)    { showAlert('Email address is required.', 'warning'); return; }

    const btn = document.getElementById('btn-le-request');
    btn.disabled = true;
    btn.innerHTML = spinnerLabel('Starting...');

    fetch('/api/ssl/certificate/lets-encrypt', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hostname, email })
    })
    .then(r => r.json())
    .then(data => {
        if (data.status === 'started' || data.status === 'in-progress') {
            showAlert(data.message, 'info');
            startLeStatusPolling();
        } else {
            showAlert('Error: ' + (data.message || 'Unknown error'), 'danger');
            btn.disabled = false;
            btn.innerHTML = '<i class="fa-solid fa-certificate mr-1"></i>Request Certificate';
        }
    })
    .catch(() => {
        showAlert('Request failed — check console.', 'danger');
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-certificate mr-1"></i>Request Certificate';
    });
}

function startLeStatusPolling() {
    if (leStatusPollInterval) return;
    leStatusPollInterval = setInterval(pollLeStatus, 3000);
    pollLeStatus();
}

function stopLeStatusPolling() {
    if (leStatusPollInterval) {
        clearInterval(leStatusPollInterval);
        leStatusPollInterval = null;
    }
}

function pollLeStatus() {
    fetch('/api/ssl/certificate/lets-encrypt/status')
        .then(r => r.json())
        .then(data => {
            renderLeStatus(data.status, data.message);
            const terminal = ['success', 'failed', 'idle'];
            if (terminal.includes(data.status)) {
                stopLeStatusPolling();
                const btn = document.getElementById('btn-le-request');
                btn.disabled = false;
                btn.innerHTML = '<i class="fa-solid fa-certificate mr-1"></i>Request Certificate';
                if (data.status === 'success') {
                    showAlert('<strong>Success!</strong> Let\'s Encrypt certificate installed. The SSL context will reload automatically.', 'success');
                    setTimeout(loadCertInfo, 3000);
                } else if (data.status === 'failed') {
                    showAlert('<strong>Failed:</strong> ' + data.message, 'danger');
                }
            }
        })
        .catch(() => {});
}

function renderLeStatus(status, message) {
    const el = document.getElementById('le-status-display');
    if (!el) return;

    const active = ['ordering', 'pending_challenge', 'validating', 'issuing'];
    const indicatorClass = status === 'success' ? 'le-status-success'
                         : status === 'failed'  ? 'le-status-failed'
                         : active.includes(status) ? 'le-status-active'
                         : 'le-status-idle';
    el.innerHTML = `<span class="le-status-indicator ${indicatorClass}"></span>
        <span>${message || status}</span>`;
}

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    loadCertInfo();
    // Start polling if a Let's Encrypt flow is already in progress
    pollLeStatus();
});
