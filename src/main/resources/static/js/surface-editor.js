/* surface-editor.js — Surface Editor: chat, file tree, code viewer, config sidebar */
/* global StompJs, SockJS, marked, Prism */

'use strict';

marked.use({ breaks: true, gfm: true });

const rootEl = document.getElementById('surface-editor-root');
const surfaceUuid = rootEl ? rootEl.dataset.uuid : null;

const messagesArea = document.getElementById('messages-area');
const chatForm = document.getElementById('chat-form');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const chatStatus = document.getElementById('chat-status');
const fileTree = document.getElementById('file-tree');
const refreshFilesBtn = document.getElementById('refresh-files-btn');
const newFolderBtn = document.getElementById('new-folder-btn');
const viewerPre = document.getElementById('viewer-pre');
const viewerCode = document.getElementById('viewer-code');
const viewerEmpty = document.getElementById('viewer-empty');
const viewerFilename = document.getElementById('viewer-filename');
const viewerMeta = document.getElementById('viewer-meta');

let surface = null;
let sessionUuid = null;
let stomp = null;
let chatSubscription = null;
let waiting = false;
let selectedPath = null;
let treeData = null;
let workingIndicatorEl = null;

let allSkills = [];
let allReflectionBindings = [];
let allJobs = [];
let skillGroupNameByUuid = new Map();
let lookupsReady = false;

let loadedDirs = new Map(); // path -> element

// ── Init ─────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', function () {
    if (!surfaceUuid) {
        showError('Surface UUID is missing.');
        return;
    }
    setupConfigSidebar();
    loadSurface();

    chatForm.addEventListener('submit', handleSend);
    refreshFilesBtn.addEventListener('click', loadFileTree);
    newFolderBtn.addEventListener('click', createFolder);

    messageInput.addEventListener('keydown', function (e) {
        if (waiting) {
            e.preventDefault();
            return;
        }
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            chatForm.requestSubmit();
        }
    });
});

// ── Surface + session loading ────────────────────────────────────────────────

async function loadSurface() {
    try {
        const [surfaceRes, skillsRes, skillGroupsRes, reflectionGroupsRes, jobsRes] = await Promise.all([
            fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid)),
            fetch('/api/skills?includePrivate=true'),
            fetch('/api/skill-groups'),
            fetch('/api/reflection-groups'),
            fetch('/api/jobs')
        ]);

        if (!surfaceRes.ok) {
            showError('Surface not found.');
            return;
        }
        surface = await surfaceRes.json();

        allSkills = skillsRes.ok ? await skillsRes.json() : [];
        skillGroupNameByUuid = skillGroupsRes.ok ? buildSkillGroupMap(await skillGroupsRes.json()) : new Map();
        allReflectionBindings = reflectionGroupsRes.ok ? buildBindingList(await reflectionGroupsRes.json()) : [];
        allJobs = jobsRes.ok ? await jobsRes.json() : [];

        renderConfigSidebar();
        await loadSession();
    } catch (e) {
        showError('Failed to load surface: ' + e.message);
    }
}

function buildBindingList(groups) {
    const list = [];
    (groups || []).forEach(function (g) {
        const group = g.group || g;
        const bindings = g.bindings || [];
        bindings.forEach(function (b) {
            list.push({
                uuid: b.uuid,
                name: b.name,
                groupName: group.name || '—'
            });
        });
    });
    return list;
}

function buildSkillGroupMap(groups) {
    const map = new Map();
    (groups || []).forEach(function (entry) {
        const group = entry && entry.group ? entry.group : entry;
        if (!group || !group.uuid) return;
        map.set(group.uuid, group.name || group.uuid);
    });
    return map;
}

async function loadSession() {
    try {
        const res = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid) + '/session');
        if (!res.ok) {
            showError('Could not load surface session.');
            return;
        }
        const data = await res.json();
        sessionUuid = data.sessionUuid;
        (data.messages || []).forEach(renderHistoryMessage);
        connectStomp();
        loadFileTree();
    } catch (e) {
        showError('Failed to load session: ' + e.message);
    }
}

