/* github-device-flow-authorize.js */

'use strict';

(function () {
    const PENDING_FLOW_KEY = 'vork.github.deviceFlow.pending';
    const JUST_CONNECTED_KEY = 'vork.github.deviceFlow.justConnectedAt';
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

    let pollTimer = null;
    let githubPopup = null;
    let pollInFlight = false;

    const codeEl = document.getElementById('device-code');
    const statusEl = document.getElementById('connect-status');
    const copyBtn = document.getElementById('copy-device-code-btn');
    const continueBtn = document.getElementById('continue-github-btn');
    const checkBtn = document.getElementById('check-completion-btn');
    const returnLink = document.getElementById('return-link');

    const pending = readPending();

    if (!pending || !pending.flowId || !pending.verificationUriComplete) {
        setStatus('No active GitHub device flow was found. Start again from the page where you clicked Connect GitHub.', 'error');
        codeEl.textContent = 'Unavailable';
        continueBtn.disabled = true;
        checkBtn.disabled = true;
        return;
    }

    codeEl.textContent = pending.userCode || 'See GitHub prompt';
    returnLink.href = pending.returnPath || '/';

    copyBtn.addEventListener('click', async function () {
        if (!pending.userCode) {
            setStatus('No device code to copy. Continue to GitHub to view the code there.', 'info');
            return;
        }
        try {
            await navigator.clipboard.writeText(pending.userCode);
            setStatus('Device code copied to clipboard.', 'success');
        } catch (_e) {
            setStatus('Could not copy automatically. Please copy the code manually.', 'error');
        }
    });

    continueBtn.addEventListener('click', function () {
        pending.state = 'awaiting_completion';
        savePending(pending);
        const popup = openGithubPopup(pending.verificationUriComplete);
        if (!popup) {
            setStatus('Popup was blocked. Redirecting in this tab instead…', 'info');
            window.location.assign(pending.verificationUriComplete);
            return;
        }
        githubPopup = popup;
        setStatus('GitHub opened in a popup. Enter the code there; this page will complete the connection automatically.', 'info');
        beginPolling();
    });

    checkBtn.addEventListener('click', function () {
        beginPolling();
    });

    if (pending.state === 'awaiting_completion') {
        beginPolling();
    } else {
        setStatus('Copy your code, continue to GitHub, then return and click I Entered The Code.', 'info');
    }

    function beginPolling() {
        clearPolling();
        setStatus('Checking GitHub authorization status…', 'info');
        checkBtn.disabled = true;

        pollOnce();
        pollTimer = window.setInterval(function () {
            pollOnce();
        }, normalizeIntervalSeconds(pending.intervalSeconds, 5) * 1000);
    }

    async function pollOnce() {
        if (pollInFlight) {
            return;
        }
        pollInFlight = true;
        try {
            const pollUrl = '/api/github/device-flow/' + encodeURIComponent(pending.flowId)
                + '/poll?ts=' + Date.now();
            const headers = {
                'Content-Type': 'application/json',
                'Cache-Control': 'no-cache, no-store, max-age=0',
                'Pragma': 'no-cache'
            };
            if (csrfToken) {
                headers[csrfHeader] = csrfToken;
            }
            const res = await fetch(pollUrl, {
                method: 'POST',
                cache: 'no-store',
                headers: headers,
                body: JSON.stringify({})
            });
            const body = await safeJson(res);
            if (!res.ok) {
                clearPolling();
                checkBtn.disabled = false;
                setStatus(resolveHttpErrorMessage(res, body, 'Polling failed.'), 'error');
                return;
            }

            const status = (body.status || '').toLowerCase();
            if (status === 'approved' || body.connected === true) {
                finishAsConnected();
                return;
            }

            if (status === 'pending') {
                const currentStatus = await fetchConnectionStatus();
                if (currentStatus.connected === true) {
                    finishAsConnected();
                    return;
                }

                if (githubPopup && githubPopup.closed) {
                    githubPopup = null;
                    setStatus('GitHub popup was closed. Click Continue To GitHub to reopen it, then enter the code.', 'info');
                }
                if (body.intervalSeconds) {
                    const next = normalizeIntervalSeconds(body.intervalSeconds, pending.intervalSeconds);
                    if (next !== normalizeIntervalSeconds(pending.intervalSeconds, 5)) {
                        pending.intervalSeconds = next;
                        savePending(pending);
                        beginPolling();
                    }
                }
                setStatus('Waiting for GitHub approval…', 'info');
                return;
            }

            if (status === 'declined' || status === 'expired' || status === 'error') {
                clearPolling();
                clearPending();
                checkBtn.disabled = false;
                setStatus(body.message || 'GitHub authorization did not complete.', 'error');
                return;
            }
        } catch (_e) {
            clearPolling();
            checkBtn.disabled = false;
            setStatus('Polling failed due to a network/session issue. Try again.', 'error');
        } finally {
            pollInFlight = false;
        }
    }

    function finishAsConnected() {
        clearPolling();
        clearPending();
        markJustConnected();
        closeGithubPopup();
        setStatus('GitHub connected. Returning to your previous page…', 'success');
        const target = pending.returnPath || '/';
        window.setTimeout(function () {
            window.location.assign(target);
        }, 600);
    }

    function markJustConnected() {
        try {
            sessionStorage.setItem(JUST_CONNECTED_KEY, String(Date.now()));
        } catch (_e) {
            // ignore storage failures
        }
    }

    async function fetchConnectionStatus() {
        try {
            const res = await fetch('/api/github/device-flow/status?ts=' + Date.now(), {
                method: 'GET',
                cache: 'no-store',
                headers: {
                    'Cache-Control': 'no-cache, no-store, max-age=0',
                    'Pragma': 'no-cache'
                }
            });
            if (!res.ok) {
                return { connected: false };
            }
            const body = await safeJson(res);
            return { connected: body.connected === true };
        } catch (_e) {
            return { connected: false };
        }
    }

    function setStatus(message, tone) {
        statusEl.textContent = message;
        statusEl.classList.remove('success', 'error', 'info');
        if (tone) {
            statusEl.classList.add(tone);
        }
    }

    function clearPolling() {
        if (pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function closeGithubPopup() {
        if (githubPopup && !githubPopup.closed) {
            try {
                githubPopup.close();
            } catch (_e) {
                // ignore popup close failures
            }
        }
        githubPopup = null;
    }

    function openGithubPopup(url) {
        const popupWidth = 640;
        const popupHeight = 780;
        const left = Math.max(0, Math.round((window.screen.width - popupWidth) / 2));
        const top = Math.max(0, Math.round((window.screen.height - popupHeight) / 2));
        const features = [
            'popup=yes',
            'toolbar=no',
            'location=yes',
            'status=no',
            'menubar=no',
            'scrollbars=yes',
            'resizable=yes',
            'width=' + popupWidth,
            'height=' + popupHeight,
            'left=' + left,
            'top=' + top
        ].join(',');

        const popup = window.open(url, 'vork-github-device-flow', features);
        if (popup) {
            try {
                popup.focus();
            } catch (_e) {
                // focus can fail depending on browser policy
            }
        }
        return popup;
    }

    function readPending() {
        try {
            const raw = sessionStorage.getItem(PENDING_FLOW_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (_e) {
            return null;
        }
    }

    function savePending(payload) {
        try {
            if (payload) {
                payload.intervalSeconds = normalizeIntervalSeconds(payload.intervalSeconds, 5);
            }
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

    function clearPending() {
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
            return {};
        }
    }

    function resolveHttpErrorMessage(response, body, fallback) {
        if (body && body.message) {
            return body.message;
        }
        if (response && response.status === 401) {
            return 'Session expired or not authenticated. Sign in again and retry.';
        }
        if (response && response.status === 403) {
            return 'Request blocked by permissions or CSRF policy. Refresh and retry.';
        }
        return fallback + ' HTTP ' + (response ? response.status : 'unknown') + '.';
    }
})();
