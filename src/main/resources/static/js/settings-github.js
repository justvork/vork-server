/* settings-github.js */

'use strict';

(function () {
    function showAlert(msg, type) {
        const area = document.getElementById('github-settings-alert');
        if (!area) {
            return;
        }
        const tones = {
            success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
            warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
            danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
            info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
        };
        const tone = tones[type] || tones.info;
        area.innerHTML =
            '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">'
            + '<div>' + escapeHtml(msg) + '</div>'
            + '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>'
            + '</div>';
        const closeBtn = area.querySelector('button[aria-label="Dismiss alert"]');
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                area.innerHTML = '';
            });
        }
    }

    function escapeHtml(str) {
        if (!str) {
            return '';
        }
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/\"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (!window.VorkGitHubConnection || typeof window.VorkGitHubConnection.init !== 'function') {
            showAlert('GitHub connection module failed to load.', 'danger');
            return;
        }

        window.VorkGitHubConnection.init({
            connectButtonId: 'github-connect-btn',
            disconnectButtonId: 'github-disconnect-btn',
            statusLabelId: 'github-connection-status',
            alertFn: showAlert
        });
    });
})();