function renderHistoryMessage(msg) {
    if (!msg) return;
    if (msg.role === 'TOOL') {
        return;
    }
    if (msg.role === 'PROMPT_REQUIRED') {
        const parsed = tryParseJson(msg.content);
        const text = parsed && (parsed.textResponse || parsed.justification)
            ? (parsed.textResponse || parsed.justification)
            : (msg.content || 'This action requires your approval.');
        renderMessage({ role: 'ERROR', content: text });
        return;
    }
    renderMessage(msg);
}

// ── STOMP chat ───────────────────────────────────────────────────────────────

function connectStomp() {
    if (!sessionUuid) return;

    setChatStatus('connecting');
    const socket = new SockJS('/ws');
    stomp = StompJs.Stomp.over(socket);
    stomp.connect({}, function () {
        setChatStatus('connected');
        if (chatSubscription) {
            chatSubscription.unsubscribe();
        }
        chatSubscription = stomp.subscribe('/topic/chat/' + encodeURIComponent(sessionUuid), function (frame) {
            const msg = JSON.parse(frame.body);

            // Non-terminal TOOL messages are internal plumbing — never render their raw JSON.
            if (msg && msg.role === 'TOOL') {
                return;
            }

            if (isUiEventFrame(msg)) {
                handleIncomingUiFrame(msg);
                return;
            }

            renderMessage(msg);
            if (msg.role === 'ASSISTANT' || msg.role === 'ERROR' || msg.role === 'PROMPT_REQUIRED') {
                hideWorkingIndicator();
                setInputEnabled(true);
            }
        });
    }, function () {
        setChatStatus('disconnected');
        setInputEnabled(false);
        setTimeout(connectStomp, 3000);
    });
}

function setChatStatus(state) {
    const labels = {
        connected: 'Connected',
        disconnected: 'Disconnected',
        connecting: 'Connecting…'
    };
    chatStatus.textContent = labels[state] || state;
    chatStatus.className = 'text-xs ' + (state === 'connected' ? 'text-emerald-400' : 'text-zinc-500');
}

function handleSend(e) {
    e.preventDefault();
    if (waiting) return;

    const content = messageInput.value.trim();
    if (!content) return;

    messageInput.value = '';
    messageInput.style.height = 'auto';

    if (!stomp || !stomp.connected) {
        renderMessage({ role: 'USER', content: content });
        renderMessage({ role: 'ERROR', content: 'Not connected. Reconnecting… please try again in a moment.' });
        return;
    }

    setInputEnabled(false);
    renderMessage({ role: 'USER', content: content });
    showWorkingIndicator();

    try {
        stomp.publish({
            destination: '/app/chat.send',
            body: JSON.stringify({
                sessionUuid: sessionUuid,
                content: content,
                provider: 'GEMINI'
            })
        });
    } catch (err) {
        hideWorkingIndicator();
        setInputEnabled(true);
        renderMessage({ role: 'ERROR', content: 'Failed to send message: ' + (err && err.message ? err.message : 'unknown error') });
    }
}

function setInputEnabled(on) {
    messageInput.disabled = !on;
    sendBtn.disabled = !on;
    waiting = !on;
}

function isUiEventFrame(obj) {
    return obj && typeof obj === 'object'
        && typeof obj.type === 'string'
        && typeof obj.intent === 'string'
        && (
            (obj.payload && typeof obj.payload === 'object')
            || typeof obj.textResponse === 'string'
            || (obj.formSchema && typeof obj.formSchema === 'object')
        );
}

function tryParseJson(text) {
    if (!text || typeof text !== 'string') return null;
    try {
        return JSON.parse(text);
    } catch (_) {
        return null;
    }
}

