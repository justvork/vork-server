/**
 * Secrets Settings Page - Vanilla JavaScript
 *
 * Handles CRUD operations for user secrets with client-side rendering.
 * All secrets are fetched from REST API and displayed in a table.
 * Values are always redacted as "••••" for security.
 */

// ── Global State ──────────────────────────────────────────────────────────

let currentPage = 0;
const PAGE_SIZE = 20;
let editingKey = null;

// CSRF token handling
const CSRF_TOKEN = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
const CSRF_HEADER = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN'; // null for new, or the key being edited

// ── DOM Elements ──────────────────────────────────────────────────────────

const newSecretBtn = document.getElementById('newSecretBtn');
const secretModal = document.getElementById('secretModal');
const closeModalBtn = document.getElementById('closeModalBtn');
const cancelBtn = document.getElementById('cancelBtn');
const saveBtn = document.getElementById('saveBtn');
const modalTitle = document.getElementById('modalTitle');
const modalKey = document.getElementById('modalKey');
const modalValue = document.getElementById('modalValue');
const secretsContainer = document.getElementById('secretsContainer');
const secretsBody = document.getElementById('secretsBody');
const emptyState = document.getElementById('emptyState');
const statusMessage = document.getElementById('statusMessage');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const paginationContainer = document.getElementById('paginationContainer');
const pageStart = document.getElementById('pageStart');
const pageEnd = document.getElementById('pageEnd');
const totalCount = document.getElementById('totalCount');

// ── Event Listeners ───────────────────────────────────────────────────────

newSecretBtn.addEventListener('click', openNewSecretModal);
closeModalBtn.addEventListener('click', closeModal);
cancelBtn.addEventListener('click', closeModal);
saveBtn.addEventListener('click', saveSecret);
prevBtn.addEventListener('click', previousPage);
nextBtn.addEventListener('click', nextPage);

// Close modal on ESC key
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !secretModal.classList.contains('hidden')) {
        closeModal();
    }
});

// ── Page Lifecycle ────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    loadSecrets();
});

// ── Core Functions ───────────────────────────────────────────────────────────

/**
 * Load secrets from REST API and render the table.
 */
function loadSecrets() {
    showLoading('Loading secrets...');

    fetch(`/api/secrets?page=${currentPage}&pageSize=${PAGE_SIZE}`)
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            renderSecrets(data);
            clearStatus();
        })
        .catch(err => {
            console.error('Error loading secrets:', err);
            showError(`Failed to load secrets: ${err.message}`);
            emptyState.classList.remove('hidden');
            secretsContainer.classList.add('hidden');
        });
}

/**
 * Render secrets data into the table.
 */
