/* jobs-page.js */

let jobModal;
let jobPublishModal;
let allJobs = [];
let allUsers = [];
let modalNotificationUsers = [];
let githubConnection;
const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

const VID_GROUP_ID_PATTERN = /^[A-Za-z0-9]+$/;
const VID_ARTIFACT_ID_PATTERN = /^[A-Za-z0-9]+$/;

function isLegacySlashId(id) {
    return typeof id === 'string' && id.indexOf('/') >= 0;
}

function contributionPostHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    return headers;
}

document.addEventListener('DOMContentLoaded', function () {
    jobModal = new VorkModal(document.getElementById('jobModal'));
    jobPublishModal = new VorkModal(document.getElementById('job-publish-modal'));
    githubConnection = window.VorkGitHubConnection
        ? window.VorkGitHubConnection.init({
            alertFn: showAlert
        })
        : null;
    loadAgents();
    loadUsers();
    loadJobsJson();

    document.addEventListener('click', function (e) {
        if (!e.target.closest('#job-notify-search') && !e.target.closest('#job-notify-dropdown')) {
            const dropdown = document.getElementById('job-notify-dropdown');
            if (dropdown) dropdown.classList.add('hidden');
        }
    });

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

function loadUsers() {
    fetch('/api/users')
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (users) { allUsers = Array.isArray(users) ? users : []; })
        .catch(function () { allUsers = []; });
}

function openCreate() {
    document.getElementById('jobModalLabel').textContent = 'New Job';
    document.getElementById('job-id').value = '';
    document.getElementById('job-name').value = '';
    document.getElementById('job-group-id').value = '';
    document.getElementById('job-artifact-id').value = '';
    document.getElementById('job-prompt').value = '';
    document.getElementById('job-start-time').value = '';
    document.getElementById('job-repeat-duration').value = 1;
    document.getElementById('job-duration-type').value = 'HOURS';
    document.getElementById('job-agent').value = 'agent-tpl-automation-reporter-001';
    document.getElementById('job-oob-timeout').value = 240;
    document.getElementById('job-notify-search').value = '';
    modalNotificationUsers = [];
    renderNotificationUserPills();
    document.querySelectorAll('input[name="invocationType"]').forEach(function (r) { r.checked = false; });
    document.getElementById('modal-alert-area').innerHTML = '';
    setVidFieldDisabled(false);
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
    document.getElementById('job-group-id').value = job.groupId || '';
    document.getElementById('job-artifact-id').value = job.artifactId || '';
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
    document.getElementById('job-notify-search').value = '';
    modalNotificationUsers = Array.isArray(job.notificationUserIds) ? job.notificationUserIds.slice() : [];
    renderNotificationUserPills();
    document.getElementById('modal-alert-area').innerHTML = '';
    setVidFieldDisabled(true);
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
        groupId: document.getElementById('job-group-id').value.trim(),
        artifactId: document.getElementById('job-artifact-id').value.trim(),
        aiPrompt: document.getElementById('job-prompt').value.trim(),
        invocationType: type,
        startTime: document.getElementById('job-start-time').value || null,
        repeatDuration: parseInt(document.getElementById('job-repeat-duration').value) || 0,
        durationType: document.getElementById('job-duration-type').value,
        agentTemplateId: document.getElementById('job-agent').value || null,
        provider: null,
        modelId: null,
        oobTimeoutMinutes: parseInt(document.getElementById('job-oob-timeout').value) || 240,
        notificationUserIds: modalNotificationUsers.slice(),
        skillUuids: [],
        toolIds: []
    };

    if (!id && !body.artifactId) {
        body.artifactId = toArtifactId(body.name);
        document.getElementById('job-artifact-id').value = body.artifactId;
    }

    const validationError = validateVidInputs(body.groupId, body.artifactId);
    if (validationError) {
        showAlert(validationError, 'warning');
        return;
    }

    const btn = document.getElementById('btn-save-job');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving&hellip;';

    try {
        const url = id
            ? (isLegacySlashId(id)
                ? '/api/jobs/update?id=' + encodeURIComponent(id)
                : '/api/jobs/' + encodeURIComponent(id))
            : '/api/jobs';
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
        const url = isLegacySlashId(id)
            ? '/api/jobs/delete?id=' + encodeURIComponent(id)
            : '/api/jobs/' + encodeURIComponent(id);
        const res = await fetch(url, { method: 'DELETE' });
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
        const url = isLegacySlashId(id)
            ? '/api/jobs/run?id=' + encodeURIComponent(id)
            : '/api/jobs/' + encodeURIComponent(id) + '/run';
        const res = await fetch(url, { method: 'POST' });
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
        const url = isLegacySlashId(id)
            ? '/api/jobs/pause?id=' + encodeURIComponent(id)
            : '/api/jobs/' + encodeURIComponent(id) + '/pause';
        const res = await fetch(url, { method: 'POST' });
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
        const url = isLegacySlashId(id)
            ? '/api/jobs/resume?id=' + encodeURIComponent(id)
            : '/api/jobs/' + encodeURIComponent(id) + '/resume';
        const res = await fetch(url, { method: 'POST' });
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
    window.location.href = isLegacySlashId(id)
        ? '/api/jobs/export?id=' + encodeURIComponent(id)
        : '/api/jobs/' + encodeURIComponent(id) + '/export';
}

async function recommendJobVersion(id) {
    if (!id) {
        showAlert('Job id is required for version recommendation.', 'warning');
        return;
    }
    try {
        const breakingChange = window.confirm('Does this release include breaking changes?\nOK = yes (major bump), Cancel = no (minor bump).');
        const recommendUrl = '/api/contributions/jobs/' + encodeURIComponent(id)
            + '/recommend-version?breakingChange=' + encodeURIComponent(String(breakingChange));
        const res = await fetch(recommendUrl, {
            method: 'GET'
        });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok || data.error) {
            showAlert(data.error || data.message || 'Failed to recommend version.', 'danger');
            return;
        }
        const rec = data.recommendation || {};
        const latest = rec.latestVersion ? ('Latest in staging: ' + rec.latestVersion + '. ') : 'No staging version found. ';
        showAlert(latest + 'Recommended next version: ' + (rec.recommendedVersion || 'n/a') + '.', 'success');
    } catch (_e) {
        showAlert('Network error getting version recommendation.', 'danger');
    }
}