function renderTransitionEvent(text, iconClass) {
    const row = document.createElement('div');
    row.className = 'message-row transition-row';
    row.innerHTML = '<div class="transition-bubble"><i class="fa-solid ' + (iconClass || 'fa-gears') + '"></i><span>' + escapeHtml(text || '') + '</span></div>';
    messagesArea.appendChild(row);
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

function handleIncomingUiFrame(frame) {
    switch (frame.type) {
        case 'AI_THINKING':
            // Suppressed in the surface editor to keep the chat tidy.
            showWorkingIndicator();
            return;

        case 'TEXT_RESPONSE':
            hideWorkingIndicator();
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
                return;
            }
            if (typeof frame.textResponse === 'string' && frame.textResponse) {
                renderMessage({ role: 'ASSISTANT', content: frame.textResponse });
                return;
            }
            if (frame.payload && typeof frame.payload.content === 'string' && frame.payload.content) {
                renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
                return;
            }
            return;

        case 'AGENT_TRANSITION':
        case 'SKILL_TRANSITION':
            renderTransitionEvent(frame.textResponse || '', frame.type === 'SKILL_TRANSITION' ? 'fa-bolt' : 'fa-gears');
            return;

        case 'AGENT_SWITCH':
        case 'MANUAL_AGENT_SWITCH':
            // No-op in the surface editor (no agent dropdown to update).
            return;

        case 'PROMPT_REQUIRED':
            hideWorkingIndicator();
            renderMessage({
                role: 'ERROR',
                content: (typeof frame.textResponse === 'string' && frame.textResponse)
                    ? frame.textResponse
                    : 'This action requires your approval.'
            });
            setInputEnabled(true);
            return;

        case 'ERROR':
            hideWorkingIndicator();
            renderMessage({
                role: 'ERROR',
                content: (typeof frame.textResponse === 'string' && frame.textResponse)
                    ? frame.textResponse
                    : ((frame.payload && frame.payload.message) ? String(frame.payload.message) : 'Unknown error')
            });
            setInputEnabled(true);
            return;

        default:
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
            }
    }
}