function renderSecrets(data) {
    const { secrets, total, page, pageSize } = data;

    // Update pagination state
    const start = page * pageSize + 1;
    const end = Math.min((page + 1) * pageSize, total);

    pageStart.textContent = total === 0 ? 0 : start;
    pageEnd.textContent = end;
    totalCount.textContent = total;

    // Update pagination buttons
    prevBtn.disabled = page === 0;
    nextBtn.disabled = end >= total;

    if (total === 0) {
        emptyState.classList.remove('hidden');
        secretsContainer.classList.add('hidden');
        paginationContainer.classList.add('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    secretsContainer.classList.remove('hidden');
    paginationContainer.classList.remove('hidden');

    // Clear and populate table
    secretsBody.innerHTML = '';
    secrets.forEach(secret => {
        const row = document.createElement('tr');
        row.classList.add('hover:bg-zinc-800/50', 'transition-colors');
        row.innerHTML = `
            <td class="px-4 py-3 secrets-key">${escapeHtml(secret.key)}</td>
            <td class="px-4 py-3 secrets-value-redacted">••••</td>
            <td class="px-4 py-3 text-zinc-400">${formatDate(secret.createdAt)}</td>
            <td class="px-4 py-3 text-zinc-400">${formatDate(secret.updatedAt)}</td>
            <td class="px-4 py-3">
                <div class="flex gap-1">
                    <button class="action-button edit" data-key="${escapeHtml(secret.key)}" data-action="edit">
                        <i class="fa-solid fa-pen-to-square"></i>
                    </button>
                    <button class="action-button delete" data-key="${escapeHtml(secret.key)}" data-action="delete">
                        <i class="fa-solid fa-trash"></i>
                    </button>
                </div>
            </td>
        `;
        secretsBody.appendChild(row);
    });

    // Attach event listeners using delegation
    attachTableEventListeners();
}

/**
 * Attach event listeners to action buttons using event delegation.
 */
function attachTableEventListeners() {
    secretsBody.addEventListener('click', (e) => {
        const button = e.target.closest('button[data-action]');
        if (!button) return;

        const key = button.dataset.key;
        const action = button.dataset.action;

        if (action === 'edit') {
            openEditSecretModal(key);
        } else if (action === 'delete') {
            deleteSecret(key);
        }
    });
}

/**
 * Open the modal for creating a new secret.
 */
function openNewSecretModal() {
    editingKey = null;
    modalTitle.textContent = 'New Secret';
    modalKey.value = '';
    modalKey.disabled = false;
    modalValue.value = '';
    secretModal.classList.remove('hidden');
    modalKey.focus();
}

/**
 * Open the modal for editing an existing secret.
 */
function openEditSecretModal(key) {
    editingKey = key;
    modalTitle.textContent = `Edit Secret: ${key}`;
    modalKey.value = key;
    modalKey.disabled = true;
    modalValue.value = '';
    secretModal.classList.remove('hidden');
    modalValue.focus();
}

/**
 * Close the modal.
 */
function closeModal() {
    secretModal.classList.add('hidden');
    editingKey = null;
    modalKey.value = '';
    modalValue.value = '';
}

/**
 * Save secret via POST or PUT depending on mode.
 */
function saveSecret() {
    const key = modalKey.value.trim();
    const value = modalValue.value.trim();

    if (!key) {
        showError('Key cannot be empty');
        return;
    }

    if (!value) {
        showError('Value cannot be empty');
        return;
    }

    showLoading('Saving secret...');
    
    // Capture editingKey BEFORE closing modal
    const isEditing = editingKey !== null;
    const editKey = editingKey;
    
    closeModal();

    const url = isEditing ? `/api/secrets/${encodeURIComponent(editKey)}` : '/api/secrets';
    const method = isEditing ? 'PUT' : 'POST';
    const body = isEditing ? { value } : { key, value };

    const headers = { 'Content-Type': 'application/json' };
    if (CSRF_TOKEN) {
        headers[CSRF_HEADER] = CSRF_TOKEN;
    }

    fetch(url, {
        method,
        headers,
        body: JSON.stringify(body)
    })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            if (data.status === 'ok') {
                showSuccess(isEditing ? `Secret "${key}" updated.` : `Secret "${key}" created.`);
                currentPage = 0; // Reset to first page
                loadSecrets();
            } else {
                showError(data.message || 'Failed to save secret');
            }
        })
        .catch(err => {
            console.error('Error saving secret:', err);
            showError(`Failed to save secret: ${err.message}`);
        });
}

/**
 * Delete a secret with confirmation.
 */
function deleteSecret(key) {
    if (!confirm(`Are you sure you want to delete the secret "${key}"? This action cannot be undone.`)) {
        return;
    }

    showLoading('Deleting secret...');

    const headers = {};
    if (CSRF_TOKEN) {
        headers[CSRF_HEADER] = CSRF_TOKEN;
    }

    fetch(`/api/secrets/${encodeURIComponent(key)}`, {
        method: 'DELETE',
        headers
    })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            if (data.status === 'ok') {
                showSuccess(`Secret "${key}" deleted.`);
                loadSecrets();
            } else {
                showError(data.message || 'Failed to delete secret');
            }
        })
        .catch(err => {
            console.error('Error deleting secret:', err);
            showError(`Failed to delete secret: ${err.message}`);
        });
}

// ── Pagination ────────────────────────────────────────────────────────────

/**
 * Go to the previous page.
 */
function previousPage() {
    if (currentPage > 0) {
        currentPage--;
        loadSecrets();
    }
}

/**
 * Go to the next page.
 */
function nextPage() {
    currentPage++;
    loadSecrets();
}

// ── Status Messages ───────────────────────────────────────────────────────

/**
 * Show a loading status message.
 */
function showLoading(message) {
    statusMessage.innerHTML = `<div class="status-card" style="color:#64748b"><i class="fa-solid fa-spinner fa-spin mr-2"></i>${escapeHtml(message)}</div>`;
}

/**
 * Show a success status message.
 */
function showSuccess(message) {
    statusMessage.innerHTML = `<div class="status-card success"><i class="fa-solid fa-check-circle mr-2"></i>${escapeHtml(message)}</div>`;
    setTimeout(clearStatus, 4000);
}

/**
 * Show an error status message.
 */
function showError(message) {
    statusMessage.innerHTML = `<div class="status-card error"><i class="fa-solid fa-exclamation-circle mr-2"></i>${escapeHtml(message)}</div>`;
}

/**
 * Clear the status message.
 */
function clearStatus() {
    statusMessage.innerHTML = '';
}

// ── Utility Functions ─────────────────────────────────────────────────────

/**
 * Escape HTML special characters to prevent XSS.
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Format a date string for display.
 */
function formatDate(dateStr) {
    try {
        const date = new Date(dateStr);
        if (isNaN(date)) return 'Unknown';
        return date.toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    } catch (e) {
        return 'Unknown';
    }
}
