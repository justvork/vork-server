/* settings-general.js — Vork General Settings page */

(function () {
    const alertArea = document.getElementById('alert-area');
    const relayHostInput = document.getElementById('relay-host');
    const oobTimeoutInput = document.getElementById('oob-timeout');

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function showAlert(msg, type) {
        if (!alertArea) return;
        const tones = {
            success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
            warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
            danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
            info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
        };
        const tone = tones[type] || tones.info;
        alertArea.innerHTML =
            '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
            '<div>' + escapeHtml(msg) + '</div>' +
            '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>' +
            '</div>';

        const closeBtn = alertArea.querySelector('button[aria-label="Dismiss alert"]');
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                alertArea.innerHTML = '';
            });
        }
    }

    function loadSettings() {
        fetch('/api/system/settings')
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (relayHostInput) relayHostInput.value = d.relayHost || '';
                if (oobTimeoutInput) oobTimeoutInput.value = d.defaultOobTimeoutMinutes || 15;
            })
            .catch(function () {});
    }

    function saveSettings() {
        fetch('/api/system/settings')
            .then(function (r) { return r.json(); })
            .then(function (current) {
                const timeoutVal = parseInt(oobTimeoutInput ? oobTimeoutInput.value : '15', 10);
                const payload = {
                    defaultProvider: current.defaultProvider || '',
                    defaultModelId: current.defaultModelId || '',
                    relayHost: relayHostInput ? relayHostInput.value.trim() : '',
                    defaultOobTimeoutMinutes: Number.isFinite(timeoutVal) && timeoutVal > 0 ? timeoutVal : 15
                };

                return fetch('/api/system/settings', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
            })
            .then(function (r) {
                if (r.ok) return r.json();
                return r.json().then(function (e) { return Promise.reject(e); });
            })
            .then(function () {
                showAlert('Settings saved.', 'success');
            })
            .catch(function (e) {
                showAlert('Failed to save: ' + (e.error || e.message || 'unknown error'), 'danger');
            });
    }

    window.saveSettings = saveSettings;
    loadSettings();
})();