function renderMessage(msg) {
    if (!msg) return;

    const isUser = msg.role === 'USER';
    const isError = msg.role === 'ERROR';
    const content = (!isUser && !isError)
        ? normalizeAssistantContent(msg.content)
        : msg.content;

    // Skip empty assistant/transition messages, but keep empty user messages
    // (the user sent them) and error messages.
    if (!isUser && !isError && (content == null || content === '')) {
        return;
    }

    if (!isUser) {
        hideWorkingIndicator();
    }

    const row = document.createElement('div');
    row.className = 'message-row ' + (isUser ? 'user' : 'assistant');

    const avatar = document.createElement('div');
    avatar.className = 'avatar ' + (isUser ? 'user' : 'assistant');
    avatar.innerHTML = '<i class="fa-solid ' + (isUser ? 'fa-user' : 'fa-robot') + '"></i>';

    const bubble = document.createElement('div');
    bubble.className = 'bubble ' + (isUser ? 'user' : 'assistant');

    if (isError) {
        bubble.classList.add('border-rose-500/40', 'bg-rose-500/10', 'text-rose-200');
        bubble.textContent = content || 'An error occurred.';
    } else if (isUser) {
        bubble.innerHTML = escapeHtml(content || '').replace(/\n/g, '<br>');
    } else {
        bubble.innerHTML = marked.parse(content || '', { sanitize: false });
    }

    row.appendChild(avatar);
    row.appendChild(bubble);
    messagesArea.appendChild(row);
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

function normalizeAssistantContent(content) {
    if (typeof content !== 'string' || !content.trim()) {
        return content || '';
    }
    const parsed = tryParseJson(content);
    if (!parsed || typeof parsed !== 'object') {
        return content;
    }
    if (typeof parsed.textResponse === 'string' && parsed.textResponse.trim()) {
        return parsed.textResponse;
    }
    for (const key of ['response', 'message', 'content', 'output', 'text', 'reply', 'result']) {
        if (typeof parsed[key] === 'string' && parsed[key].trim()) {
            return parsed[key];
        }
    }
    return content;
}

function showWorkingIndicator() {
    if (workingIndicatorEl) return;

    const row = document.createElement('div');
    row.className = 'message-row assistant working-row';
    row.id = 'chat-working-indicator';

    const avatar = document.createElement('div');
    avatar.className = 'avatar assistant';
    avatar.innerHTML = '<i class="fa-solid fa-robot"></i>';

    const bubble = document.createElement('div');
    bubble.className = 'bubble assistant working-bubble';
    bubble.innerHTML = '<span class="working-label">Vorking</span><span class="working-dots"><span></span><span></span><span></span></span>';

    row.appendChild(avatar);
    row.appendChild(bubble);
    messagesArea.appendChild(row);
    messagesArea.scrollTop = messagesArea.scrollHeight;
    workingIndicatorEl = row;
}

function hideWorkingIndicator() {
    if (!workingIndicatorEl) return;
    workingIndicatorEl.remove();
    workingIndicatorEl = null;
}

function showError(message) {
    messagesArea.innerHTML = '<div class="p-4 text-rose-300">' + escapeHtml(message) + '</div>';
}

// ── File tree ────────────────────────────────────────────────────────────────

async function loadFileTree() {
    if (!sessionUuid) return;
    try {
        const res = await fetch('/api/session-files/list?area=SESSION&sessionUuid=' + encodeURIComponent(sessionUuid));
        if (!res.ok) {
            fileTree.innerHTML = '<div class="text-xs text-rose-300 p-2">Failed to load files.</div>';
            return;
        }
        const data = await res.json();
        treeData = data.items || [];
        loadedDirs.clear();
        renderTree(treeData, fileTree, '');
    } catch (e) {
        fileTree.innerHTML = '<div class="text-xs text-rose-300 p-2">' + escapeHtml(e.message) + '</div>';
    }
}

function renderTree(items, container, parentPath) {
    container.innerHTML = '';
    if (!items || items.length === 0) {
        container.innerHTML = '<div class="text-xs text-zinc-500 p-1">No files</div>';
        return;
    }

    const sorted = [...items].sort(function (a, b) {
        if (a.directory === b.directory) return (a.name || '').localeCompare(b.name || '');
        return a.directory ? -1 : 1;
    });

    sorted.forEach(function (item) {
        const fullPath = parentPath ? parentPath + '/' + item.name : item.name;
        const node = document.createElement('div');
        node.className = 'tree-node';

        const itemEl = document.createElement('div');
        itemEl.className = 'tree-item' + (selectedPath === fullPath ? ' selected' : '');
        itemEl.title = fullPath;

        const iconClass = item.directory ? 'fa-folder text-amber-400' : fileIconClass(item.name);
        itemEl.innerHTML = '<i class="fa-solid ' + iconClass + '"></i><span class="truncate">' + escapeHtml(item.name) + '</span>';

        if (item.directory) {
            const childrenContainer = document.createElement('div');
            childrenContainer.className = 'tree-node-children';
            childrenContainer.style.display = 'none';

            itemEl.addEventListener('click', function () {
                if (childrenContainer.style.display === 'none') {
                    if (!loadedDirs.has(fullPath)) {
                        loadDirectory(fullPath, childrenContainer);
                        loadedDirs.set(fullPath, true);
                    }
                    childrenContainer.style.display = 'block';
                } else {
                    childrenContainer.style.display = 'none';
                }
            });

            node.appendChild(itemEl);
            node.appendChild(childrenContainer);
        } else {
            itemEl.addEventListener('click', function () {
                selectedPath = fullPath;
                loadFile(fullPath);
                document.querySelectorAll('.tree-item.selected').forEach(function (el) { el.classList.remove('selected'); });
                itemEl.classList.add('selected');
            });
            node.appendChild(itemEl);
        }

        container.appendChild(node);
    });
}

async function loadDirectory(dir, container) {
    try {
        const res = await fetch('/api/session-files/list?area=SESSION&sessionUuid=' + encodeURIComponent(sessionUuid) + '&dir=' + encodeURIComponent(dir));
        if (!res.ok) return;
        const data = await res.json();
        renderTree(data.items || [], container, dir);
    } catch (e) {
        container.innerHTML = '<div class="text-xs text-rose-300 p-1">' + escapeHtml(e.message) + '</div>';
    }
}

async function loadFile(path) {
    if (!sessionUuid || !path) return;
    try {
        const res = await fetch('/api/session-files/download?area=SESSION&sessionUuid=' + encodeURIComponent(sessionUuid) + '&path=' + encodeURIComponent(path));
        if (!res.ok) {
            viewerEmpty.innerHTML = '<div class="text-rose-300">Failed to load file.</div>';
            viewerEmpty.classList.remove('hidden');
            viewerPre.classList.add('hidden');
            return;
        }
        const text = await res.text();
        const language = detectLanguage(path);
        viewerCode.className = 'language-' + language;
        viewerCode.textContent = text;
        viewerFilename.innerHTML = '<i class="fa-solid fa-file-code mr-2 text-[#fdaa02]"></i>' + escapeHtml(path);
        viewerMeta.textContent = text.length + ' chars';
        viewerEmpty.classList.add('hidden');
        viewerPre.classList.remove('hidden');
        Prism.highlightElement(viewerCode);
    } catch (e) {
        viewerEmpty.innerHTML = '<div class="text-rose-300">' + escapeHtml(e.message) + '</div>';
        viewerEmpty.classList.remove('hidden');
        viewerPre.classList.add('hidden');
    }
}

async function createFolder() {
    if (!sessionUuid) return;
    const name = window.prompt('Folder name (relative to root):');
    if (!name || !name.trim()) return;
    try {
        const res = await fetch('/api/session-files/create-folder?area=SESSION&sessionUuid=' + encodeURIComponent(sessionUuid) + '&dir=' + encodeURIComponent(name.trim()), {
            method: 'POST'
        });
        if (!res.ok) {
            const data = await res.json().catch(function () { return {}; });
            alert(data.message || 'Failed to create folder.');
            return;
        }
        loadFileTree();
    } catch (e) {
        alert('Failed to create folder: ' + e.message);
    }
}

function fileIconClass(name) {
    if (!name) return 'fa-file text-zinc-400';
    const lower = name.toLowerCase();
    if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'fa-brands fa-html5 text-orange-400';
    if (lower.endsWith('.css')) return 'fa-brands fa-css3-alt text-blue-400';
    if (lower.endsWith('.js')) return 'fa-brands fa-js text-yellow-300';
    if (lower.endsWith('.java')) return 'fa-brands fa-java text-red-300';
    if (lower.endsWith('.json')) return 'fa-file-code text-zinc-300';
    if (lower.endsWith('.md')) return 'fa-file-lines text-zinc-300';
    if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.gif') || lower.endsWith('.svg')) return 'fa-image text-zinc-300';
    return 'fa-file text-zinc-400';
}

function detectLanguage(path) {
    if (!path) return 'text';
    const lower = path.toLowerCase();
    if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'html';
    if (lower.endsWith('.css')) return 'css';
    if (lower.endsWith('.js')) return 'javascript';
    if (lower.endsWith('.java')) return 'java';
    if (lower.endsWith('.json')) return 'json';
    if (lower.endsWith('.md')) return 'markdown';
    if (lower.endsWith('.yaml') || lower.endsWith('.yml')) return 'yaml';
    if (lower.endsWith('.xml')) return 'xml';
    if (lower.endsWith('.sql')) return 'sql';
    return 'text';
}

// ── Configuration sidebar ────────────────────────────────────────────────────

function setupConfigSidebar() {
    document.querySelectorAll('.config-section-title').forEach(function (title) {
        title.addEventListener('click', function () {
            const targetId = title.dataset.target;
            const target = document.getElementById(targetId);
            if (!target) return;
            title.classList.toggle('collapsed');
            target.classList.toggle('hidden');
        });
    });

    if (!lookupsReady) {
        setupLookup('skillUuids', 'skill-lookup-input', 'skill-lookup-results', function () { return allSkills; });
        setupLookup('reflectionBindingUuids', 'reflection-lookup-input', 'reflection-lookup-results', function () {
            return allReflectionBindings;
        });
        setupLookup('jobUuids', 'job-lookup-input', 'job-lookup-results', function () { return allJobs; });
        lookupsReady = true;
    }
}

function renderConfigSidebar() {
    if (!surface) return;

    renderChipList('skills-list', 'skillUuids', allSkills);
    renderChipList('reflections-list', 'reflectionBindingUuids', allReflectionBindings);
    renderChipList('jobs-list', 'jobUuids', allJobs);

    renderLookupResults('skillUuids', 'skill-lookup-input', 'skill-lookup-results', allSkills);
    renderLookupResults('reflectionBindingUuids', 'reflection-lookup-input', 'reflection-lookup-results', allReflectionBindings);
    renderLookupResults('jobUuids', 'job-lookup-input', 'job-lookup-results', allJobs);
}

function renderChipList(containerId, field, allItems) {
    const container = document.getElementById(containerId);
    container.innerHTML = '';
    const assigned = surface[field] || [];
    if (assigned.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">None assigned</span>';
        return;
    }
    assigned.forEach(function (uuid) {
        const item = allItems.find(function (i) { return i.uuid === uuid; });
        const name = item ? formatLookupLabel(field, item) : uuid;
        const chip = document.createElement('div');
        chip.className = 'config-chip';
        chip.title = name;
        chip.innerHTML = '<span>' + escapeHtml(name) + '</span>'
            + '<button type="button" aria-label="Remove"><i class="fa-solid fa-xmark"></i></button>';
        chip.querySelector('button').addEventListener('click', function () {
            removeAssignment(field, uuid);
        });
        container.appendChild(chip);
    });
}

function availableItemsFor(field, allItems) {
    const assigned = new Set(surface[field] || []);
    return allItems.filter(function (i) { return !assigned.has(i.uuid); });
}

function renderLookupResults(field, inputId, resultsId, allItems) {
    const input = document.getElementById(inputId);
    const results = document.getElementById(resultsId);
    if (!input || !results || !surface) return;

    const query = (input.value || '').trim().toLowerCase();
    const filtered = availableItemsFor(field, allItems).filter(function (item) {
        const label = formatLookupLabel(field, item);
        const haystack = (label + ' ' + (item.name || '') + ' ' + (item.groupName || '')).toLowerCase();
        return query === '' || haystack.includes(query);
    });

    results.innerHTML = '';

    if (filtered.length === 0) {
        input.dataset.lookupActiveIndex = '-1';
        const empty = document.createElement('div');
        empty.className = 'config-lookup-empty';
        empty.textContent = query ? 'No matches' : ('No unassigned ' + friendlyName(field) + 's');
        results.appendChild(empty);
        return;
    }

    filtered.slice(0, 30).forEach(function (item, index) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'config-lookup-item';
        button.dataset.lookupIndex = String(index);
        button.textContent = formatLookupLabel(field, item);
        button.addEventListener('click', function () {
            addAssignment(field, item.uuid);
            input.value = '';
            results.classList.remove('open');
        });
        button.addEventListener('mouseenter', function () {
            setLookupActiveIndex(input, results, index);
        });
        results.appendChild(button);
    });

    setLookupActiveIndex(input, results, 0);
}

