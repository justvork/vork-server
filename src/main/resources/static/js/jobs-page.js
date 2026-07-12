/* jobs-page.js */

let jobModal;
let allJobs = [];
let _jobToolsCache = null;
let _jobSkillsCache = null;
let pendingJobSkillUuids = [];
let pendingJobToolIds = [];

document.addEventListener('DOMContentLoaded', function () {
    jobModal = new VorkModal(document.getElementById('jobModal'));
    loadAgents();
    loadModels();
    loadJobsJson();
    setupJobSkillSearch();
    setupJobToolSearch();
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

function loadModels() {
    fetch('/api/chat/models')
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (groups) {
            const sel = document.getElementById('job-model');
            groups.forEach(function (g) {
                if (!g.configured) return;
                g.models.forEach(function (m) {
                    const opt = document.createElement('option');
                    opt.value = g.providerKey + ':' + m.modelId;
                    opt.textContent = g.providerLabel + ' - ' + m.label;
                    sel.appendChild(opt);
                });
            });
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
    document.getElementById('job-model').value = '';
    document.getElementById('job-oob-timeout').value = 240;
    document.querySelectorAll('input[name="invocationType"]').forEach(function (r) { r.checked = false; });
    pendingJobSkillUuids = [];
    pendingJobToolIds = [];
    renderJobSkillPills();
    renderJobToolPills();
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

    const modelKey = job.provider ? job.provider + ':' + (job.modelId || '') : '';
    document.getElementById('job-model').value = modelKey;

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
    pendingJobSkillUuids = job.skillUuids ? job.skillUuids.slice() : [];
    pendingJobToolIds = job.toolIds ? job.toolIds.slice() : [];
    renderJobSkillPills();
    renderJobToolPills();
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

    const modelVal = document.getElementById('job-model').value;
    const sep = modelVal ? modelVal.indexOf(':') : -1;
    const provider = sep >= 0 ? modelVal.substring(0, sep) : null;
    const modelId = sep >= 0 ? modelVal.substring(sep + 1) : null;

    const body = {
        name: document.getElementById('job-name').value.trim(),
        aiPrompt: document.getElementById('job-prompt').value.trim(),
        invocationType: type,
        startTime: document.getElementById('job-start-time').value || null,
        repeatDuration: parseInt(document.getElementById('job-repeat-duration').value) || 0,
        durationType: document.getElementById('job-duration-type').value,
        agentTemplateId: document.getElementById('job-agent').value || null,
        provider: provider,
        modelId: modelId,
        oobTimeoutMinutes: parseInt(document.getElementById('job-oob-timeout').value) || 240,
        skillUuids: pendingJobSkillUuids.slice(),
        toolIds: pendingJobToolIds.slice()
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

function renderJobSkillPills() {
    const container = document.getElementById('job-skills-pills');
    if (!container) return;
    container.innerHTML = '';
    pendingJobSkillUuids.forEach(function (uuid) {
        const skill = (_jobSkillsCache || []).find(function (s) { return s.uuid === uuid; });
        const label = skill ? skill.name : uuid;
        const pill = document.createElement('span');
        pill.className = 'extra-pill skill-pill';
        pill.textContent = label;
        const btn = document.createElement('button');
        btn.className = 'pill-remove';
        btn.innerHTML = '&times;';
        btn.addEventListener('click', function () {
            pendingJobSkillUuids = pendingJobSkillUuids.filter(function (u) { return u !== uuid; });
            renderJobSkillPills();
        });
        pill.appendChild(btn);
        container.appendChild(pill);
    });
}

function renderJobToolPills() {
    const container = document.getElementById('job-tools-pills');
    if (!container) return;
    container.innerHTML = '';
    pendingJobToolIds.forEach(function (id) {
        const tool = (_jobToolsCache || []).find(function (t) { return t.id === id; });
        const label = tool ? tool.name : id;
        const pill = document.createElement('span');
        pill.className = 'extra-pill tool-pill';
        pill.textContent = label;
        const btn = document.createElement('button');
        btn.className = 'pill-remove';
        btn.innerHTML = '&times;';
        btn.addEventListener('click', function () {
            pendingJobToolIds = pendingJobToolIds.filter(function (i) { return i !== id; });
            renderJobToolPills();
        });
        pill.appendChild(btn);
        container.appendChild(pill);
    });
}

function setupJobSkillSearch() {
    const input = document.getElementById('job-skill-search');
    const dropdown = document.getElementById('job-skill-dropdown');
    if (!input || !dropdown) return;

    input.addEventListener('input', async function () {
        const q = input.value.trim().toLowerCase();
        dropdown.classList.add('hidden');
        dropdown.innerHTML = '';
        if (q.length < 1) return;
        if (!_jobSkillsCache) {
            try {
                const r = await fetch('/api/skills?page=0&pageSize=100');
                if (r.ok) _jobSkillsCache = await r.json();
            } catch (_e) {
                _jobSkillsCache = [];
            }
        }
        const matches = (_jobSkillsCache || []).filter(function (s) {
            return s.name.toLowerCase().includes(q) || (s.description || '').toLowerCase().includes(q);
        }).slice(0, 10);
        if (!matches.length) return;

        matches.forEach(function (skill) {
            const item = document.createElement('div');
            item.className = 'skills-search-item';
            item.textContent = skill.name;
            item.title = skill.description || '';
            item.addEventListener('click', function () {
                if (!pendingJobSkillUuids.includes(skill.uuid)) {
                    pendingJobSkillUuids.push(skill.uuid);
                    renderJobSkillPills();
                }
                input.value = '';
                dropdown.classList.add('hidden');
            });
            dropdown.appendChild(item);
        });
        dropdown.classList.remove('hidden');
    });

    document.addEventListener('click', function (e) {
        if (!input.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('hidden');
        }
    });
}

function setupJobToolSearch() {
    const input = document.getElementById('job-tool-search');
    const dropdown = document.getElementById('job-tool-dropdown');
    if (!input || !dropdown) return;

    input.addEventListener('input', async function () {
        const q = input.value.trim().toLowerCase();
        dropdown.classList.add('hidden');
        dropdown.innerHTML = '';
        if (q.length < 1) return;
        if (!_jobToolsCache) {
            try {
                const r = await fetch('/api/chat/tools');
                if (r.ok) _jobToolsCache = await r.json();
            } catch (_e) {
                _jobToolsCache = [];
            }
        }
        const matches = (_jobToolsCache || []).filter(function (t) {
            return t.name.toLowerCase().includes(q)
                || (t.description || '').toLowerCase().includes(q)
                || (t.category || '').toLowerCase().includes(q);
        }).slice(0, 10);
        if (!matches.length) return;

        matches.forEach(function (tool) {
            const item = document.createElement('div');
            item.className = 'skills-search-item';
            item.textContent = tool.name + (tool.category ? '  [' + tool.category + ']' : '');
            item.title = tool.description || '';
            item.addEventListener('click', function () {
                if (!pendingJobToolIds.includes(tool.id)) {
                    pendingJobToolIds.push(tool.id);
                    renderJobToolPills();
                }
                input.value = '';
                dropdown.classList.add('hidden');
            });
            dropdown.appendChild(item);
        });
        dropdown.classList.remove('hidden');
    });

    document.addEventListener('click', function (e) {
        if (!input.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('hidden');
        }
    });
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
