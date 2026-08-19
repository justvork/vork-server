/* header-nav.js - lightweight nav badge updates */
/* jshint esversion: 6 */

(function () {
    'use strict';

    var REFRESH_INTERVAL_MS = 10000;
    var refreshHandle = null;
    var initialized = false;

    function updateNeedsAttentionBadge() {
        var link = document.getElementById('needs-attention-link');
        var badge = document.getElementById('needs-attention-badge');
        if (!link || !badge) {
            return;
        }

        fetch('/api/attention/count')
            .then(function (r) {
                if (!r.ok) {
                    throw new Error('HTTP ' + r.status);
                }
                return r.json();
            })
            .then(function (payload) {
                var count = Number(payload && payload.count ? payload.count : 0);
                if (count <= 0) {
                    badge.classList.add('hidden');
                    badge.textContent = '0';
                    link.setAttribute('title', 'Needs Attention');
                    return;
                }

                badge.textContent = count > 99 ? '99+' : String(count);
                badge.classList.remove('hidden');
                link.setAttribute('title', 'Needs Attention (' + count + ')');
            })
            .catch(function () {
                badge.classList.add('hidden');
            });
    }

    function initNeedsAttentionBadge() {
        if (initialized) {
            return;
        }
        initialized = true;

        updateNeedsAttentionBadge();

        if (refreshHandle !== null) {
            clearInterval(refreshHandle);
        }
        refreshHandle = setInterval(function () {
            if (document.visibilityState !== 'visible') {
                return;
            }
            updateNeedsAttentionBadge();
        }, REFRESH_INTERVAL_MS);

        window.addEventListener('beforeunload', function () {
            if (refreshHandle !== null) {
                clearInterval(refreshHandle);
                refreshHandle = null;
            }
        }, { once: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initNeedsAttentionBadge, { once: true });
    } else {
        initNeedsAttentionBadge();
    }
}());
