/* settings-oauth-templates.js - OAuth Template management */
/* jshint esversion: 6 */

let allTemplates = [];
const isReadOnly = document.body.getAttribute('data-oauth-templates-read-only') === 'true';
let githubConnection = null;

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

const alertArea = document.getElementById('alert-area');
const emptyState = document.getElementById('empty-state');
const tableContainer = document.getElementById('table-container');
const templatesBody = document.getElementById('templates-body');

const templateModal = document.getElementById('templateModal');
const templateModalTitle = document.getElementById('template-modal-title');
const templateModalAlert = document.getElementById('template-modal-alert');

const connectModal = document.getElementById('connectModal');
const connectModalAlert = document.getElementById('connect-modal-alert');
const connectTemplateId = document.getElementById('connect-template-id');
const connectTemplateName = document.getElementById('connect-template-name');
const connectProfileName = document.getElementById('connect-profile-name');
const connectClientId = document.getElementById('connect-client-id');
const connectClientSecret = document.getElementById('connect-client-secret');
const connectRedirectUri = document.getElementById('connect-redirect-uri');

const publishModal = document.getElementById('oauth-template-publish-modal');
const publishModalAlert = document.getElementById('oauth-template-publish-alert');
const publishTemplateId = document.getElementById('oauth-template-publish-id');
const publishPrTitle = document.getElementById('oauth-template-publish-pr-title');
const publishChangeSummary = document.getElementById('oauth-template-publish-change-summary');
const publishCommitMessage = document.getElementById('oauth-template-publish-commit-message');
const publishPrBody = document.getElementById('oauth-template-publish-pr-body');
const publishReleaseNotes = document.getElementById('oauth-template-publish-release-notes');
const publishReviewerHints = document.getElementById('oauth-template-publish-reviewer-hints');

let connectDefaults = null;

const templateId = document.getElementById('template-id');
const templateName = document.getElementById('template-name');
const templateClientName = document.getElementById('template-client-name');
const templateDescription = document.getElementById('template-description');
const templateAuthorizeEndpoint = document.getElementById('template-authorize-endpoint');
const templateTokenEndpoint = document.getElementById('template-token-endpoint');
const templateScopes = document.getElementById('template-scopes');
const templateAuthorizationParams = document.getElementById('template-authorization-params');

function init() {
    githubConnection = window.VorkGitHubConnection
        ? window.VorkGitHubConnection.init({
            connectButtonId: 'github-connect-btn',
            statusLabelId: 'github-connection-status',
            alertFn: showAlert,
            onStatusChange: function (status) {
                applyContributionActionState(!!(status && status.connected));
            }
        })
        : null;

    const newTemplateButton = document.getElementById('new-template-btn');
    if (newTemplateButton) {
        newTemplateButton.addEventListener('click', openCreateTemplateModal);
    }
    const importTemplatesButton = document.getElementById('import-templates-btn');
    const importTemplatesInput = document.getElementById('import-templates-input');
    if (importTemplatesButton && importTemplatesInput) {
        importTemplatesButton.addEventListener('click', function () {
            importTemplatesInput.click();
        });
        importTemplatesInput.addEventListener('change', function () {
            importTemplates(importTemplatesInput);
        });
    }
    document.getElementById('close-template-modal').addEventListener('click', closeTemplateModal);
    document.getElementById('cancel-template-btn').addEventListener('click', closeTemplateModal);
    document.getElementById('save-template-btn').addEventListener('click', saveTemplate);
    document.getElementById('close-connect-modal').addEventListener('click', closeConnectModal);
    document.getElementById('cancel-connect-btn').addEventListener('click', closeConnectModal);
    document.getElementById('start-connect-btn').addEventListener('click', startConnectFromTemplate);
    const syncMainButton = document.getElementById('sync-main-btn');
    if (syncMainButton) {
        syncMainButton.addEventListener('click', synchronizeTemplatesFromMain);
    }
    document.getElementById('oauth-template-publish-close').addEventListener('click', closePublishModal);
    document.getElementById('oauth-template-publish-cancel').addEventListener('click', closePublishModal);
    document.getElementById('oauth-template-publish-submit').addEventListener('click', submitOAuthTemplatePublishFromModal);

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && !templateModal.classList.contains('hidden')) {
            closeTemplateModal();
        } else if (event.key === 'Escape' && !connectModal.classList.contains('hidden')) {
            closeConnectModal();
        } else if (event.key === 'Escape' && !publishModal.classList.contains('hidden')) {
            closePublishModal();
        }
    });

    loadTemplates();
}

