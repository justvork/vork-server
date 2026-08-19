(function () {
    'use strict';

    var csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

    var loadingEl = document.getElementById('attention-loading');
    var emptyEl = document.getElementById('attention-empty');
    var tableWrap = document.getElementById('attention-table-wrap');
    var tbody = document.getElementById('attention-tbody');
    var refreshTimer = null;
    var remindAlertUuid = null;

    var remindModal = document.getElementById('remind-modal');
    var remindModalClose = document.getElementById('remind-modal-close');
    var remindModalCancel = document.getElementById('remind-modal-cancel');
    var remindModalSubmit = document.getElementById('remind-modal-submit');
    var remindCustomDatetime = document.getElementById('remind-custom-datetime');
    var remindModalError = document.getElementById('remind-modal-error');

    function fetchAlerts() {
        return fetch('/api/attention/alerts').then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        });
    }

    function formatDate(epochMs) {
        if (!epochMs) return '';
        return new Date(epochMs).toLocaleString();
    }

    function renderRows(alerts) {
        tbody.innerHTML = '';
        alerts.forEach(function (alert) {
            var tr = document.createElement('tr');
            tr.className = 'border-b border-zinc-800/80 last:border-0';

            tr.innerHTML = ''
                + '<td class="px-3 py-2 font-semibold text-zinc-100">' + escapeHtml(alert.alertName || '-') + '</td>'
                + '<td class="px-3 py-2 text-zinc-300">' + escapeHtml(alert.description || '') + '</td>'
                + '<td class="px-3 py-2 text-xs text-zinc-400">' + escapeHtml(alert.resolutionPolicy || '-') + '</td>'
                + '<td class="px-3 py-2 text-xs text-zinc-400">' + escapeHtml(formatDate(alert.attentionAt)) + '</td>';

            var tdActions = document.createElement('td');
            tdActions.className = 'px-3 py-2 text-right';
            var wrap = document.createElement('div');
            wrap.className = 'inline-flex gap-2';

            if (alert.actionUrl) {
                var openBtn = document.createElement('button');
                openBtn.type = 'button';
                openBtn.className = 'rounded-lg bg-[#fdaa02] px-2.5 py-1.5 text-xs font-semibold text-black transition-colors hover:bg-[#e89a02]';
                openBtn.textContent = 'Open';
                openBtn.addEventListener('click', function () {
                    window.location.href = alert.actionUrl;
                });
                wrap.appendChild(openBtn);
            }

            var remindBtn = document.createElement('button');
            remindBtn.type = 'button';
            remindBtn.className = 'rounded-lg border border-zinc-600 px-2.5 py-1.5 text-xs font-medium text-zinc-200 transition-colors hover:bg-zinc-800';
            remindBtn.textContent = 'Remind Me';
            remindBtn.addEventListener('click', function () {
                openRemindModal(alert.uuid);
            });
            wrap.appendChild(remindBtn);

            if (alert.resolutionPolicy === 'DISMISSABLE') {
                var dismissBtn = document.createElement('button');
                dismissBtn.type = 'button';
                dismissBtn.className = 'rounded-lg border border-rose-500/40 px-2.5 py-1.5 text-xs font-medium text-rose-300 transition-colors hover:bg-rose-500/15';
                dismissBtn.textContent = 'Dismiss';
                dismissBtn.addEventListener('click', function () {
                    dismiss(alert.uuid);
                });
                wrap.appendChild(dismissBtn);
            }

            tdActions.appendChild(wrap);
            tr.appendChild(tdActions);
            tbody.appendChild(tr);
        });
    }

    function load() {
        fetchAlerts()
            .then(function (alerts) {
                loadingEl.classList.add('hidden');
                if (!Array.isArray(alerts) || alerts.length === 0) {
                    tableWrap.classList.add('hidden');
                    emptyEl.classList.remove('hidden');
                    return;
                }
                emptyEl.classList.add('hidden');
                renderRows(alerts);
                tableWrap.classList.remove('hidden');
            })
            .catch(function (err) {
                loadingEl.classList.add('hidden');
                tableWrap.classList.remove('hidden');
                tbody.innerHTML = '<tr><td colspan="5" class="px-3 py-2 text-rose-300">Failed to load alerts: ' + escapeHtml(String(err)) + '</td></tr>';
            });
    }

    function dismiss(alertUuid) {
        var headers = {};
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }

        fetch('/api/attention/alerts/' + encodeURIComponent(alertUuid), {
            method: 'DELETE',
            headers: headers
        })
            .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
            .then(function (res) {
                if (!res.ok) {
                    throw new Error((res.body && res.body.message) ? res.body.message : 'Request failed');
                }
                load();
            })
            .catch(function (err) {
                window.alert('Dismiss failed: ' + err.message);
            });
    }

    function remind(alertUuid, payload) {
        fetch('/api/attention/alerts/' + encodeURIComponent(alertUuid) + '/remind', {
            method: 'POST',
            headers: buildJsonHeaders(),
            body: JSON.stringify(payload)
        })
            .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
            .then(function (res) {
                if (!res.ok) {
                    throw new Error((res.body && res.body.message) ? res.body.message : 'Request failed');
                }
                closeRemindModal();
                load();
            })
            .catch(function (err) {
                showRemindError('Remind failed: ' + err.message);
            });
    }

    function openRemindModal(alertUuid) {
        remindAlertUuid = alertUuid;
        remindCustomDatetime.value = '';
        clearRemindError();
        remindModal.classList.remove('hidden');
        remindModal.classList.add('flex');
    }

    function closeRemindModal() {
        remindAlertUuid = null;
        clearRemindError();
        remindModal.classList.add('hidden');
        remindModal.classList.remove('flex');
    }

    function showRemindError(message) {
        remindModalError.textContent = message;
        remindModalError.classList.remove('hidden');
    }

    function clearRemindError() {
        remindModalError.textContent = '';
        remindModalError.classList.add('hidden');
    }

    function handlePresetReminder(preset) {
        if (!remindAlertUuid) {
            return;
        }
        clearRemindError();
        remind(remindAlertUuid, { preset: preset });
    }

    function handleCustomReminder() {
        if (!remindAlertUuid) {
            return;
        }
        clearRemindError();
        if (!remindCustomDatetime.value) {
            showRemindError('Select a date and time.');
            return;
        }
        var epoch = new Date(remindCustomDatetime.value).getTime();
        if (!Number.isFinite(epoch)) {
            showRemindError('Invalid date and time.');
            return;
        }
        remind(remindAlertUuid, { attentionAt: epoch });
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function buildJsonHeaders() {
        var headers = { 'Content-Type': 'application/json' };
        if (csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        return headers;
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (remindModalClose) {
            remindModalClose.addEventListener('click', closeRemindModal);
        }
        if (remindModalCancel) {
            remindModalCancel.addEventListener('click', closeRemindModal);
        }
        if (remindModalSubmit) {
            remindModalSubmit.addEventListener('click', handleCustomReminder);
        }
        if (remindModal) {
            remindModal.addEventListener('click', function (event) {
                if (event.target === remindModal) {
                    closeRemindModal();
                }
            });
        }
        document.querySelectorAll('[data-remind-preset]').forEach(function (button) {
            button.addEventListener('click', function () {
                var preset = button.getAttribute('data-remind-preset');
                handlePresetReminder(preset);
            });
        });

        load();
        refreshTimer = setInterval(function () {
            if (document.visibilityState !== 'visible') {
                return;
            }
            load();
        }, 30000);
    });

    window.addEventListener('beforeunload', function () {
        if (refreshTimer !== null) {
            clearInterval(refreshTimer);
        }
    });
}());
