const csrfTokenMeta = document.querySelector('meta[name="_csrf"]');
const csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
const csrfToken = csrfTokenMeta ? csrfTokenMeta.getAttribute('content') : null;
const csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.getAttribute('content') : null;

const duplicateModal = document.getElementById('duplicateProfileModal');
const duplicateModalAlert = document.getElementById('duplicate-modal-alert');
const duplicateClientUuid = document.getElementById('duplicate-client-uuid');
const duplicateClientName = document.getElementById('duplicate-client-name');
const duplicateSourceProfile = document.getElementById('duplicate-source-profile');
const duplicateNewProfile = document.getElementById('duplicate-new-profile');

document.addEventListener('DOMContentLoaded', initOAuthClientsPage);

function initOAuthClientsPage() {
    document.querySelectorAll('[data-action="duplicate-oauth-client"]').forEach(function (button) {
        button.addEventListener('click', function () {
            openDuplicateModal(button);
        });
    });

    const closeButton = document.getElementById('close-duplicate-modal');
    const cancelButton = document.getElementById('cancel-duplicate-btn');
    const confirmButton = document.getElementById('confirm-duplicate-btn');

    if (closeButton) {
        closeButton.addEventListener('click', closeDuplicateModal);
    }
    if (cancelButton) {
        cancelButton.addEventListener('click', closeDuplicateModal);
    }
    if (confirmButton) {
        confirmButton.addEventListener('click', submitDuplicateProfile);
    }

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && duplicateModal && !duplicateModal.classList.contains('hidden')) {
            closeDuplicateModal();
        }
    });
}

function openDuplicateModal(button) {
    if (!duplicateModal) {
        return;
    }

    duplicateClientUuid.value = button.getAttribute('data-client-uuid') || '';
    duplicateClientName.value = button.getAttribute('data-client-name') || '';
    duplicateSourceProfile.value = button.getAttribute('data-profile-name') || '';
    duplicateNewProfile.value = '';
    clearDuplicateModalAlert();

    duplicateModal.classList.remove('hidden');
    duplicateNewProfile.focus();
}

function closeDuplicateModal() {
    if (!duplicateModal) {
        return;
    }
    duplicateModal.classList.add('hidden');
}

async function submitDuplicateProfile() {
    clearDuplicateModalAlert();

    const sourceClientUuid = duplicateClientUuid.value.trim();
    const newProfileName = duplicateNewProfile.value.trim();
    const sourceProfileName = duplicateSourceProfile.value.trim();

    if (!sourceClientUuid) {
        showDuplicateModalAlert('Missing source OAuth client reference.', 'error');
        return;
    }
    if (!newProfileName) {
        showDuplicateModalAlert('Please provide a new profile name.', 'error');
        return;
    }
    if (newProfileName === sourceProfileName) {
        showDuplicateModalAlert('New profile name must be different from the source profile.', 'error');
        return;
    }

    const payload = {
        profileName: newProfileName,
        returnPath: window.location.pathname + window.location.search
    };

    const headers = {
        'Content-Type': 'application/json'
    };
    if (csrfToken && csrfHeader) {
        headers[csrfHeader] = csrfToken;
    }

    try {
        const response = await fetch('/api/oauth-clients/' + encodeURIComponent(sourceClientUuid) + '/duplicate-profile', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(payload)
        });

        const data = await response.json().catch(function () {
            return {};
        });

        if (!response.ok || !data || data.status === 'error') {
            const message = data && data.message ? data.message : 'Failed to duplicate OAuth profile.';
            showDuplicateModalAlert(message, 'error');
            return;
        }

        if (data.status === 'connect_required' && data.authorizationUrl) {
            window.location.assign(data.authorizationUrl);
            return;
        }

        if (data.status === 'ready') {
            window.location.reload();
            return;
        }

        showDuplicateModalAlert('OAuth profile duplicated but no authorization step was returned.', 'error');
    } catch (error) {
        showDuplicateModalAlert('Unexpected error while duplicating OAuth profile.', 'error');
    }
}

function showDuplicateModalAlert(message, type) {
    if (!duplicateModalAlert) {
        return;
    }
    const isError = type === 'error';
    duplicateModalAlert.className = isError
        ? 'rounded-lg border border-rose-700/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-300'
        : 'rounded-lg border border-emerald-700/60 bg-emerald-950/40 px-3 py-2 text-sm text-emerald-300';
    duplicateModalAlert.textContent = message;
}

function clearDuplicateModalAlert() {
    if (!duplicateModalAlert) {
        return;
    }
    duplicateModalAlert.className = '';
    duplicateModalAlert.textContent = '';
}
