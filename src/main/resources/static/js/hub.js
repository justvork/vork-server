/* hub.js - Hub search and install page */
/* jshint esversion: 6 */

let hubRepositories = [];
let hubItems = [];
let currentDetailsItemId = '';

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

const alertArea = document.getElementById('hub-alert-area');
const repositorySelect = document.getElementById('hub-repository-select');
const repositoryStatus = document.getElementById('hub-repository-status');
const searchInput = document.getElementById('hub-search-input');
const typeFilter = document.getElementById('hub-type-filter');
const refreshButton = document.getElementById('hub-refresh-btn');
const emptyState = document.getElementById('hub-empty-state');
const resultsGrid = document.getElementById('hub-results-grid');

const detailsModal = document.getElementById('hub-details-modal');
const detailsTitle = document.getElementById('hub-details-title');
const detailsDescription = document.getElementById('hub-details-description');
const detailsType = document.getElementById('hub-details-type');
const detailsRepository = document.getElementById('hub-details-repository');
const detailsArtifactPath = document.getElementById('hub-details-artifact-path');
const detailsInstallPath = document.getElementById('hub-details-install-path');
const detailsLogo = document.getElementById('hub-details-logo');
const detailsDoc = document.getElementById('hub-details-doc');
const detailsInstallButton = document.getElementById('hub-details-install-btn');

function init() {
    if (window.marked && typeof window.marked.use === 'function') {
        window.marked.use({ breaks: true, gfm: true });
    }

    if (repositorySelect) {
        repositorySelect.addEventListener('change', function () {
            updateRepositoryStatus();
            loadCatalog();
        });
    }
    if (searchInput) {
        searchInput.addEventListener('input', debounce(loadCatalog, 250));
    }
    if (typeFilter) {
        typeFilter.addEventListener('change', loadCatalog);
    }
    if (refreshButton) {
        refreshButton.addEventListener('click', loadCatalog);
    }

    document.querySelectorAll('[data-action="close-details"]').forEach(function (element) {
        element.addEventListener('click', closeDetailsModal);
    });

    if (detailsInstallButton) {
        detailsInstallButton.addEventListener('click', function () {
            if (currentDetailsItemId) {
                installArtifact(currentDetailsItemId);
            }
        });
    }

    loadRepositories();
}

document.addEventListener('DOMContentLoaded', init);

async function loadRepositories() {
    if (!repositorySelect || !repositoryStatus) {
        return;
    }
    repositoryStatus.textContent = 'Loading repositories...';
    repositorySelect.innerHTML = '';

    try {
        const response = await fetch('/api/hub/repositories');
        const result = await parseJson(response);
        if (!response.ok || !Array.isArray(result)) {
            throw new Error('HTTP ' + response.status);
        }

        hubRepositories = result;
        result.forEach(function (repo) {
            const option = document.createElement('option');
            option.value = repo.name || '';
            option.textContent = (repo.name || 'Unnamed') + (repo.available ? '' : ' (unavailable)');
            if (repo.name && repo.name.toLowerCase() === 'production') {
                option.selected = true;
            }
            repositorySelect.appendChild(option);
        });

        if (!repositorySelect.value && repositorySelect.options.length > 0) {
            repositorySelect.options[0].selected = true;
        }

        updateRepositoryStatus();
        await loadCatalog();
    } catch (_error) {
        hubRepositories = [];
        showAlert('Failed to load repository sources.', 'danger');
        repositoryStatus.className = 'rounded-md border border-rose-700/60 bg-rose-950/40 px-2 py-1 text-xs text-rose-300';
        repositoryStatus.textContent = 'Repository list unavailable.';
    }
}

function selectedRepositoryName() {
    return (repositorySelect && repositorySelect.value ? repositorySelect.value : '').trim();
}

function selectedRepository() {
    const selected = selectedRepositoryName().toLowerCase();
    if (!selected) {
        return null;
    }
    return hubRepositories.find(function (repo) {
        return String(repo.name || '').toLowerCase() === selected;
    }) || null;
}