document.addEventListener('DOMContentLoaded', init);

async function loadTemplates() {
    try {
        const response = await fetch('/api/oauth-templates');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        allTemplates = await response.json();
        renderTemplates();
    } catch (error) {
        showAlert('Failed to load OAuth templates: ' + (error.message || 'unknown error'), 'danger');
    }
}

function renderTemplates() {
    templatesBody.innerHTML = '';

    if (!allTemplates || allTemplates.length === 0) {
        emptyState.classList.remove('hidden');
        tableContainer.classList.add('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    tableContainer.classList.remove('hidden');

    allTemplates.forEach(function (template) {
        const row = document.createElement('tr');
        row.className = 'border-b border-zinc-800/80 last:border-0';
        const artifactStatus = template.artifactStatus || 'SNAPSHOT';
        const isSnapshot = artifactStatus === 'SNAPSHOT' || artifactStatus === 'REJECTED';
        const canDelete = artifactStatus === 'SNAPSHOT' || artifactStatus === 'REJECTED';

        const scopes = (template.scopes || []).length === 0
            ? '<span class="text-zinc-500">—</span>'
            : (template.scopes || []).map(function (scope) {
                return '<span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-1.5 py-0.5 text-xs text-zinc-300 mr-1 mb-1">' + escapeHtml(scope) + '</span>';
            }).join('');

        const contributionActions = [];
        if (isSnapshot) {
            contributionActions.push('<button type="button" class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15 contrib-action" data-action="publish" data-id="' + escapeHtml(template.id) + '" data-default-title="Publish to staging via PR" title="Publish to staging via PR" disabled><i class="fa-solid fa-cloud-arrow-up"></i></button>');
        } else if (artifactStatus === 'SUBMITTED') {
            contributionActions.push('<button type="button" class="rounded-md border border-blue-500/40 px-2 py-1 text-xs text-blue-300 transition-colors hover:bg-blue-500/15 contrib-action" data-action="refresh-status" data-id="' + escapeHtml(template.id) + '" data-default-title="Refresh contribution status" title="Refresh contribution status" disabled><i class="fa-solid fa-rotate-right"></i></button>');
        }

        const deleteAction = canDelete
            ? '<button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" data-action="delete" data-id="' + escapeHtml(template.id) + '" title="Delete template"><i class="fa-solid fa-trash"></i></button>'
            : '<button type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-500 cursor-not-allowed" title="Only SNAPSHOT or REJECTED templates can be deleted" disabled><i class="fa-solid fa-trash"></i></button>';

        row.innerHTML = ''
            + '<td class="px-3 py-2 align-top font-semibold text-zinc-100">' + escapeHtml(template.name || '') + '</td>'
            + '<td class="px-3 py-2 align-top text-xs font-mono text-zinc-300">' + escapeHtml(template.clientName || '') + '</td>'
            + '<td class="px-3 py-2 align-top text-zinc-300">' + escapeHtml(template.description || '') + '</td>'
            + '<td class="px-3 py-2 align-top">' + scopes + '</td>'
            + '<td class="px-3 py-2 align-top"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-950 px-2 py-0.5 text-xs text-zinc-300">' + escapeHtml(artifactStatus) + '</span></td>'
            + '<td class="px-3 py-2 align-top text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <button class="rounded-md border border-emerald-500/40 px-2 py-1 text-xs text-emerald-300 transition-colors hover:bg-emerald-500/15" data-action="connect" data-id="' + escapeHtml(template.id) + '" title="Connect using template">'
            + '      <i class="fa-solid fa-plug"></i>'
            + '    </button>'
            + (isReadOnly ? '' : ''
                + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" data-action="export" data-id="' + escapeHtml(template.id) + '" title="Export template">'
                + '      <i class="fa-solid fa-file-export"></i>'
                + '    </button>'
                + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" data-action="edit" data-id="' + escapeHtml(template.id) + '" title="Edit template">'
                + '      <i class="fa-solid fa-pen"></i>'
                + '    </button>'
                + contributionActions.join('')
                + deleteAction)
            + '  </div>'
            + '</td>';

        templatesBody.appendChild(row);
    });

    templatesBody.querySelectorAll('button[data-action="connect"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openConnectModal(button.getAttribute('data-id'));
        });
    });

    if (!isReadOnly) {
        templatesBody.querySelectorAll('button[data-action="publish"]').forEach(function (button) {
            button.addEventListener('click', function () {
                publishOAuthTemplateContribution(button.getAttribute('data-id'));
            });
        });
        templatesBody.querySelectorAll('button[data-action="refresh-status"]').forEach(function (button) {
            button.addEventListener('click', function () {
                refreshOAuthTemplateContributionStatus(button.getAttribute('data-id'));
            });
        });
        templatesBody.querySelectorAll('button[data-action="export"]').forEach(function (button) {
            button.addEventListener('click', function () {
                exportTemplate(button.getAttribute('data-id'));
            });
        });

        templatesBody.querySelectorAll('button[data-action="edit"]').forEach(function (button) {
            button.addEventListener('click', function () {
                openEditTemplateModal(button.getAttribute('data-id'));
            });
        });

        templatesBody.querySelectorAll('button[data-action="delete"]').forEach(function (button) {
            button.addEventListener('click', function () {
                deleteTemplate(button.getAttribute('data-id'));
            });
        });
    }

    if (githubConnection && typeof githubConnection.refreshStatus === 'function') {
        githubConnection.refreshStatus();
    } else {
        applyContributionActionState(false);
    }
}