function formatLookupLabel(field, item) {
    if (!item) return '';

    if (field === 'skillUuids') {
        const skillName = item.name || item.uuid || '';
        const groupName = resolveSkillGroupName(item);
        return groupName ? (groupName + ' - ' + skillName) : skillName;
    }

    if (field === 'reflectionBindingUuids') {
        const bindingName = item.name || item.uuid || '';
        const groupName = item.groupName || '';
        return groupName ? (groupName + ' - ' + bindingName) : bindingName;
    }

    return item.name || item.uuid || '';
}

function resolveSkillGroupName(skill) {
    if (!skill) return '';
    if (skill.groupName && skill.groupName.trim()) return skill.groupName;
    if (skill.groupUuid && skillGroupNameByUuid.has(skill.groupUuid)) {
        return skillGroupNameByUuid.get(skill.groupUuid) || '';
    }
    return '';
}

function setupLookup(field, inputId, resultsId, getAllItems) {
    const input = document.getElementById(inputId);
    const results = document.getElementById(resultsId);
    if (!input || !results) return;

    input.addEventListener('focus', function () {
        renderLookupResults(field, inputId, resultsId, getAllItems());
        results.classList.add('open');
    });

    input.addEventListener('input', function () {
        renderLookupResults(field, inputId, resultsId, getAllItems());
        results.classList.add('open');
    });

    input.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            results.classList.remove('open');
            return;
        }
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (!results.classList.contains('open')) {
                renderLookupResults(field, inputId, resultsId, getAllItems());
                results.classList.add('open');
            }
            moveLookupActiveIndex(input, results, 1);
            return;
        }
        if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (!results.classList.contains('open')) {
                renderLookupResults(field, inputId, resultsId, getAllItems());
                results.classList.add('open');
            }
            moveLookupActiveIndex(input, results, -1);
            return;
        }
        if (e.key === 'Enter') {
            const active = getActiveLookupItem(input, results) || results.querySelector('.config-lookup-item');
            if (active) {
                e.preventDefault();
                active.click();
            }
        }
    });

    document.addEventListener('click', function (e) {
        if (!results.contains(e.target) && e.target !== input) {
            results.classList.remove('open');
        }
    });
}

