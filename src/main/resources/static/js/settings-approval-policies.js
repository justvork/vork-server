/* settings-approval-policies.js */
/* jshint esversion: 6 */

let policyModal;
let allPolicies = [];
let modalPolicyChannels = [];
let modalRules = [];

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

document.addEventListener('DOMContentLoaded', function () {
    policyModal = new VorkModal(document.getElementById('policyModal'));
    document.getElementById('policyModal').addEventListener('hidden.bs.modal', resetModal);
    document.addEventListener('click', handleOutsideClick);
    loadData();
});

async function loadData() {
    try {
        const policyRes = await fetch('/api/approval-policies');

        allPolicies = policyRes.ok ? await policyRes.json() : [];
        renderTable();
    } catch (_e) {
        showAlert('Failed to load approval policies.', 'warning');
    }
}

function renderTable() {
    const table = document.getElementById('policy-table');
    const body = document.getElementById('policy-table-body');
    const empty = document.getElementById('empty-state');

    body.innerHTML = '';
    if (!allPolicies || allPolicies.length === 0) {
        table.classList.add('hidden');
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    table.classList.remove('hidden');

    allPolicies.forEach(function (policy) {
        const policyId = (policy.uuid || policy.id || '').trim();
        const policyName = (policy.name || '').trim();
        const tr = document.createElement('tr');
        tr.className = 'border-b border-zinc-800/80 last:border-0';
        tr.innerHTML = ''
            + '<td class="px-3 py-2 font-semibold text-zinc-100">' + escapeHtml(policy.name || '') + '</td>'
            + '<td class="px-3 py-2">' + renderChannelPills(policy.channels || []) + '</td>'
            + '<td class="px-3 py-2">' + renderRuleSummary(policy.overrides || []) + '</td>'
            + '<td class="px-3 py-2">'
            + (policy.enabled
                ? '<span class="inline-flex rounded-md border border-emerald-500/40 bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-200">Enabled</span>'
                : '<span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-2 py-0.5 text-xs text-zinc-400">Disabled</span>')
            + '</td>'
            + '<td class="px-3 py-2 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openPolicyModal(\'' + escapeJs(policyId) + '\')" title="Edit policy"><i class="fa-solid fa-pen"></i></button>'
            + '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deletePolicy(\'' + escapeJs(policyId) + '\', \'' + escapeJs(policyName) + '\')" title="Delete policy"><i class="fa-solid fa-trash"></i></button>'
            + '  </div>'
            + '</td>';
        body.appendChild(tr);
    });
}

function renderChannelPills(channels) {
    if (!channels || channels.length === 0) {
        return '<span class="text-xs text-zinc-500">— none —</span>';
    }
    return channels.map(function (channel) {
        return '<span class="mr-1 mb-1 inline-flex items-center gap-1 rounded-full border border-zinc-700 bg-zinc-950 px-2.5 py-1 text-xs text-zinc-200">'
            + '<i class="fa-solid fa-user text-zinc-400"></i>'
            + '<span>' + escapeHtml(channel) + '</span>'
            + '</span>';
    }).join('');
}

function renderRuleSummary(rules) {
    if (!rules || rules.length === 0) {
        return '<span class="text-xs text-zinc-500">No rules</span>';
    }
    return '<span class="text-xs text-zinc-300">' + rules.length + ' rule(s)</span>';
}

function openPolicyModal(policyId) {
    clearAlert();
    document.getElementById('policy-id').value = '';
    document.getElementById('policy-name').value = '';
    document.getElementById('policy-enabled').checked = true;
    modalPolicyChannels = [];
    modalRules = [];

    if (!policyId) {
        document.getElementById('policyModalLabel').textContent = 'New Approval Policy';
        renderPolicyChannelPills();
        renderRules();
        policyModal.show();
        return;
    }

    const policy = allPolicies.find(function (p) { return p.uuid === policyId; });
    if (!policy) {
        showAlert('Policy not found. Reload the page.', 'warning');
        return;
    }

    document.getElementById('policyModalLabel').textContent = 'Edit Approval Policy';
    document.getElementById('policy-id').value = policy.uuid;
    document.getElementById('policy-name').value = policy.name || '';
    document.getElementById('policy-enabled').checked = !!policy.enabled;
    modalPolicyChannels = (policy.channels || []).slice();
    modalRules = (policy.overrides || []).map(function (rule) {
        return {
            day: rule.day || 'MONDAY',
            startTime: rule.startTime || '',
            endTime: rule.endTime || '',
            channels: (rule.channels || []).slice(),
            enabled: rule.enabled !== false
        };
    });

    renderPolicyChannelPills();
    renderRules();
    policyModal.show();
}

function resetModal() {
    document.getElementById('policy-id').value = '';
    document.getElementById('policy-name').value = '';
    document.getElementById('policy-enabled').checked = true;
    document.getElementById('policy-channel-search').value = '';
    document.getElementById('policy-channel-dropdown').classList.add('hidden');
    modalPolicyChannels = [];
    modalRules = [];
    clearAlert();
}

function handleOutsideClick(e) {
    if (!e.target.closest('#policy-channel-search') && !e.target.closest('#policy-channel-dropdown')) {
        const dropdown = document.getElementById('policy-channel-dropdown');
        if (dropdown) dropdown.classList.add('hidden');
    }

    modalRules.forEach(function (_rule, idx) {
        const searchSel = '#rule-channel-search-' + idx;
        const dropdownSel = '#rule-channel-dropdown-' + idx;
        if (!e.target.closest(searchSel) && !e.target.closest(dropdownSel)) {
            const ruleDropdown = document.getElementById('rule-channel-dropdown-' + idx);
            if (ruleDropdown) ruleDropdown.classList.add('hidden');
        }
    });
}

async function searchChannels(query) {
    const trimmed = (query || '').trim();
    if (!trimmed) {
        return [];
    }

    const url = '/api/channels/search?q=' + encodeURIComponent(trimmed) + '&limit=30';
    const res = await fetch(url);
    if (!res.ok) {
        return [];
    }
    const data = await res.json();
    return Array.isArray(data) ? data : [];
}

async function filterPolicyChannels() {
    const query = document.getElementById('policy-channel-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('policy-channel-dropdown');
    const list = document.getElementById('policy-channel-options');

    const rawMatches = await searchChannels(query);
    const matches = rawMatches.filter(function (channel) {
        const channelName = (channel.channelName || '').trim();
        if (!channelName) return false;
        if (modalPolicyChannels.includes(channelName)) return false;
        return true;
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (channel) {
        const channelName = channel.channelName;
        const displayName = channel.displayName || channelName;
        const li = document.createElement('li');
        li.className = 'tool-list-item cursor-pointer px-2 py-1.5 hover:bg-zinc-800';
        li.innerHTML = ''
            + '<div class="flex items-center gap-2">'
            + '  <i class="fa-solid fa-hashtag fa-xs text-zinc-400"></i>'
            + '  <span class="text-xs font-semibold text-zinc-100">' + escapeHtml(displayName) + '</span>'
            + '  <span class="text-[0.65rem] text-zinc-500">' + escapeHtml(channelName) + '</span>'
            + (channel.providerKey ? '  <span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[0.65rem] text-zinc-400">' + escapeHtml(channel.providerKey) + '</span>' : '')
            + '</div>';
        li.addEventListener('click', function () {
            addPolicyChannel(channelName);
            document.getElementById('policy-channel-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
}

function addPolicyChannel(channel) {
    if (!channel) return;
    if (!modalPolicyChannels.includes(channel)) {
        modalPolicyChannels.push(channel);
        renderPolicyChannelPills();
    }
}

function removePolicyChannel(channel) {
    modalPolicyChannels = modalPolicyChannels.filter(function (v) { return v !== channel; });
    renderPolicyChannelPills();
}

function renderPolicyChannelPills() {
    const container = document.getElementById('policy-channel-pill-container');
    container.innerHTML = '';
    if (!modalPolicyChannels || modalPolicyChannels.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No channels selected.</span>';
        return;
    }

    modalPolicyChannels.forEach(function (channel) {
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-user"></i>'
            + '<span>' + escapeHtml(channel) + '</span>'
            + '<span class="remove-tool" title="Remove channel">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removePolicyChannel(channel);
        });
        container.appendChild(pill);
    });
}

function addRule() {
    modalRules.push({
        day: 'MONDAY',
        startTime: '',
        endTime: '',
        channels: [],
        enabled: true
    });
    renderRules();
}

function removeRule(index) {
    modalRules.splice(index, 1);
    renderRules();
}

function updateRule(index, field, value) {
    if (!modalRules[index]) return;
    modalRules[index][field] = value;
}

function renderRules() {
    const container = document.getElementById('policy-rules');
    container.innerHTML = '';

    if (!modalRules || modalRules.length === 0) {
        container.innerHTML = '<p class="mb-0 text-xs text-zinc-500">No conditional rules. Base channels apply all week.</p>';
        return;
    }

    modalRules.forEach(function (rule, idx) {
        const row = document.createElement('div');
        row.className = 'mb-3 rounded-lg border border-zinc-700 bg-zinc-950 p-3';

        const dayOptions = DAYS.map(function (day) {
            return '<option value="' + day + '"' + (rule.day === day ? ' selected' : '') + '>' + day + '</option>';
        }).join('');

        row.innerHTML = ''
            + '<div class="mb-2 flex items-center justify-between">'
            + '  <div class="text-sm font-semibold text-zinc-200">Rule ' + (idx + 1) + '</div>'
            + '  <button type="button" class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15"><i class="fa-solid fa-trash"></i></button>'
            + '</div>'
            + '<div class="mb-3 grid grid-cols-1 gap-2 md:grid-cols-4">'
            + '  <div>'
            + '    <label class="mb-1 block text-xs text-zinc-400">Day</label>'
            + '    <select id="rule-day-' + idx + '" class="w-full rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-100">' + dayOptions + '</select>'
            + '  </div>'
            + '  <div>'
            + '    <label class="mb-1 block text-xs text-zinc-400">Start (optional)</label>'
            + '    <input id="rule-start-' + idx + '" type="time" class="w-full rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-100" value="' + escapeHtml(rule.startTime || '') + '">'
            + '  </div>'
            + '  <div>'
            + '    <label class="mb-1 block text-xs text-zinc-400">End (optional)</label>'
            + '    <input id="rule-end-' + idx + '" type="time" class="w-full rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-100" value="' + escapeHtml(rule.endTime || '') + '">'
            + '  </div>'
            + '  <div class="flex items-end">'
            + '    <label class="inline-flex items-center gap-2 rounded-md border border-zinc-700 bg-zinc-900 px-2 py-1 text-xs text-zinc-200">'
            + '      <input id="rule-enabled-' + idx + '" type="checkbox"' + (rule.enabled ? ' checked' : '') + ' class="h-3.5 w-3.5 rounded border-zinc-600 bg-zinc-950 text-cyan-400">'
            + '      Enabled'
            + '    </label>'
            + '  </div>'
            + '</div>'
            + '<div>'
            + '  <label class="mb-1 block text-xs text-zinc-400">Rule Channels</label>'
            + '  <div id="rule-channel-pill-container-' + idx + '" class="mb-2 flex min-h-9 flex-wrap gap-1 rounded border border-zinc-700 bg-zinc-900 p-2"></div>'
            + '  <div class="mb-1 flex items-center gap-2 rounded-md border border-zinc-700 bg-zinc-900 px-2">'
            + '    <span class="text-zinc-400"><i class="fa-solid fa-magnifying-glass"></i></span>'
            + '    <input type="text" id="rule-channel-search-' + idx + '" class="w-full bg-transparent py-1.5 text-xs text-zinc-100 placeholder:text-zinc-500 focus:outline-none" placeholder="Search channels...">'
            + '  </div>'
            + '  <div id="rule-channel-dropdown-' + idx + '" class="hidden max-h-44 overflow-y-auto rounded-md border border-zinc-700 bg-zinc-900">'
            + '    <ul id="rule-channel-options-' + idx + '" class="divide-y divide-zinc-800"></ul>'
            + '  </div>'
            + '</div>';

        const deleteBtn = row.querySelector('button');
        deleteBtn.addEventListener('click', function () { removeRule(idx); });

        container.appendChild(row);

        document.getElementById('rule-day-' + idx).addEventListener('change', function (e) {
            updateRule(idx, 'day', e.target.value);
        });
        document.getElementById('rule-start-' + idx).addEventListener('change', function (e) {
            updateRule(idx, 'startTime', e.target.value || '');
        });
        document.getElementById('rule-end-' + idx).addEventListener('change', function (e) {
            updateRule(idx, 'endTime', e.target.value || '');
        });
        document.getElementById('rule-enabled-' + idx).addEventListener('change', function (e) {
            updateRule(idx, 'enabled', !!e.target.checked);
        });

        document.getElementById('rule-channel-search-' + idx).addEventListener('input', function () {
            filterRuleChannels(idx);
        });

        renderRuleChannelPills(idx);
    });
}

async function filterRuleChannels(index) {
    const rule = modalRules[index];
    if (!rule) return;

    const queryEl = document.getElementById('rule-channel-search-' + index);
    const dropdown = document.getElementById('rule-channel-dropdown-' + index);
    const list = document.getElementById('rule-channel-options-' + index);
    if (!queryEl || !dropdown || !list) return;

    const query = queryEl.value.toLowerCase().trim();
    const rawMatches = await searchChannels(query);
    const matches = rawMatches.filter(function (channel) {
        const channelName = (channel.channelName || '').trim();
        if (!channelName) return false;
        if ((rule.channels || []).includes(channelName)) return false;
        return true;
    });

    list.innerHTML = '';
    if (matches.length === 0) {
        dropdown.classList.add('hidden');
        return;
    }

    matches.forEach(function (channel) {
        const channelName = channel.channelName;
        const displayName = channel.displayName || channelName;
        const li = document.createElement('li');
        li.className = 'tool-list-item cursor-pointer px-2 py-1.5 hover:bg-zinc-800';
        li.innerHTML = ''
            + '<div class="flex items-center gap-2">'
            + '  <i class="fa-solid fa-hashtag fa-xs text-zinc-400"></i>'
            + '  <span class="text-xs font-semibold text-zinc-100">' + escapeHtml(displayName) + '</span>'
            + '  <span class="text-[0.65rem] text-zinc-500">' + escapeHtml(channelName) + '</span>'
            + (channel.providerKey ? '  <span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[0.65rem] text-zinc-400">' + escapeHtml(channel.providerKey) + '</span>' : '')
            + '</div>';
        li.addEventListener('click', function () {
            addRuleChannel(index, channelName);
            queryEl.value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });

    dropdown.classList.remove('hidden');
}

function addRuleChannel(index, channel) {
    const rule = modalRules[index];
    if (!rule || !channel) return;
    rule.channels = rule.channels || [];
    if (!rule.channels.includes(channel)) {
        rule.channels.push(channel);
        renderRuleChannelPills(index);
    }
}

function removeRuleChannel(index, channel) {
    const rule = modalRules[index];
    if (!rule) return;
    rule.channels = (rule.channels || []).filter(function (v) { return v !== channel; });
    renderRuleChannelPills(index);
}

function renderRuleChannelPills(index) {
    const rule = modalRules[index];
    const container = document.getElementById('rule-channel-pill-container-' + index);
    if (!rule || !container) return;

    container.innerHTML = '';
    if (!rule.channels || rule.channels.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No channels selected.</span>';
        return;
    }

    rule.channels.forEach(function (channel) {
        const pill = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML = ''
            + '<i class="fa-solid fa-user"></i>'
            + '<span>' + escapeHtml(channel) + '</span>'
            + '<span class="remove-tool" title="Remove channel">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () {
            removeRuleChannel(index, channel);
        });
        container.appendChild(pill);
    });
}

async function savePolicy() {
    const id = document.getElementById('policy-id').value.trim();
    const name = document.getElementById('policy-name').value.trim();
    const enabled = document.getElementById('policy-enabled').checked;

    if (!name) {
        showAlert('Policy name is required.', 'warning');
        return;
    }
    if (!modalPolicyChannels || modalPolicyChannels.length === 0) {
        showAlert('Select at least one policy channel.', 'warning');
        return;
    }

    for (let i = 0; i < modalRules.length; i++) {
        const rule = modalRules[i];
        if (!rule.day) {
            showAlert('Rule ' + (i + 1) + ' must include a day.', 'warning');
            return;
        }
        if (!rule.channels || rule.channels.length === 0) {
            showAlert('Rule ' + (i + 1) + ' must include at least one channel.', 'warning');
            return;
        }
    }

    const payload = {
        name: name,
        enabled: enabled,
        channels: modalPolicyChannels.slice(),
        overrides: modalRules.map(function (rule) {
            return {
                day: rule.day,
                startTime: rule.startTime || '',
                endTime: rule.endTime || '',
                channels: (rule.channels || []).slice(),
                enabled: rule.enabled !== false
            };
        })
    };

    const btn = document.getElementById('save-policy-btn');
    btn.disabled = true;
    btn.innerHTML = '<span class="mr-1 inline-block h-3 w-3 animate-spin rounded-full border border-current border-t-transparent align-[-0.1em]"></span>Saving...';

    try {
        const url = id ? '/api/approval-policies/' + encodeURIComponent(id) : '/api/approval-policies';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (!res.ok || data.error) {
            showAlert(data.error || 'Failed to save policy.', 'danger');
            return;
        }

        policyModal.hide();
        showAlert(id ? 'Policy updated.' : 'Policy created.', 'success');
        await loadData();
    } catch (_e) {
        showAlert('Network error saving policy.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save mr-1"></i>Save Policy';
    }
}

async function deletePolicy(policyId, policyName) {
    if (!confirm('Delete this policy? This cannot be undone.')) return;
    try {
        let res;
        if ((policyId || '').trim()) {
            res = await fetch('/api/approval-policies/' + encodeURIComponent(policyId), {
                method: 'DELETE'
            });
        } else if ((policyName || '').trim()) {
            res = await fetch('/api/approval-policies?name=' + encodeURIComponent(policyName), {
                method: 'DELETE'
            });
        } else {
            showAlert('Unable to delete policy: missing identifier.', 'danger');
            return;
        }

        const data = await res.json();
        if (!res.ok || data.error) {
            showAlert(data.error || 'Failed to delete policy.', 'danger');
            return;
        }
        showAlert('Policy deleted.', 'success');
        await loadData();
    } catch (_e) {
        showAlert('Network error deleting policy.', 'danger');
    }
}

function showAlert(message, type) {
    const area = document.getElementById('alert-area');
    if (!area) return;
    area.innerHTML = '<div class="rounded-lg border px-3 py-2 text-sm '
        + (type === 'success' ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200' : '')
        + (type === 'warning' ? 'border-amber-500/40 bg-amber-500/10 text-amber-200' : '')
        + (type === 'danger' ? 'border-rose-500/40 bg-rose-500/10 text-rose-200' : '')
        + '">' + escapeHtml(message) + '</div>';
}

function clearAlert() {
    const area = document.getElementById('alert-area');
    if (area) area.innerHTML = '';
}

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escapeJs(value) {
    return String(value == null ? '' : value)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/\"/g, '\\"')
        .replace(/\n/g, '\\n')
        .replace(/\r/g, '\\r');
}