function applyContributionActionState(isConnected) {
    const reason = 'Connect GitHub before publishing or synchronizing templates.';
    document.querySelectorAll('.contrib-action').forEach(function (btn) {
        if (!(btn instanceof HTMLButtonElement)) {
            return;
        }
        btn.disabled = !isConnected;
        if (!isConnected) {
            btn.setAttribute('title', reason);
        } else {
            const defaultTitle = btn.getAttribute('data-default-title');
            if (defaultTitle) {
                btn.setAttribute('title', defaultTitle);
            }
        }
    });
}

function openCreateTemplateModal() {
    templateModalTitle.textContent = 'New OAuth Template';
    templateId.value = '';
    templateName.value = '';
    templateClientName.value = '';
    templateDescription.value = '';
    templateAuthorizeEndpoint.value = '';
    templateTokenEndpoint.value = '';
    templateScopes.value = '';
    templateAuthorizationParams.value = '';
    clearTemplateModalAlert();
    openTemplateModal();
}

function openEditTemplateModal(id) {
    const template = allTemplates.find(function (entry) { return entry.id === id; });
    if (!template) {
        showAlert('Template not found. Reload and try again.', 'warning');
        return;
    }

    templateModalTitle.textContent = 'Edit OAuth Template';
    templateId.value = template.id || '';
    templateName.value = template.name || '';
    templateClientName.value = template.clientName || '';
    templateDescription.value = template.description || '';
    templateAuthorizeEndpoint.value = template.authorizeEndpoint || '';
    templateTokenEndpoint.value = template.tokenEndpoint || '';
    templateScopes.value = (template.scopes || []).join(', ');
    templateAuthorizationParams.value = serializeAuthorizationParams(template.authorizationParameters || {});
    clearTemplateModalAlert();
    openTemplateModal();
}

function serializeAuthorizationParams(params) {
    return Object.entries(params).map(function (entry) {
        return entry[0] + '=' + (entry[1] == null ? '' : String(entry[1]));
    }).join('\n');
}

function parseAuthorizationParams(raw) {
    const result = {};
    if (!raw || !raw.trim()) {
        return result;
    }

    const lines = raw.split(/\r?\n/);
    lines.forEach(function (line) {
        const trimmed = line.trim();
        if (!trimmed) {
            return;
        }
        const index = trimmed.indexOf('=');
        if (index <= 0) {
            result[trimmed] = '';
            return;
        }
        const key = trimmed.substring(0, index).trim();
        const value = trimmed.substring(index + 1).trim();
        if (key) {
            result[key] = value;
        }
    });

    return result;
}

function parseScopes(raw) {
    if (!raw || !raw.trim()) {
        return [];
    }
    return raw.split(',')
        .map(function (scope) { return scope.trim(); })
        .filter(function (scope) { return scope.length > 0; });
}

