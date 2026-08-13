/* github-device-flow-ui.js */

'use strict';

window.VorkGitHubConnection = (function () {
    const PENDING_FLOW_KEY = 'vork.github.deviceFlow.pending';
    const JUST_CONNECTED_KEY = 'vork.github.deviceFlow.justConnectedAt';

    let connected = false;
    let githubLogin = '';
    let provider = '';
    let refreshCapable = false;
    let accessTokenExpiresAt = 0;
    let refreshTokenExpiresAt = 0;
    let pollTimer = null;
    let pollInFlight = false;

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

    function init(options) {
        const cfg = normalizeOptions(options);
        bindActions(cfg);
        refreshStatus(cfg);
        resumePendingFlow(cfg);
        return {
            refreshStatus: function () { return refreshStatus(cfg); },
            isConnected: function () { return connected; },
            getLogin: function () { return githubLogin; },
            getProvider: function () { return provider; },
            isRefreshCapable: function () { return refreshCapable; }
        };
    }

    function normalizeOptions(options) {
        return {
            connectButtonId: options && options.connectButtonId ? options.connectButtonId : 'github-connect-btn',
            disconnectButtonId: options && options.disconnectButtonId ? options.disconnectButtonId : 'github-disconnect-btn',
            statusLabelId: options && options.statusLabelId ? options.statusLabelId : 'github-connection-status',
            scope: options && options.scope ? options.scope : 'public_repo read:user',
            alertFn: options && typeof options.alertFn === 'function' ? options.alertFn : function (msg) { window.alert(msg); },
            onStatusChange: options && typeof options.onStatusChange === 'function' ? options.onStatusChange : function () {}
        };
    }

    function bindActions(cfg) {
        const connectBtn = document.getElementById(cfg.connectButtonId);
        if (connectBtn) {
            connectBtn.addEventListener('click', function (event) {
                if (event) {
                    event.preventDefault();
                }
                startDeviceFlow(cfg);
            });
        }

        const disconnectBtn = document.getElementById(cfg.disconnectButtonId);
        if (disconnectBtn) {
            disconnectBtn.addEventListener('click', function (event) {
                if (event) {
                    event.preventDefault();
                }
                disconnect(cfg);
            });
        }
    }

    async function refreshStatus(cfg) {
        try {
            const res = await fetch('/api/github/device-flow/status');
            const body = await safeJson(res);
            if (!res.ok || body.status === 'error') {
                updateConnectionState(cfg, false, '', '');
                return;
            }
            updateConnectionState(
                cfg,
                !!body.connected,
                body.githubLogin || '',
                body.provider || '',
                !!body.refreshCapable,
                Number(body.accessTokenExpiresAt || 0),
                Number(body.refreshTokenExpiresAt || 0)
            );
        } catch (_e) {
            updateConnectionState(cfg, false, '', '', false, 0, 0);
        }
    }

    function updateConnectionState(cfg, nextConnected, nextLogin, nextProvider, nextRefreshCapable, nextAccessTokenExpiresAt, nextRefreshTokenExpiresAt) {
        connected = !!nextConnected;
        githubLogin = nextLogin || '';
        provider = nextProvider || '';
        refreshCapable = !!nextRefreshCapable;
        accessTokenExpiresAt = Number(nextAccessTokenExpiresAt || 0);
        refreshTokenExpiresAt = Number(nextRefreshTokenExpiresAt || 0);

        const statusLabel = document.getElementById(cfg.statusLabelId);
        if (statusLabel) {
            statusLabel.textContent = connected
                ? 'Connected as ' + (githubLogin || 'GitHub user')
                : 'Not connected';
            statusLabel.classList.toggle('text-success', connected);
            statusLabel.classList.toggle('text-muted', !connected);
        }

        const hint = document.getElementById('github-connection-hint');
        if (hint) {
            if (connected) {
                const justConnected = consumeJustConnectedFlag();
                if (justConnected) {
                    hint.textContent = 'GitHub connected.';
                    hint.classList.remove('d-none');
                    hint.classList.remove('alert-info');
                    hint.classList.remove('text-cyan-200');
                    hint.classList.add('alert-success');
                    hint.classList.add('text-emerald-200');
                } else {
                    hint.textContent = '';
                    hint.classList.add('d-none');
                    hint.classList.remove('alert-success');
                    hint.classList.remove('text-emerald-200');
                    hint.classList.remove('alert-info');
                    hint.classList.remove('text-cyan-200');
                }
            } else {
                hint.textContent = 'GitHub not connected. Reconnect to publish artifacts.';
                hint.classList.remove('d-none');
                hint.classList.remove('alert-success');
                hint.classList.remove('text-emerald-200');
                hint.classList.add('alert-info');
                hint.classList.add('text-cyan-200');
            }
        }

        const connectBtn = document.getElementById(cfg.connectButtonId);
        if (connectBtn) {
            connectBtn.title = connected ? 'GitHub is already connected' : 'Connect GitHub';
            setElementVisible(connectBtn, !connected);
        }

        const disconnectBtn = document.getElementById(cfg.disconnectButtonId);
        if (disconnectBtn) {
            disconnectBtn.disabled = !connected;
            setElementVisible(disconnectBtn, connected);
        }

        toggleContributionActions(connected);
        cfg.onStatusChange({
            connected: connected,
            githubLogin: githubLogin,
            provider: provider,
            refreshCapable: refreshCapable,
            accessTokenExpiresAt: accessTokenExpiresAt,
            refreshTokenExpiresAt: refreshTokenExpiresAt
        });
    }

    function setElementVisible(el, visible) {
        if (!el) {
            return;
        }
        if (el.classList.contains('btn')) {
            el.classList.toggle('d-none', !visible);
            return;
        }
        el.classList.toggle('hidden', !visible);
    }

    function formatExpiryRelative(timestampMs) {
        if (!timestampMs || timestampMs <= 0) {
            return 'at an unknown time';
        }
        const deltaMs = timestampMs - Date.now();
        if (deltaMs <= 0) {
            return 'now';
        }
        const minutes = Math.floor(deltaMs / 60000);
        if (minutes < 1) {
            return 'in under 1 minute';
        }
        if (minutes < 60) {
            return 'in ' + minutes + ' minute' + (minutes === 1 ? '' : 's');
        }
        const hours = Math.floor(minutes / 60);
        if (hours < 24) {
            return 'in ' + hours + ' hour' + (hours === 1 ? '' : 's');
        }
        const days = Math.floor(hours / 24);
        return 'in ' + days + ' day' + (days === 1 ? '' : 's');
    }

    function toggleContributionActions(isConnected) {
        const reason = 'Connect GitHub before publishing, recommending versions, or creating snapshots.';
        document.querySelectorAll('.contrib-action').forEach(function (btn) {
            if (!(btn instanceof HTMLButtonElement)) return;
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

    async function startDeviceFlow(cfg) {
        clearPollTimer();

        let response;
        try {
            response = await fetch('/api/github/device-flow/start', {
                method: 'POST',
                headers: buildJsonHeaders(),
                body: JSON.stringify({ scope: cfg.scope })
            });
        } catch (_e) {
            cfg.alertFn('Failed to start GitHub connection.', 'danger');
            return;
        }

        const body = await safeJson(response);
        if (!response.ok || body.status === 'error') {
            cfg.alertFn(resolveHttpErrorMessage(response, body, 'Failed to start GitHub Device Flow.'), 'danger');
            return;
        }

        const flowId = body.flowId;
        const verificationUriComplete = body.verificationUriComplete || body.verificationUri;
        const userCode = body.userCode || '';
        const intervalSeconds = Number(body.intervalSeconds || 5);

        savePendingFlow({
            flowId: flowId,
            intervalSeconds: intervalSeconds,
            userCode: userCode,
            verificationUriComplete: verificationUriComplete,
            returnPath: window.location.pathname + window.location.search,
            state: 'ready'
        });

        window.location.assign('/github/device-flow/authorize');
        return;

    }

    async function pollDeviceFlow(cfg, flowId, intervalSeconds) {
        if (pollInFlight) {
            return;
        }
        pollInFlight = true;
        try {
            const pollUrl = '/api/github/device-flow/' + encodeURIComponent(flowId)
                + '/poll?ts=' + Date.now();
            const res = await fetch(pollUrl, {
                method: 'POST',
                cache: 'no-store',
                headers: buildJsonHeaders(),
                body: JSON.stringify({})
            });
            const body = await safeJson(res);

            if (!res.ok) {
                clearPollTimer();
                cfg.alertFn(resolveHttpErrorMessage(res, body, 'GitHub authorization polling failed.'), 'danger');
                await refreshStatus(cfg);
                return;
            }

            const status = (body.status || '').toLowerCase();
            if (status === 'approved' || body.connected === true) {
                clearPollTimer();
                clearPendingFlow();
                cfg.alertFn('GitHub connected successfully.', 'success');
                await refreshStatus(cfg);
                return;
            }

            if (status === 'pending') {
                await refreshStatus(cfg);
                if (connected) {
                    clearPollTimer();
                    clearPendingFlow();
                    cfg.alertFn('GitHub connected successfully.', 'success');
                    await refreshStatus(cfg);
                    return;
                }

                if (body.intervalSeconds && Number(body.intervalSeconds) !== intervalSeconds) {
                    clearPollTimer();
                    const nextInterval = normalizeIntervalSeconds(body.intervalSeconds, intervalSeconds);
                    pollTimer = window.setInterval(function () {
                        pollDeviceFlow(cfg, flowId, nextInterval);
                    }, nextInterval * 1000);
                }
                return;
            }

            if (status === 'declined' || status === 'expired' || status === 'error') {
                clearPollTimer();
                clearPendingFlow();
                cfg.alertFn(body.message || 'GitHub authorization did not complete.', 'warning');
                await refreshStatus(cfg);
            }
        } catch (_e) {
            clearPollTimer();
            cfg.alertFn('GitHub authorization polling failed. Check network/session and retry.', 'danger');
            await refreshStatus(cfg);
        } finally {
            pollInFlight = false;
        }
    }

    function resumePendingFlow(cfg) {
        const pending = readPendingFlow();
        if (!pending || !pending.flowId) {
            return;
        }
        if (connected) {
            clearPendingFlow();
            return;
        }
        cfg.alertFn('GitHub authorization pending. Checking approval status…', 'info');
        clearPollTimer();
        const startInterval = normalizeIntervalSeconds(pending.intervalSeconds, 5);
        pollTimer = window.setInterval(function () {
            pollDeviceFlow(cfg, pending.flowId, startInterval);
        }, startInterval * 1000);
    }

    async function disconnect(cfg) {
        try {
            const res = await fetch('/api/github/device-flow/status', {
                method: 'DELETE',
                headers: buildCsrfHeaders()
            });
            const body = await safeJson(res);
            if (!res.ok || body.status === 'error') {
                cfg.alertFn(resolveHttpErrorMessage(res, body, 'Failed to disconnect GitHub.'), 'danger');
                return;
            }
            cfg.alertFn('GitHub disconnected.', 'warning');
            clearPollTimer();
            await refreshStatus(cfg);
        } catch (_e) {
            cfg.alertFn('Failed to disconnect GitHub.', 'danger');
        }
    }

    function clearPollTimer() {
        if (pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function savePendingFlow(data) {
        try {
            const payload = Object.assign({
                flowId: '',
                intervalSeconds: 5,
                userCode: '',
                verificationUriComplete: '',
                returnPath: '/',
                state: 'ready',
                createdAt: Date.now()
            }, data || {});
            payload.intervalSeconds = normalizeIntervalSeconds(payload.intervalSeconds, 5);
            sessionStorage.setItem(PENDING_FLOW_KEY, JSON.stringify(payload));
        } catch (_e) {
            // ignore storage failures
        }
    }

    function normalizeIntervalSeconds(value, fallback) {
        const fallbackValue = Number.isFinite(Number(fallback)) ? Number(fallback) : 5;
        const parsed = Number(value);
        if (!Number.isFinite(parsed) || parsed <= 0) {
            return Math.max(2, fallbackValue);
        }
        return Math.max(2, parsed);
    }

    function readPendingFlow() {
        try {
            const raw = sessionStorage.getItem(PENDING_FLOW_KEY);
            if (!raw) {
                return null;
            }
            const payload = JSON.parse(raw);
            if (!payload || !payload.flowId) {
                return null;
            }
            return payload;
        } catch (_e) {
            return null;
        }
    }

    function consumeJustConnectedFlag() {
        try {
            const raw = sessionStorage.getItem(JUST_CONNECTED_KEY);
            if (!raw) {
                return false;
            }
            sessionStorage.removeItem(JUST_CONNECTED_KEY);
            const timestamp = Number(raw);
            return Number.isFinite(timestamp) && (Date.now() - timestamp) < 120000;
        } catch (_e) {
            return false;
        }
    }

    function clearPendingFlow() {
        try {
            sessionStorage.removeItem(PENDING_FLOW_KEY);
        } catch (_e) {
            // ignore storage failures
        }
    }

    async function safeJson(response) {
        try {
            return await response.json();
        } catch (_e) {
            try {
                const text = await response.text();
                if (!text) {
                    return {};
                }
                try {
                    return JSON.parse(text);
                } catch (_ignored) {
                    return { _rawText: text };
                }
            } catch (_ignored) {
                return {};
            }
        }
    }

    function buildCsrfHeaders() {
        const headers = {};
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        return headers;
    }

    function buildJsonHeaders() {
        return Object.assign({ 'Content-Type': 'application/json' }, buildCsrfHeaders());
    }

    function resolveHttpErrorMessage(response, body, fallback) {
        if (body && body.message) {
            return body.message;
        }
        if (response && response.status === 401) {
            return 'Session expired or not authenticated. Sign in again and retry.';
        }
        if (response && response.status === 403) {
            return 'Request blocked (CSRF/permission). Refresh the page and retry.';
        }
        if (body && body._rawText && typeof body._rawText === 'string' && body._rawText.trim()) {
            return fallback + ' HTTP ' + response.status + '.';
        }
        return fallback;
    }

    return {
        init: init
    };
})();