async function checkJobContributionDependencies(id) {
    if (!id) {
        showAlert('Job id is required for dependency pre-check.', 'warning');
        return;
    }
    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndDisplay === 'function') {
        await window.VorkDependencyPrecheck.runAndDisplay('jobs', id, 'Job', showAlert);
        return;
    }
    showAlert('Dependency pre-check helper is not available on this page.', 'warning');
}

async function publishJobContribution(id) {
    if (!id) {
        showAlert('Job id is required for publish.', 'warning');
        return;
    }

    if (window.VorkDependencyPrecheck && typeof window.VorkDependencyPrecheck.runAndGate === 'function') {
        const ready = await window.VorkDependencyPrecheck.runAndGate('jobs', id, 'Job', showAlert);
        if (!ready) {
            return;
        }
    }

    document.getElementById('job-publish-id').value = id;
    clearAlert('job-publish-modal-alert');
    setJobPublishLoading(true);
    jobPublishModal.show();

    try {
        const draftRes = await fetch('/api/contributions/jobs/' + encodeURIComponent(id) + '/publish-draft', {
            method: 'POST',
            headers: contributionPostHeaders(),
            body: JSON.stringify({})
        });
        const draftData = await draftRes.json().catch(function () { return {}; });
        if (!draftRes.ok || draftData.error) {
            showAlert(draftData.error || draftData.message || 'Failed to generate publish draft.', 'danger', 'job-publish-modal-alert');
            setJobPublishLoading(false);
            return;
        }

        const draft = draftData.draft || {};
        document.getElementById('job-publish-version').value = (draft.version || '').trim();
        document.getElementById('job-publish-pr-title').value = (draft.prTitle || '').trim();
        document.getElementById('job-publish-change-summary').value = (draft.changeSummary || '').trim();
        document.getElementById('job-publish-commit-message').value = (draft.commitMessage || '').trim();
        document.getElementById('job-publish-pr-body').value = (draft.prBody || '').trim();
        document.getElementById('job-publish-release-notes').value = (draft.releaseNotes || '').trim();
        document.getElementById('job-publish-reviewer-hints').value = (draft.reviewerHints || '').trim();
        document.getElementById('job-publish-breaking-change').checked = !!draft.breakingChange;

        if (draft.latestVersion) {
            showAlert('Latest in staging: ' + draft.latestVersion + '. Draft generated and ready to edit.', 'success', 'job-publish-modal-alert');
        }

        setJobPublishLoading(false);
    } catch (_e) {
        showAlert('Network error during draft generation.', 'danger', 'job-publish-modal-alert');
        setJobPublishLoading(false);
    }
}