async function saveTemplate() {
    const id = templateId.value.trim();
    const payload = {
        id: id || null,
        name: templateName.value.trim(),
        clientName: templateClientName.value.trim(),
        description: templateDescription.value.trim(),
        authorizeEndpoint: templateAuthorizeEndpoint.value.trim(),
        tokenEndpoint: templateTokenEndpoint.value.trim(),
        scopes: parseScopes(templateScopes.value),
        authorizationParameters: parseAuthorizationParams(templateAuthorizationParams.value)
    };

    if (!payload.name || !payload.authorizeEndpoint || !payload.tokenEndpoint) {
        showTemplateModalAlert('Name, authorize endpoint, and token endpoint are required.', 'warning');
        return;
    }

    const url = id ? '/api/oauth-templates/' + encodeURIComponent(id) : '/api/oauth-templates';
    const method = id ? 'PUT' : 'POST';

    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }

        const response = await fetch(url, {
            method: method,
            headers: headers,
            body: JSON.stringify(payload)
        });

        const result = await parseJson(response);
        if (!response.ok) {
            showTemplateModalAlert(result.error || 'Failed to save template.', 'danger');
            return;
        }

        closeTemplateModal();
        showAlert(id ? 'OAuth template updated.' : 'OAuth template created.', 'success');
        await loadTemplates();
    } catch (error) {
        showTemplateModalAlert('Network error while saving template.', 'danger');
    }
}

async function deleteTemplate(id) {
    if (!id) {
        return;
    }

    if (!confirm('Delete this OAuth template?')) {
        return;
    }

    try {
        const headers = {};
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }

        const response = await fetch('/api/oauth-templates/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: headers
        });

        if (!response.ok) {
            const result = await parseJson(response);
            showAlert(result.error || 'Failed to delete template.', 'danger');
            return;
        }

        showAlert('OAuth template deleted.', 'success');
        await loadTemplates();
    } catch (_error) {
        showAlert('Network error while deleting template.', 'danger');
    }
}

async function synchronizeTemplatesFromMain() {
    if (!window.confirm('Synchronize OAuth templates from main branch? Local rows with matching clientName will be updated.')) {
        return;
    }
    try {
        const headers = {};
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        const response = await fetch('/api/oauth-templates/synchronize', {
            method: 'POST',
            headers: headers
        });
        const result = await parseJson(response);
        if (!response.ok || result.status === 'error') {
            showAlert(result.message || 'Failed to synchronize templates from main.', 'danger');
            return;
        }
        showAlert('Synchronized from main: created ' + (result.created || 0)
            + ', updated ' + (result.updated || 0)
            + ', skipped ' + (result.skipped || 0) + '.', 'success');
        await loadTemplates();
    } catch (_error) {
        showAlert('Network error during synchronization.', 'danger');
    }
}

async function publishOAuthTemplateContribution(id) {
    if (!id) {
        showAlert('Template id is required for publish.', 'warning');
        return;
    }
    publishTemplateId.value = id;
    clearPublishModalAlert();
    setPublishModalLoading(true);
    publishModal.classList.remove('hidden');

    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        const response = await fetch('/api/contributions/oauth-templates/' + encodeURIComponent(id) + '/publish-draft', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({})
        });
        const result = await parseJson(response);
        if (!response.ok || result.error) {
            showPublishModalAlert(result.error || 'Failed to generate publish draft.', 'danger');
            setPublishModalLoading(false);
            return;
        }
        const draft = result.draft || {};
        publishPrTitle.value = draft.prTitle || '';
        publishChangeSummary.value = draft.changeSummary || '';
        publishCommitMessage.value = draft.commitMessage || '';
        publishPrBody.value = draft.prBody || '';
        publishReleaseNotes.value = draft.releaseNotes || '';
        publishReviewerHints.value = draft.reviewerHints || '';
        setPublishModalLoading(false);
    } catch (_error) {
        showPublishModalAlert('Network error during draft generation.', 'danger');
        setPublishModalLoading(false);
    }
}