function updateRepositoryStatus() {
    if (!repositoryStatus) {
        return;
    }
    const repo = selectedRepository();
    if (!repo) {
        repositoryStatus.className = 'rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-400';
        repositoryStatus.textContent = 'Select a repository.';
        return;
    }

    if (repo.available) {
        repositoryStatus.className = 'rounded-md border border-emerald-700/60 bg-emerald-950/40 px-2 py-1 text-xs text-emerald-300';
        repositoryStatus.textContent = 'Available: ' + (repo.baseUrl || '');
    } else {
        repositoryStatus.className = 'rounded-md border border-amber-700/60 bg-amber-950/40 px-2 py-1 text-xs text-amber-300';
        repositoryStatus.textContent = (repo.message || 'Unavailable') + (repo.baseUrl ? ' [' + repo.baseUrl + ']' : '');
    }
}

async function loadCatalog() {
    const repositoryName = selectedRepositoryName();
    if (!repositoryName) {
        hubItems = [];
        renderCatalog();
        return;
    }

    const params = new URLSearchParams();
    params.set('repositoryName', repositoryName);

    const type = (typeFilter && typeFilter.value ? typeFilter.value : '').trim();
    if (type) {
        params.set('type', type);
    }

    const query = (searchInput && searchInput.value ? searchInput.value : '').trim();
    if (query) {
        params.set('q', query);
    }

    try {
        const response = await fetch('/api/hub/catalog?' + params.toString());
        const result = await parseJson(response);
        if (!response.ok || !Array.isArray(result)) {
            throw new Error(result.message || ('HTTP ' + response.status));
        }
        hubItems = result;
        renderCatalog();
    } catch (error) {
        hubItems = [];
        renderCatalog();
        showAlert('Failed to load Hub catalog: ' + (error.message || 'unknown error'), 'danger');
    }
}

function renderCatalog() {
    if (!resultsGrid || !emptyState) {
        return;
    }

    resultsGrid.innerHTML = '';

    if (!hubItems || hubItems.length === 0) {
        emptyState.classList.remove('hidden');
        resultsGrid.classList.add('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    resultsGrid.classList.remove('hidden');

    hubItems.forEach(function (item) {
        const card = document.createElement('article');
        card.className = 'hub-card';

        const typeLabel = formatType(item.type);
        const logoUrl = item.logoPath ? hubArtifactUrl(item.repositoryName, item.logoPath) : '';

        card.innerHTML = ''
            + '<div class="hub-card-header">'
            + (logoUrl ? '<img class="hub-logo-lg" src="' + escapeHtml(logoUrl) + '" alt="logo">' : '<span class="hub-logo-lg"></span>')
            + '  <div>'
            + '    <h3 class="hub-card-name">' + escapeHtml(item.name || '') + '</h3>'
            + '    <p class="hub-card-type">' + escapeHtml(typeLabel) + '</p>'
            + '  </div>'
            + '</div>'
            + '<p class="hub-card-description">' + escapeHtml(item.description || defaultDescription(item)) + '</p>'
            + '<div class="hub-card-actions">'
            + '  <button class="rounded-md border border-emerald-500/40 px-2 py-1.5 text-xs font-medium text-emerald-300 transition-colors hover:bg-emerald-500/15" data-action="install" data-id="' + escapeHtml(item.id) + '">'
            + '    <i class="fa-solid fa-download mr-1"></i>Install'
            + '  </button>'
            + '  <button class="rounded-md border border-zinc-600 px-2 py-1.5 text-xs font-medium text-zinc-200 transition-colors hover:bg-zinc-800" data-action="details" data-id="' + escapeHtml(item.id) + '">'
            + '    <i class="fa-solid fa-circle-info mr-1"></i>More details'
            + '  </button>'
            + '</div>';

        resultsGrid.appendChild(card);
    });

    resultsGrid.querySelectorAll('button[data-action="install"]').forEach(function (button) {
        button.addEventListener('click', function () {
            installArtifact(button.getAttribute('data-id'));
        });
    });

    resultsGrid.querySelectorAll('button[data-action="details"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openDetails(button.getAttribute('data-id'));
        });
    });
}

function hubArtifactUrl(repositoryName, path) {
    const params = new URLSearchParams();
    params.set('repositoryName', repositoryName || selectedRepositoryName());
    params.set('path', path);
    return '/api/hub/artifact?' + params.toString();
}

