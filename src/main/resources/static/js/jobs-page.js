/* jobs-page.js */

let jobModal;
let allJobs = [];

document.addEventListener('DOMContentLoaded', function () {
    jobModal = new VorkModal(document.getElementById('jobModal'));
    loadAgents();
    loadJobsJson();

    const importBtn = document.getElementById('import-jobs-btn');
    const importInput = document.getElementById('import-jobs-input');
    if (importBtn && importInput) {
        importBtn.addEventListener('click', function () {
            importInput.click();
        });
        importInput.addEventListener('change', function () {
            importJobs(importInput);
        });
    }
});

function loadAgents() {
    fetch('/api/chat/agents?type=BACKGROUND')
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (agents) {
            const sel = document.getElementById('job-agent');
            agents.forEach(function (a) {
                const opt = document.createElement('option');
                opt.value = a.uuid;
                opt.textContent = a.name;
                sel.appendChild(opt);
            });
            if (!sel.value) {
                sel.value = 'agent-tpl-automation-reporter-001';
            }
        })
        .catch(function () {});
}

function loadJobsJson() {
    fetch('/api/jobs')
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (jobs) { allJobs = jobs; })
        .catch(function () {});
}

function openCreate() {
    document.getElementById('jobModalLabel').textContent = 'New Job';
    document.getElementById('job-id').value = '';
    document.getElementById('job-name').value = '';
    document.getElementById('job-prompt').value = '';
    document.getElementById('job-start-time').value = '';
    document.getElementById('job-repeat-duration').value = 1;
    document.getElementById('job-duration-type').value = 'HOURS';
    document.getElementById('job-agent').value = 'agent-tpl-automation-reporter-001';
    document.getElementById('job-oob-timeout').value = 240;
    document.querySelectorAll('input[name="invocationType"]').forEach(function (r) { r.checked = false; });
    document.getElementById('modal-alert-area').innerHTML = '';
    onTypeChange();
    jobModal.show();
}

function openEdit(id) {
    const job = allJobs.find(function (j) { return j.id === id; });
    if (!job) {
        showAlert('Job not found.', 'warning');
        return;
    }

    document.getElementById('jobModalLabel').textContent = 'Edit Job';
    document.getElementById('job-id').value = job.id;
    document.getElementById('job-name').value = job.name;
    document.getElementById('job-prompt').value = job.aiPrompt;
    document.getElementById('job-agent').value = job.agentTemplateId || '';

    const typeRadio = document.getElementById('type-' + job.invocationType);
    if (typeRadio) typeRadio.checked = true;
    onTypeChange();

    if (job.startTime) {
        const dt = new Date(job.startTime);
        document.getElementById('job-start-time').value = toDatetimeLocal(dt);
    }
    document.getElementById('job-repeat-duration').value = job.repeatDuration || 1;
    document.getElementById('job-duration-type').value = job.durationType || 'HOURS';
    document.getElementById('job-oob-timeout').value = job.oobTimeoutMinutes > 0 ? job.oobTimeoutMinutes : 240;
    document.getElementById('modal-alert-area').innerHTML = '';
    jobModal.show();
}

function onTypeChange() {
    const type = selectedType();
    const showStart = type === 'ONE_TIME' || type === 'REPEAT';
    const showRepeat = type === 'REPEAT';
    document.getElementById('row-start-time').classList.toggle('jobs-schedule-hidden', !showStart);
    document.getElementById('row-repeat-duration').classList.toggle('jobs-schedule-hidden', !showRepeat);
    document.getElementById('row-duration-type').classList.toggle('jobs-schedule-hidden', !showRepeat);
}

function selectedType() {
    const radio = document.querySelector('input[name="invocationType"]:checked');
    return radio ? radio.value : null;
}

async function saveJob() {
    const id = document.getElementById('job-id').value;
    const type = selectedType();
    if (!type) {
        showAlert('Please select an invocation type.', 'warning');
        return;
    }

    const body = {
        name: document.getElementById('job-name').value.trim(),
        aiPrompt: document.getElementById('job-prompt').value.trim(),
        invocationType: type,
        startTime: document.getElementById('job-start-time').value || null,
        repeatDuration: parseInt(document.getElementById('job-repeat-duration').value) || 0,
        durationType: document.getElementById('job-duration-type').value,
        agentTemplateId: document.getElementById('job-agent').value || null,
        provider: null,
        modelId: null,
        oobTimeoutMinutes: parseInt(document.getElementById('job-oob-timeout').value) || 240,
        skillUuids: [],
        toolIds: []
    };

    const btn = document.getElementById('btn-save-job');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving&hellip;';

    try {
        const url = id ? '/api/jobs/' + id : '/api/jobs';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        jobModal.hide();
        showAlert((id ? 'Job updated.' : 'Job created.'), 'success');
        setTimeout(function () { location.reload(); }, 800);
    } catch (_e) {
        showAlert('Network error saving job.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save me-1"></i>Save Job';
    }
}

async function deleteJob(id) {
    if (!confirm('Delete this job? This cannot be undone.')) return;
    try {
        const res = await fetch('/api/jobs/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Job deleted.', 'warning');
        setTimeout(function () { location.reload(); }, 600);
    } catch (_e) {
        showAlert('Network error deleting job.', 'danger');
    }
}

async function runNow(id) {
    try {
        const res = await fetch('/api/jobs/' + id + '/run', { method: 'POST' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        window.open('/job-monitor.html?session=' + encodeURIComponent(data.trackingSessionUuid), '_blank');
    } catch (_e) {
        showAlert('Network error triggering job.', 'danger');
    }
}

async function pauseJob(id) {
    try {
        const res = await fetch('/api/jobs/' + id + '/pause', { method: 'POST' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Job paused.', 'warning');
        setTimeout(function () { location.reload(); }, 600);
    } catch (_e) {
        showAlert('Network error pausing job.', 'danger');
    }
}

async function resumeJob(id) {
    try {
        const res = await fetch('/api/jobs/' + id + '/resume', { method: 'POST' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Job resumed.', 'success');
        setTimeout(function () { location.reload(); }, 600);
    } catch (_e) {
        showAlert('Network error resuming job.', 'danger');
    }
}

function exportJobPackage(id) {
    if (!id) {
        showAlert('Job id is missing for export.', 'warning');
        return;
    }
    window.location.href = '/api/jobs/' + encodeURIComponent(id) + '/export';
}

async function importJobs(input) {
    const file = input && input.files && input.files[0];
    if (!file) return;

    try {
        const raw = await file.text();
        const payload = JSON.parse(raw);
        const res = await fetch('/api/jobs/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.status === 'error') {
            showAlert(data.message || 'Job import failed.', 'danger');
            return;
        }
        showAlert(data.status === 'updated' ? 'Job updated from import.' : 'Job imported successfully.', 'success');
        setTimeout(function () { location.reload(); }, 700);
    } catch (_e) {
        showAlert('Invalid JSON file or network error during job import.', 'danger');
    } finally {
        if (input) input.value = '';
    }
}

function showAlert(msg, type) {
    const modalEl = document.getElementById('jobModal');
    const isOpen = modalEl && modalEl.classList.contains('show');
    const area = document.getElementById(isOpen ? 'modal-alert-area' : 'alert-area');
    area.innerHTML = '<div class="alert alert-' + type + ' alert-dismissible fade show py-2 small" role="alert">' +
        msg + '<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>';
}

function toDatetimeLocal(d) {
    const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
        'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}