async function submitOAuthTemplatePublishFromModal() {
    const id = (publishTemplateId.value || '').trim();
    const payload = {
        prTitle: (publishPrTitle.value || '').trim(),
        changeSummary: (publishChangeSummary.value || '').trim(),
        commitMessage: (publishCommitMessage.value || '').trim(),
        prBody: (publishPrBody.value || '').trim(),
        releaseNotes: (publishReleaseNotes.value || '').trim(),
        reviewerHints: (publishReviewerHints.value || '').trim()
    };
    if (!id) {
        showPublishModalAlert('Template id is missing for publish.', 'danger');
        return;
    }
    if (!payload.prTitle) {
        showPublishModalAlert('PR title is required.', 'danger');
        return;
    }
    if (!payload.changeSummary) {
        showPublishModalAlert('Change summary is required.', 'danger');
        return;
    }

    clearPublishModalAlert();
    setPublishModalLoading(true, 'Creating PR...');
    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        const response = await fetch('/api/contributions/oauth-templates/' + encodeURIComponent(id) + '/publish', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(payload)
        });
        const result = await parseJson(response);
        if (!response.ok || result.error) {
            showPublishModalAlert(result.error || 'Publish failed.', 'danger');
            setPublishModalLoading(false);
            return;
        }
        closePublishModal();
        const pr = result.pullRequest || {};
        showAlert('Published. PR: ' + (pr.url || 'created'), 'success');
        await loadTemplates();
    } catch (_error) {
        showPublishModalAlert('Network error during publish.', 'danger');
        setPublishModalLoading(false);
    }
}

async function refreshOAuthTemplateContributionStatus(id) {
    if (!id) {
        showAlert('Template id is required to refresh status.', 'warning');
        return;
    }
    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        const response = await fetch('/api/contributions/promotions/reconcile', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({})
        });
        const result = await parseJson(response);
        if (!response.ok || result.error) {
            showAlert(result.error || 'Failed to refresh contribution status.', 'danger');
            return;
        }
        const summary = result.summary || {};
        showAlert('Status refresh complete. OAuth templates promoted to PUBLISHED: ' + (summary.oauthTemplatesPromotedToPublished || 0) + '.', 'success');
        await loadTemplates();
    } catch (_error) {
        showAlert('Network error refreshing contribution status.', 'danger');
    }
}

function setPublishModalLoading(isLoading, loadingLabel) {
    [publishPrTitle, publishChangeSummary, publishCommitMessage, publishPrBody, publishReleaseNotes, publishReviewerHints]
        .forEach(function (el) {
            if (!el) {
                return;
            }
            if (isLoading) {
                el.setAttribute('disabled', 'disabled');
            } else {
                el.removeAttribute('disabled');
            }
        });
    const submit = document.getElementById('oauth-template-publish-submit');
    if (!submit) {
        return;
    }
    if (isLoading) {
        submit.setAttribute('disabled', 'disabled');
        submit.dataset.label = submit.textContent;
        submit.textContent = loadingLabel || 'Preparing draft...';
    } else {
        submit.removeAttribute('disabled');
        submit.textContent = submit.dataset.label || 'Submit PR';
    }
}

function closePublishModal() {
    publishModal.classList.add('hidden');
    publishTemplateId.value = '';
    publishPrTitle.value = '';
    publishChangeSummary.value = '';
    publishCommitMessage.value = '';
    publishPrBody.value = '';
    publishReleaseNotes.value = '';
    publishReviewerHints.value = '';
    clearPublishModalAlert();
    setPublishModalLoading(false);
}

function showPublishModalAlert(message, type) {
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;
    publishModalAlert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + tone + '">' + escapeHtml(message) + '</div>';
}

function clearPublishModalAlert() {
    publishModalAlert.innerHTML = '';
}

function exportTemplate(id) {
    if (!id) {
        return;
    }
    window.location.href = '/api/oauth-templates/' + encodeURIComponent(id) + '/export';
}

async function importTemplates(input) {
    const file = input.files[0];
    if (!file) {
        return;
    }
    input.value = '';

    let payload;
    try {
        payload = JSON.parse(await file.text());
    } catch (error) {
        const detail = error && error.message ? ': ' + error.message : '.';
        showAlert('Could not parse file - not valid JSON' + detail, 'danger');
        return;
    }

    if (!payload.vorkOAuthTemplateExport || !Array.isArray(payload.templates) || payload.templates.length === 0) {
        showAlert('Not a valid Vork OAuth template export file.', 'danger');
        return;
    }

    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }

        const response = await fetch('/api/oauth-templates/import', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(payload)
        });

        const result = await parseJson(response);
        if (!response.ok) {
            const detail = result.detail ? ' (' + result.detail + ')' : '';
            showAlert('Import failed: ' + escapeHtml((result.message || 'Unknown error') + detail), 'danger');
            return;
        }

        showAlert('Imported OAuth templates: created ' + result.created + ', updated ' + result.updated + '.', 'success');
        await loadTemplates();
    } catch (_error) {
        showAlert('Network error during import.', 'danger');
    }
}