async function openDetails(itemId) {
    const item = findItem(itemId);
    if (!item || !detailsModal) {
        return;
    }

    currentDetailsItemId = item.id;
    detailsTitle.textContent = item.name || 'Artifact details';
    detailsDescription.textContent = item.description || defaultDescription(item);
    detailsType.textContent = formatType(item.type);
    detailsRepository.textContent = item.repositoryName || selectedRepositoryName();
    detailsArtifactPath.textContent = item.artifactPath || 'N/A';
    detailsInstallPath.textContent = item.installPath || 'N/A';

    if (item.logoPath) {
        detailsLogo.src = hubArtifactUrl(item.repositoryName, item.logoPath);
        detailsLogo.classList.remove('hidden');
    } else {
        detailsLogo.classList.add('hidden');
        detailsLogo.removeAttribute('src');
    }

    detailsDoc.innerHTML = '<p>Loading documentation...</p>';
    detailsModal.classList.remove('hidden');

    if (!item.docPath) {
        detailsDoc.innerHTML = '<p>No documentation found for this artifact.</p>';
        return;
    }

    try {
        const response = await fetch(hubArtifactUrl(item.repositoryName, item.docPath));
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        const text = await response.text();
        renderMarkdown(detailsDoc, text || 'No documentation content.');
    } catch (_error) {
        detailsDoc.innerHTML = '<p>Unable to load documentation.</p>';
    }
}

function closeDetailsModal() {
    if (detailsModal) {
        detailsModal.classList.add('hidden');
    }
    currentDetailsItemId = '';
}

async function installArtifact(itemId) {
    const item = findItem(itemId);
    if (!item) {
        showAlert('Artifact not found in current results.', 'warning');
        return;
    }

    if (!item.installPath) {
        showAlert('Install package path is missing for this artifact.', 'warning');
        return;
    }

    const headers = { 'Content-Type': 'application/json' };
    if (csrfToken) {
        headers[csrfHeader] = csrfToken;
    }

    try {
        const prepareResponse = await fetch('/api/hub/install/prepare', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({
                repositoryName: item.repositoryName || selectedRepositoryName(),
                type: item.type,
                installPath: item.installPath
            })
        });
        const prepared = await parseJson(prepareResponse);
        if (!prepareResponse.ok) {
            throw new Error(prepared.message || ('HTTP ' + prepareResponse.status));
        }

        const installResult = await submitInstall(prepared, headers);
        const summary = summarizeInstallResult(installResult);
        showAlert('Installed ' + item.name + '. ' + summary, 'success');
    } catch (error) {
        showAlert('Install failed for ' + item.name + ': ' + (error.message || 'unknown error'), 'danger');
    }
}

async function submitInstall(prepared, headers) {
    if (!prepared || !prepared.installEndpoint || !prepared.payloadBase64) {
        throw new Error('Install package payload is incomplete.');
    }

    if (prepared.installMode === 'multipart-zip') {
        const bytes = decodeBase64(prepared.payloadBase64);
        const blob = new Blob([bytes], { type: prepared.mediaType || 'application/zip' });
        const fileName = prepared.fileName || 'artifact.zip';
        const formData = new FormData();
        formData.append('file', blob, fileName);

        const requestHeaders = {};
        if (csrfToken) {
            requestHeaders[csrfHeader] = csrfToken;
        }

        const response = await fetch(prepared.installEndpoint, {
            method: 'POST',
            headers: requestHeaders,
            body: formData
        });
        const result = await parseJson(response);
        if (!response.ok || (result && result.status === 'error')) {
            throw new Error((result && (result.message || result.error)) || ('HTTP ' + response.status));
        }
        return result;
    }

    const rawJson = new TextDecoder().decode(decodeBase64(prepared.payloadBase64));
    const parsedJson = JSON.parse(rawJson);
    const response = await fetch(prepared.installEndpoint, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(parsedJson)
    });
    const result = await parseJson(response);
    if (!response.ok || (result && result.status === 'error')) {
        throw new Error((result && (result.message || result.error)) || ('HTTP ' + response.status));
    }
    return result;
}

function summarizeInstallResult(result) {
    if (!result || typeof result !== 'object') {
        return 'Install API returned no detail.';
    }
    if (result.status && result.status !== 'ok') {
        return 'Status: ' + result.status + '.';
    }
    if (result.created !== undefined || result.updated !== undefined) {
        return 'Created: ' + (result.created || 0) + ', Updated: ' + (result.updated || 0) + '.';
    }
    if (result.agentUuid) {
        return 'Agent: ' + result.agentUuid + '.';
    }
    if (result.jobId) {
        return 'Job: ' + result.jobId + '.';
    }
    if (result.surfaceUuid) {
        return 'Surface: ' + result.surfaceUuid + '.';
    }
    if (result.groupUuid) {
        return 'Group: ' + result.groupUuid + '.';
    }
    return 'Install completed.';
}