function getLookupItems(results) {
    return Array.from(results.querySelectorAll('.config-lookup-item'));
}

function setLookupActiveIndex(input, results, index) {
    const items = getLookupItems(results);
    if (items.length === 0) {
        input.dataset.lookupActiveIndex = '-1';
        return;
    }
    const boundedIndex = Math.max(0, Math.min(index, items.length - 1));
    input.dataset.lookupActiveIndex = String(boundedIndex);
    items.forEach(function (item, i) {
        item.classList.toggle('active', i === boundedIndex);
    });
    items[boundedIndex].scrollIntoView({ block: 'nearest' });
}

function moveLookupActiveIndex(input, results, delta) {
    const items = getLookupItems(results);
    if (items.length === 0) return;
    const current = parseInt(input.dataset.lookupActiveIndex || '0', 10);
    let next = current + delta;
    if (Number.isNaN(next)) next = 0;
    if (next < 0) next = items.length - 1;
    if (next >= items.length) next = 0;
    setLookupActiveIndex(input, results, next);
}

function getActiveLookupItem(input, results) {
    const items = getLookupItems(results);
    if (items.length === 0) return null;
    const index = parseInt(input.dataset.lookupActiveIndex || '-1', 10);
    if (Number.isNaN(index) || index < 0 || index >= items.length) {
        return null;
    }
    return items[index];
}

function friendlyName(field) {
    if (field === 'skillUuids') return 'skill';
    if (field === 'reflectionBindingUuids') return 'reflection binding';
    if (field === 'jobUuids') return 'job';
    return 'item';
}

async function addAssignment(field, uuid) {
    if (!uuid || !surface) return;
    const list = [...(surface[field] || [])];
    if (list.includes(uuid)) return;
    list.push(uuid);
    await saveAssignments(field, list);
}

async function removeAssignment(field, uuid) {
    if (!surface) return;
    const list = (surface[field] || []).filter(function (id) { return id !== uuid; });
    await saveAssignments(field, list);
}

async function saveAssignments(field, list) {
    const payload = {
        name: surface.name,
        description: surface.description,
        skillUuids: surface.skillUuids,
        reflectionBindingUuids: surface.reflectionBindingUuids,
        jobUuids: surface.jobUuids
    };
    payload[field] = list;

    try {
        const res = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!res.ok) {
            alert('Failed to save assignment.');
            return;
        }
        surface = await res.json();
        renderConfigSidebar();
    } catch (e) {
        alert('Failed to save assignment: ' + e.message);
    }
}

// ── Utilities ────────────────────────────────────────────────────────────────

function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