function openTemplateModal() {
    templateModal.classList.remove('hidden');
    templateName.focus();
}

function closeTemplateModal() {
    templateModal.classList.add('hidden');
    clearTemplateModalAlert();
}

function openConnectModal(id) {
    const template = allTemplates.find(function (entry) { return entry.id === id; });
    if (!template) {
        showAlert('Template not found. Reload and try again.', 'warning');
        return;
    }

    connectTemplateId.value = id || '';
    connectTemplateName.value = (template.name || '') + (template.clientName ? ' [' + template.clientName + ']' : '');
    connectProfileName.value = 'default';
    connectClientId.value = '';
    connectClientSecret.value = '';
    connectRedirectUri.value = '';
    clearConnectModalAlert();
    connectModal.classList.remove('hidden');
    loadConnectDefaults().then(function () {
        if (connectDefaults && connectDefaults.redirectUri) {
            connectRedirectUri.value = connectDefaults.redirectUri;
        }
    }).catch(function () {
        // Ignore; user can enter redirect URI manually.
    });
    connectClientId.focus();
}

function closeConnectModal() {
    connectModal.classList.add('hidden');
    clearConnectModalAlert();
}

async function startConnectFromTemplate() {
    const templateIdValue = connectTemplateId.value.trim();
    const payload = {
        profileName: connectProfileName.value.trim(),
        clientId: connectClientId.value.trim(),
        clientSecret: connectClientSecret.value,
        redirectUri: connectRedirectUri.value.trim(),
        returnPath: window.location.pathname + window.location.search
    };

    if (!templateIdValue) {
        showConnectModalAlert('Template id is missing.', 'danger');
        return;
    }
    if (!payload.clientId || !payload.redirectUri) {
        showConnectModalAlert('Client ID and Redirect URI are required.', 'warning');
        return;
    }

    try {
        const headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }

        const response = await fetch('/api/oauth-templates/' + encodeURIComponent(templateIdValue) + '/connect', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(payload)
        });

        const result = await parseJson(response);
        if (!response.ok) {
            showConnectModalAlert(result.message || result.error || 'Failed to start OAuth connect.', 'danger');
            return;
        }

        if (result.status === 'connect_required' && result.authorizationUrl) {
            window.location.href = result.authorizationUrl;
            return;
        }

        if (result.status === 'ready') {
            closeConnectModal();
            showAlert('OAuth client is already connected and ready to use.', 'success');
            return;
        }

        showConnectModalAlert(result.message || 'Unexpected OAuth connect response.', 'warning');
    } catch (_error) {
        showConnectModalAlert('Network error while starting OAuth connect.', 'danger');
    }
}

async function loadConnectDefaults() {
    if (connectDefaults) {
        return connectDefaults;
    }
    const response = await fetch('/api/oauth-templates/connect-defaults');
    if (!response.ok) {
        throw new Error('HTTP ' + response.status);
    }
    connectDefaults = await response.json();
    return connectDefaults;
}

function showConnectModalAlert(message, type) {
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;

    connectModalAlert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + tone + '">' + escapeHtml(message) + '</div>';
}

function clearConnectModalAlert() {
    connectModalAlert.innerHTML = '';
}

function showAlert(message, type) {
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;

    alertArea.innerHTML = ''
        + '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">'
        + '  <div>' + escapeHtml(message) + '</div>'
        + '  <button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" id="dismiss-alert">Close</button>'
        + '</div>';

    const dismiss = document.getElementById('dismiss-alert');
    if (dismiss) {
        dismiss.addEventListener('click', function () {
            alertArea.innerHTML = '';
        });
    }
}

function showTemplateModalAlert(message, type) {
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;

    templateModalAlert.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm ' + tone + '">' + escapeHtml(message) + '</div>';
}

function clearTemplateModalAlert() {
    templateModalAlert.innerHTML = '';
}

async function parseJson(response) {
    try {
        return await response.json();
    } catch (_ignored) {
        return {};
    }
}

function escapeHtml(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