async function submitJobPublishFromModal() {
    const id = document.getElementById('job-publish-id').value;
    const version = document.getElementById('job-publish-version').value.trim();
    const prTitle = document.getElementById('job-publish-pr-title').value.trim();
    const changeSummary = document.getElementById('job-publish-change-summary').value.trim();
    const commitMessage = document.getElementById('job-publish-commit-message').value.trim();
    const prBody = document.getElementById('job-publish-pr-body').value.trim();
    const releaseNotes = document.getElementById('job-publish-release-notes').value.trim();
    const reviewerHints = document.getElementById('job-publish-reviewer-hints').value.trim();
    const breakingChange = !!document.getElementById('job-publish-breaking-change').checked;

    if (!id) {
        showAlert('Job id is missing for publish.', 'danger', 'job-publish-modal-alert');
        return;
    }
    if (!/^[0-9]+\.[0-9]+$/.test(version) || version.toUpperCase() === 'SNAPSHOT') {
        showAlert('Version must follow major.minor and cannot be SNAPSHOT.', 'danger', 'job-publish-modal-alert');
        return;
    }
    if (!prTitle) {
        showAlert('PR title is required.', 'danger', 'job-publish-modal-alert');
        return;
    }
    if (!changeSummary) {
        showAlert('Change summary is required.', 'danger', 'job-publish-modal-alert');
        return;
    }

    setJobPublishLoading(true, 'Creating PR...');
    clearAlert('job-publish-modal-alert');

    try {
        const publishRes = await fetch('/api/contributions/jobs/' + encodeURIComponent(id) + '/publish', {
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
            showAlert(publishData.error || publishData.message || 'Publish failed.', 'danger', 'job-publish-modal-alert');
            setJobPublishLoading(false);
            return;
        }

        const pullRequest = publishData.pullRequest || {};
        showAlert('Published. PR: ' + (pullRequest.url || 'created'), 'success');
        jobPublishModal.hide();
        setTimeout(function () { location.reload(); }, 900);
    } catch (_e) {
        showAlert('Network error during publish.', 'danger', 'job-publish-modal-alert');
        setJobPublishLoading(false);
    }
}

function setJobPublishLoading(isLoading, loadingLabel) {
    const fields = [
        'job-publish-version',
        'job-publish-pr-title',
        'job-publish-change-summary',
        'job-publish-commit-message',
        'job-publish-pr-body',
        'job-publish-release-notes',
        'job-publish-reviewer-hints',
        'job-publish-breaking-change'
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

    const submitBtn = document.getElementById('job-publish-submit-btn');
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

async function createJobSnapshotContribution(id) {
    if (!id) {
        showAlert('Job id is required for snapshot.', 'warning');
        return;
    }
    if (!window.confirm('Create a SNAPSHOT clone from this immutable job?')) {
        return;
    }
    try {
        const res = await fetch('/api/contributions/jobs/' + encodeURIComponent(id) + '/snapshot', {
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

async function refreshJobContributionStatus(id) {
    if (!id) {
        showAlert('Job id is required to refresh status.', 'warning');
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
        const promoted = (summary.jobsPromotedToStaged || 0);
        showAlert('Status refresh complete. Jobs promoted to STAGED: ' + promoted + '.', 'success');
        setTimeout(function () { location.reload(); }, 700);
    } catch (_e) {
        showAlert('Network error refreshing contribution status.', 'danger');
    }
}

function renderNotificationUserPills() {
    const container = document.getElementById('job-notify-pill-container');
    if (!container) return;
    container.innerHTML = '';

    if (!modalNotificationUsers || modalNotificationUsers.length === 0) {
        container.innerHTML = '<span class="text-muted small">No explicit recipients selected (job owner fallback applies).</span>';
        return;
    }

    modalNotificationUsers.forEach(function (username) {
        const user = allUsers.find(function (u) { return u.username === username; });
        const label = user ? user.username : username;
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-user"></i>'
            + '<span>' + escapeHtml(label) + '</span>'
            + '<span class="remove-tool" title="Remove recipient">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeNotificationUser(username);
        });
        container.appendChild(pill);
    });
}

function removeNotificationUser(username) {
    modalNotificationUsers = (modalNotificationUsers || []).filter(function (u) { return u !== username; });
    renderNotificationUserPills();
    filterNotificationUsers();
}

function addNotificationUser(username) {
    if (!username) return;
    if (!modalNotificationUsers.includes(username)) {
        modalNotificationUsers.push(username);
        renderNotificationUserPills();
    }
}

function filterNotificationUsers() {
    const query = document.getElementById('job-notify-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('job-notify-dropdown');
    const list = document.getElementById('job-notify-options');
    if (!dropdown || !list) return;

    const matches = (allUsers || []).filter(function (user) {
        const username = user.username || '';
        if (modalNotificationUsers.includes(username)) return false;
        if (!query) return true;
        return username.toLowerCase().includes(query)
            || (user.role || '').toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (user) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action py-1 px-2';
        li.innerHTML = ''
            + '<div class="d-flex align-items-center gap-2">'
            + '  <i class="fa-solid fa-user fa-xs text-secondary"></i>'
            + '  <span class="fw-semibold small">' + escapeHtml(user.username || '') + '</span>'
            + (user.role ? '  <span class="badge bg-dark border border-secondary text-secondary">' + escapeHtml(user.role) + '</span>' : '')
            + '</div>';
        li.addEventListener('click', function () {
            addNotificationUser(user.username);
            document.getElementById('job-notify-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function validateVidInputs(groupId, artifactId) {
    if (!groupId) {
        return 'Group ID is required.';
    }
    if (!artifactId) {
        return 'Artifact ID is required.';
    }
    if (groupId.length < 3 || groupId.length > 64) {
        return 'Group ID length must be between 3 and 64 characters.';
    }
    if (artifactId.length < 3 || artifactId.length > 64) {
        return 'Artifact ID length must be between 3 and 64 characters.';
    }
    if (!VID_GROUP_ID_PATTERN.test(groupId)) {
        return 'Group ID must be alphanumeric only (letters and numbers), with no spaces.';
    }
    if (!VID_ARTIFACT_ID_PATTERN.test(artifactId)) {
        return 'Artifact ID must be alphanumeric only (letters and numbers), with no spaces.';
    }
    return null;
}

function setVidFieldDisabled(disabled) {
    document.getElementById('job-group-id').disabled = disabled;
    document.getElementById('job-artifact-id').disabled = disabled;
}

function toArtifactId(name) {
    const base = (name || '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '')
        .trim();
    if (base.length >= 3) {
        return base.slice(0, 64);
    }
    return 'job';
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

function clearAlert(targetId) {
    const area = document.getElementById(targetId || 'alert-area');
    if (area) area.innerHTML = '';
}

function showAlert(msg, type, targetId) {
    const modalEl = document.getElementById('jobModal');
    const isOpen = modalEl && modalEl.classList.contains('show');
    const area = document.getElementById(targetId || (isOpen ? 'modal-alert-area' : 'alert-area'));
    if (!area) return;
    area.innerHTML = '<div class="alert alert-' + type + ' alert-dismissible fade show py-2 small" role="alert">' +
        msg + '<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>';
}

function toDatetimeLocal(d) {
    const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
        'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}