function findItem(itemId) {
    return hubItems.find(function (entry) {
        return entry.id === itemId;
    }) || null;
}

function defaultDescription(item) {
    return 'Installable ' + formatType(item.type).toLowerCase() + ' artifact from the selected repository.';
}

function formatType(type) {
    switch ((type || '').toLowerCase()) {
        case 'agent': return 'Agent';
        case 'job': return 'Job';
        case 'surface': return 'Surface';
        case 'skill-group': return 'Skill Group';
        case 'reflection-group': return 'Reflection Group';
        case 'oauth-template': return 'OAuth Template';
        default: return type || 'Unknown';
    }
}

function decodeBase64(value) {
    const binary = atob(value || '');
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

function showAlert(message, type) {
    if (!alertArea) {
        return;
    }

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
        + '  <button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" id="dismiss-hub-alert">Close</button>'
        + '</div>';

    const dismiss = document.getElementById('dismiss-hub-alert');
    if (dismiss) {
        dismiss.addEventListener('click', function () {
            alertArea.innerHTML = '';
        });
    }
}

function debounce(fn, waitMs) {
    let timeoutId;
    return function () {
        const args = arguments;
        clearTimeout(timeoutId);
        timeoutId = window.setTimeout(function () {
            fn.apply(null, args);
        }, waitMs);
    };
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

async function parseJson(response) {
    try {
        return await response.json();
    } catch (_ignored) {
        return {};
    }
}

function renderMarkdown(targetElement, markdownText) {
    if (!targetElement) {
        return;
    }
    if (window.marked && typeof window.marked.parse === 'function') {
        const renderedHtml = window.marked.parse(markdownText || '');
        targetElement.innerHTML = sanitizeHtml(renderedHtml);
        return;
    }
    targetElement.textContent = markdownText || '';
}

function sanitizeHtml(rawHtml) {
    const template = document.createElement('template');
    template.innerHTML = rawHtml || '';

    const blockedTags = new Set([
        'script', 'style', 'iframe', 'object', 'embed', 'form',
        'input', 'button', 'select', 'textarea', 'meta', 'link'
    ]);

    const allowedTags = new Set([
        'p', 'br', 'strong', 'em', 'b', 'i', 'u', 's',
        'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
        'ul', 'ol', 'li', 'blockquote', 'hr',
        'code', 'pre', 'span', 'div',
        'table', 'thead', 'tbody', 'tr', 'th', 'td',
        'a', 'img'
    ]);

    const walk = function (node) {
        if (!node || !node.childNodes) {
            return;
        }

        const children = Array.from(node.childNodes);
        children.forEach(function (child) {
            if (child.nodeType === Node.ELEMENT_NODE) {
                const tag = child.tagName.toLowerCase();

                if (blockedTags.has(tag)) {
                    child.remove();
                    return;
                }

                if (!allowedTags.has(tag)) {
                    const textNode = document.createTextNode(child.textContent || '');
                    child.replaceWith(textNode);
                    return;
                }

                const attributes = Array.from(child.attributes || []);
                attributes.forEach(function (attribute) {
                    const name = attribute.name.toLowerCase();
                    const value = attribute.value || '';

                    if (name.startsWith('on') || name === 'style' || name === 'srcset') {
                        child.removeAttribute(attribute.name);
                        return;
                    }

                    if (name === 'href' || name === 'src') {
                        const lowerValue = value.trim().toLowerCase();
                        const safe = lowerValue.startsWith('http://')
                            || lowerValue.startsWith('https://')
                            || lowerValue.startsWith('/')
                            || lowerValue.startsWith('./')
                            || lowerValue.startsWith('../')
                            || lowerValue.startsWith('#');
                        if (!safe) {
                            child.removeAttribute(attribute.name);
                        }
                    }

                    if (tag !== 'a' && name === 'target') {
                        child.removeAttribute(attribute.name);
                    }

                    if (tag !== 'a' && name === 'rel') {
                        child.removeAttribute(attribute.name);
                    }
                });

                if (tag === 'a') {
                    child.setAttribute('rel', 'noopener noreferrer nofollow');
                    if (!child.getAttribute('target')) {
                        child.setAttribute('target', '_blank');
                    }
                }
            }

            walk(child);
        });
    };

    walk(template.content);
    return template.innerHTML;
}
