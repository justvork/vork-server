/* skills.js — Vork Skills management page */
/* jshint esversion: 6 */

const PARAM_TYPES = ['string', 'text', 'int', 'double', 'boolean'];

let skillModal;
let groupModal;
let allSkills     = [];
let allGroups     = [];
let allGroupViews = [];
let allTools      = [];
let allTypes      = [];
let allCategories = [];
let categoriesLoadFailed = false;
let modalTools      = [];
let modalTypes      = [];
let modalSubSkills  = [];
let modalParams     = []; // [{name, type, description, inputMode}]
let modalSecrets    = []; // [{name, description}]

// ── Init ──────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', function () {
    skillModal = new VorkModal(document.getElementById('skillModal'));
    groupModal = new VorkModal(document.getElementById('groupModal'));
    loadData();

    document.getElementById('skillModal').addEventListener('hidden.bs.modal', function () {
        clearAlert('skill-modal-alert');
        document.getElementById('tool-search').value        = '';
        document.getElementById('type-search').value        = '';
        document.getElementById('subskill-search').value    = '';
        document.getElementById('tool-dropdown').classList.add('hidden');
        document.getElementById('type-dropdown').classList.add('hidden');
        document.getElementById('subskill-dropdown').classList.add('hidden');
    });

    document.getElementById('groupModal').addEventListener('hidden.bs.modal', function () {
        clearAlert('group-modal-alert');
    });
    document.addEventListener('click', function (e) {
        if (!e.target.closest('#tool-search') && !e.target.closest('#tool-dropdown')) {
            document.getElementById('tool-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#type-search') && !e.target.closest('#type-dropdown')) {
            document.getElementById('type-dropdown').classList.add('hidden');
        }
        if (!e.target.closest('#subskill-search') && !e.target.closest('#subskill-dropdown')) {
            document.getElementById('subskill-dropdown').classList.add('hidden');
        }
    });
});

async function loadData() {
    try {
        const [skillsRes, groupsRes, toolsRes, typesRes, catsRes] = await Promise.all([
            fetch('/api/skills?includePrivate=true'),
            fetch('/api/skill-groups'),
            fetch('/api/management/tools'),
            fetch('/api/types/java-types'),
            fetch('/api/skills/categories')
        ]);
        allSkills     = skillsRes.ok ? await skillsRes.json() : [];
        allGroupViews = groupsRes.ok ? await groupsRes.json() : [];
        allGroups     = allGroupViews.map(function (entry) { return entry.group || entry; });
        allTools      = toolsRes.ok  ? await toolsRes.json()  : [];
        allTypes      = typesRes.ok  ? await typesRes.json()  : [];
        allCategories = catsRes.ok   ? await catsRes.json()   : [];
        categoriesLoadFailed = !catsRes.ok;
        updateCategoryHelp();
        renderGroupTable();
    } catch (e) {
        categoriesLoadFailed = true;
        updateCategoryHelp();
        showAlert('Failed to load data.', 'warning');
    }
}

function updateCategoryHelp() {
    const help = document.getElementById('group-category-help');
    if (!help) return;

    if (categoriesLoadFailed) {
        help.className = 'text-xs text-amber-300';
        help.textContent = 'Supported categories could not be loaded from GitHub. Retry later.';
        return;
    }

    if (!allCategories || allCategories.length === 0) {
        help.className = 'text-xs text-amber-300';
        help.textContent = 'No supported categories are available right now.';
        return;
    }

    help.className = 'text-xs text-zinc-500';
    help.textContent = 'Category must be selected from the supported list.';
}

function renderGroupTable() {
    const table = document.getElementById('group-table');
    const body = document.getElementById('group-table-body');
    const empty = document.getElementById('no-groups');

    if (!table || !body || !empty) return;

    body.innerHTML = '';
    if (!allGroupViews || allGroupViews.length === 0) {
        table.classList.add('hidden');
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    table.classList.remove('hidden');

    allGroupViews.forEach(function (entry) {
        const group = entry.group || entry;
        const skills = entry.skills || allSkills.filter(function (s) { return s.groupUuid === group.uuid; });

        const tr = document.createElement('tr');
        tr.id = 'group-row-' + group.uuid;
        tr.className = 'border-b border-zinc-800/80 last:border-0';

        const pills = skills.length === 0
            ? '<span class="text-xs text-zinc-500">No skills</span>'
            : skills.map(function (s) {
                const isPrivate = (s.visibility || 'PUBLIC') === 'PRIVATE';
                const visibilityIcon = isPrivate
                    ? ' <i class="fa-solid fa-lock text-warning" title="Private skill"></i>'
                    : ' <i class="fa-solid fa-globe text-info" title="Public skill"></i>';
                return '<span class="skill-pill mr-1 mb-1">'
                    + '<span>' + escapeHtml(s.name) + visibilityIcon + '</span>'
                    + '<span class="remove-skill" title="Edit skill" onclick="openEdit(\'' + escapeHtml(s.uuid) + '\')"><i class="fa-solid fa-pen"></i></span>'
                    + '<span class="remove-skill text-danger" title="Delete skill" onclick="deleteSkill(\'' + escapeHtml(s.uuid) + '\')"><i class="fa-solid fa-trash"></i></span>'
                    + '</span>';
            }).join('');

        tr.innerHTML = ''
            + '<td class="px-3 py-2 font-semibold text-zinc-100">' + escapeHtml(group.name || '') + '</td>'
            + '<td class="px-3 py-2"><span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-2 py-0.5 text-xs text-zinc-400">' + escapeHtml(group.category || '—') + '</span></td>'
            + '<td class="px-3 py-2">' + pills + '</td>'
            + '<td class="px-3 py-2 text-xs text-zinc-400">' + escapeHtml(group.author || '—') + '</td>'
            + '<td class="px-3 py-2 text-right">'
            + '  <div class="inline-flex gap-1 justify-end">'
            + '    <button class="rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800" onclick="openEditGroup(\'' + escapeHtml(group.uuid) + '\')" title="Edit group"><i class="fa-solid fa-pen"></i></button>'
            + '    <button class="rounded-md border border-cyan-500/40 px-2 py-1 text-xs text-cyan-300 transition-colors hover:bg-cyan-500/15" onclick="exportGroup(\'' + escapeHtml(group.uuid) + '\')" title="Export group"><i class="fa-solid fa-file-export"></i></button>'
            + '    <button class="rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" onclick="deleteGroup(\'' + escapeHtml(group.uuid) + '\')" title="Delete group"><i class="fa-solid fa-trash"></i></button>'
            + '  </div>'
            + '</td>';

        body.appendChild(tr);
    });
}

function populateGroupSelect(selected) {
    const sel = document.getElementById('skill-group');
    sel.innerHTML = '<option value="">- select a group -</option>';
    allGroups.forEach(function (entry) {
        const group = entry.group || entry;
        const opt = document.createElement('option');
        opt.value = group.uuid;
        opt.textContent = group.name + (group.category ? ' [' + group.category + ']' : '');
        if (group.uuid === selected) opt.selected = true;
        sel.appendChild(opt);
    });
}

function populateCategorySelect(selected) {
    const sel = document.getElementById('group-category');
    if (!sel) return;

    sel.innerHTML = '<option value="">— select a category —</option>';
    allCategories.forEach(function (category) {
        const opt = document.createElement('option');
        opt.value = category;
        opt.textContent = category;
        if (category === selected) opt.selected = true;
        sel.appendChild(opt);
    });
}

// ── Open modal ────────────────────────────────────────────────────────────────
function openCreate() {
    if (!allGroups || allGroups.length === 0) {
        showAlert('Create a group first before creating skills.', 'warning');
        return;
    }
    document.getElementById('skillModalLabel').textContent      = 'New Skill';
    document.getElementById('skill-id').value                   = '';
    document.getElementById('skill-name').value                 = '';
    document.getElementById('skill-description').value          = '';
    document.getElementById('skill-instructions').value         = '';
    document.getElementById('skill-visibility').value           = 'PUBLIC';
    document.getElementById('btn-delete-skill').classList.add('hidden');
    populateGroupSelect('');
    modalTools      = [];
    modalTypes      = [];
    modalSubSkills  = [];
    modalParams     = [];
    modalSecrets    = [];
    clearAlert('skill-modal-alert');
    renderToolPills();
    renderTypePills();
    renderSubSkillPills();
    renderParams();
    renderSecrets();
    skillModal.show();
}

function openEdit(id) {
    const skill = allSkills.find(function (s) { return s.uuid === id; });
    if (!skill) { showAlert('Skill not found — reload the page.', 'warning'); return; }

    document.getElementById('skillModalLabel').textContent      = 'Edit Skill: ' + skill.name;
    document.getElementById('skill-id').value                   = skill.uuid;
    document.getElementById('skill-name').value                 = skill.name;
    document.getElementById('skill-description').value          = skill.description || '';
    document.getElementById('skill-instructions').value         = skill.instructions || '';
    populateGroupSelect(skill.groupUuid || '');
    document.getElementById('skill-visibility').value           = skill.visibility || 'PUBLIC';
    document.getElementById('btn-delete-skill').classList.remove('hidden');
    modalTools      = skill.allowedTools  ? skill.allowedTools.slice()  : [];
    modalTypes      = skill.allowedTypes  ? skill.allowedTypes.slice()  : [];
    modalSubSkills  = skill.subSkillUuids ? skill.subSkillUuids.slice() : [];
    modalParams     = skill.parameters    ? skill.parameters.map(function (p) {
        let inputMode = p.inputMode || 'AI_REQUIRED';
        if (!p.inputMode && p.forceUserInput === true) inputMode = 'USER_ALWAYS_PROMPT';
        if (!p.inputMode && p.forceUserInput === false) inputMode = 'AI_REQUIRED';
        const normalizedType = (p.type || 'string').toLowerCase() === 'secret' ? 'string' : (p.type || 'string');
        return {
            name: p.name || '',
            type: normalizedType,
            description: p.description || '',
            inputMode: inputMode
        };
    }) : [];
    modalSecrets = skill.secrets ? skill.secrets.map(function (s) {
        return {
            name: s.name || '',
            description: s.description || ''
        };
    }) : [];
    clearAlert('skill-modal-alert');
    renderToolPills();
    renderTypePills();
    renderSubSkillPills();
    renderParams();
    renderSecrets();
    skillModal.show();
}

// ── Parameter rows ────────────────────────────────────────────────────────────
function addParam() {
    modalParams.push({ name: '', type: 'string', description: '', inputMode: 'AI_REQUIRED' });
    renderParams();
    // Focus the name field of the new row
    const list = document.getElementById('param-list');
    const last = list.querySelector('.param-row:last-child .param-name');
    if (last) last.focus();
}

function removeParam(idx) {
    modalParams.splice(idx, 1);
    renderParams();
}

function renderParams() {
    const list = document.getElementById('param-list');
    list.innerHTML = '';
    if (modalParams.length === 0) {
        list.innerHTML = '<p class="mb-0 text-xs text-zinc-500">No parameters defined. The skill will receive no structured input.</p>';
        return;
    }

    // Header
    const hdr = document.createElement('div');
    hdr.className = 'param-row mb-1';
    hdr.innerHTML =
        '<span class="param-name text-xs text-zinc-500">Name</span>' +
        '<span class="param-type text-xs text-zinc-500">Type</span>' +
        '<span class="param-input-mode text-xs text-zinc-500">Input</span>' +
        '<span class="param-desc text-xs text-zinc-500">Description (optional)</span>' +
        '<span class="inline-block w-8"></span>';
    list.appendChild(hdr);

    modalParams.forEach(function (p, idx) {
        const row = document.createElement('div');
        row.className = 'param-row';

        const typeOptions = PARAM_TYPES.map(function (t) {
            return '<option value="' + t + '"' + (p.type === t ? ' selected' : '') + '>' + t + '</option>';
        }).join('');

        const forceOptions = [
            { value: 'USER_ALWAYS_PROMPT', label: 'User Input: Always Prompt' },
            { value: 'USER_PROMPT_IF_EMPTY', label: 'User Input: Prompt if Empty' },
            { value: 'AI_REQUIRED', label: 'AI Input: Required' },
            { value: 'AI_OPTIONAL', label: 'AI Input: Optional' }
        ].map(function (entry) {
            const selected = (p.inputMode || 'AI_REQUIRED') === entry.value ? ' selected' : '';
            return '<option value="' + entry.value + '"' + selected + '>' + entry.label + '</option>';
        }).join('');

        row.innerHTML =
            '<input type="text" class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 param-name" ' +
                   'placeholder="paramName" value="' + escapeHtml(p.name) + '" ' +
                   'data-idx="' + idx + '" oninput="updateParam(this)">' +
            '<select class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 param-type" ' +
                    'data-idx="' + idx + '" onchange="updateParam(this)">' +
            typeOptions +
            '</select>' +
                '<select class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 param-input-mode" ' +
                    'data-idx="' + idx + '" onchange="updateParam(this)">' +
                forceOptions +
                '</select>' +
            '<input type="text" class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 param-desc" ' +
                   'placeholder="Brief description…" value="' + escapeHtml(p.description) + '" ' +
                   'data-idx="' + idx + '" oninput="updateParam(this)">' +
            '<button type="button" class="btn-remove-param rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" ' +
                    'onclick="removeParam(' + idx + ')" title="Remove parameter">' +
            '  <i class="fa-solid fa-xmark"></i>' +
            '</button>';

        list.appendChild(row);
    });
}

function updateParam(el) {
    const idx = parseInt(el.dataset.idx, 10);
    if (el.classList.contains('param-name'))  modalParams[idx].name        = el.value;
    if (el.classList.contains('param-type'))  modalParams[idx].type        = el.value;
    if (el.classList.contains('param-input-mode'))  modalParams[idx].inputMode = el.value;
    if (el.classList.contains('param-desc'))  modalParams[idx].description = el.value;
}

// ── Skill secrets rows ───────────────────────────────────────────────────────
function addSecret() {
    modalSecrets.push({ name: '', description: '' });
    renderSecrets();
    const list = document.getElementById('secret-list');
    const last = list.querySelector('.secret-row:last-child .secret-name');
    if (last) last.focus();
}

function removeSecret(idx) {
    modalSecrets.splice(idx, 1);
    renderSecrets();
}

function renderSecrets() {
    const list = document.getElementById('secret-list');
    if (!list) return;

    list.innerHTML = '';
    if (modalSecrets.length === 0) {
        list.innerHTML = '<p class="mb-0 text-xs text-zinc-500">No secrets defined. Add names to enable secure {{SECRET_NAME}} placeholder substitution.</p>';
        return;
    }

    const hdr = document.createElement('div');
    hdr.className = 'secret-row mb-1';
    hdr.innerHTML =
        '<span class="secret-name text-xs text-zinc-500">Name</span>' +
        '<span class="secret-desc text-xs text-zinc-500">Description (optional)</span>' +
        '<span class="inline-block w-8"></span>';
    list.appendChild(hdr);

    modalSecrets.forEach(function (s, idx) {
        const row = document.createElement('div');
        row.className = 'secret-row';
        row.innerHTML =
             '<input type="text" class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 secret-name" ' +
                   'placeholder="API_KEY" value="' + escapeHtml(s.name) + '" ' +
                   'data-idx="' + idx + '" oninput="updateSecret(this)">' +
             '<input type="text" class="w-full rounded-md border border-zinc-700 bg-zinc-950 px-2 py-1 text-xs text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/20 secret-desc" ' +
                   'placeholder="What this secret is used for…" value="' + escapeHtml(s.description) + '" ' +
                   'data-idx="' + idx + '" oninput="updateSecret(this)">' +
             '<button type="button" class="btn-remove-secret rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15" ' +
                    'onclick="removeSecret(' + idx + ')" title="Remove secret">' +
            '  <i class="fa-solid fa-xmark"></i>' +
            '</button>';

        list.appendChild(row);
    });
}

function updateSecret(el) {
    const idx = parseInt(el.dataset.idx, 10);
    if (el.classList.contains('secret-name')) modalSecrets[idx].name = el.value;
    if (el.classList.contains('secret-desc')) modalSecrets[idx].description = el.value;
}

// ── Tool pills ────────────────────────────────────────────────────────────────
function renderToolPills() {
    const container = document.getElementById('tool-pill-container');
    container.innerHTML = '';
    if (modalTools.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No tools assigned — skill will run with no external tools.</span>';
        return;
    }
    modalTools.forEach(function (toolId) {
        const desc  = allTools.find(function (t) { return t.id === toolId; });
        const label = desc ? (desc.friendlyName || desc.name || toolId) : toolId;
        const pill  = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML =
            '<i class="fa-solid fa-screwdriver-wrench"></i>' +
            '<span>' + escapeHtml(label) + '</span>' +
            '<span class="remove-tool" title="Remove tool">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () { removeTool(toolId); });
        container.appendChild(pill);
    });
}

function removeTool(toolId) {
    modalTools = modalTools.filter(function (t) { return t !== toolId; });
    renderToolPills();
    filterTools();
}

function filterTools() {
    const query    = document.getElementById('tool-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('tool-dropdown');
    const list     = document.getElementById('tool-list');

    const matches = allTools.filter(function (t) {
        if (modalTools.includes(t.id)) return false;
        if (!query) return true;
        return ((t.friendlyName || '') + ' ' + (t.name || '') + ' ' + (t.id || '') + ' ' + (t.category || '')).toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) { dropdown.classList.add('hidden'); return; }
    matches.forEach(function (t) {
        const li = document.createElement('li');
        li.className = 'tool-list-item cursor-pointer px-2 py-1.5 hover:bg-zinc-800';
        li.innerHTML =
            '<div class="flex items-center gap-2">' +
            '  <i class="fa-solid fa-screwdriver-wrench fa-xs text-zinc-400"></i>' +
            '  <span class="text-xs font-semibold text-zinc-100">' + escapeHtml(t.friendlyName || t.name || t.id) + '</span>' +
            (t.category ? '  <span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[0.65rem] text-zinc-400">' + escapeHtml(t.category) + '</span>' : '') +
            '</div>' +
            (t.description ? '<div class="text-[0.7rem] text-zinc-400">' + escapeHtml(t.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addTool(t.id);
            document.getElementById('tool-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });
    dropdown.classList.remove('hidden');
}

function addTool(toolId) {
    if (!modalTools.includes(toolId)) { modalTools.push(toolId); renderToolPills(); }
}

// ── Type pills ────────────────────────────────────────────────────────────────
function renderTypePills() {
    const container = document.getElementById('type-pill-container');
    container.innerHTML = '';
    if (modalTypes.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No types assigned.</span>';
        return;
    }
    modalTypes.forEach(function (fqn) {
        const pill = document.createElement('span');
        pill.className = 'skill-pill';
        pill.innerHTML =
            '<i class="fa-solid fa-cube"></i>' +
            '<span>' + escapeHtml(fqn) + '</span>' +
            '<span class="remove-skill" title="Remove type">✕</span>';
        pill.querySelector('.remove-skill').addEventListener('click', function () { removeType(fqn); });
        container.appendChild(pill);
    });
}

function removeType(fqn) {
    modalTypes = modalTypes.filter(function (t) { return t !== fqn; });
    renderTypePills();
    filterTypes();
}

function filterTypes() {
    const query    = document.getElementById('type-search').value.toLowerCase().trim();
    const dropdown = document.getElementById('type-dropdown');
    const list     = document.getElementById('type-list');

    const matches = allTypes.filter(function (t) {
        const fqn = typeof t === 'string' ? t : (t.fqn || t.name || '');
        if (modalTypes.includes(fqn)) return false;
        if (!query) return true;
        return fqn.toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) { dropdown.classList.add('hidden'); return; }
    matches.forEach(function (t) {
        const fqn = typeof t === 'string' ? t : (t.fqn || t.name || '');
        const li  = document.createElement('li');
        li.className = 'skill-list-item cursor-pointer px-2 py-1.5 hover:bg-zinc-800';
        li.innerHTML =
            '<div class="flex items-center gap-2">' +
            '  <i class="fa-solid fa-cube fa-xs text-zinc-400"></i>' +
            '  <span class="text-xs text-zinc-200">' + escapeHtml(fqn) + '</span>' +
            '</div>';
        li.addEventListener('click', function () {
            addType(fqn);
            document.getElementById('type-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });
    dropdown.classList.remove('hidden');
}

function addType(fqn) {
    if (!modalTypes.includes(fqn)) { modalTypes.push(fqn); renderTypePills(); }
}

// ── Sub-skill pills ───────────────────────────────────────────────────────────
function renderSubSkillPills() {
    const container = document.getElementById('subskill-pill-container');
    container.innerHTML = '';
    if (modalSubSkills.length === 0) {
        container.innerHTML = '<span class="text-xs text-zinc-500">No sub-skills assigned.</span>';
        return;
    }
    modalSubSkills.forEach(function (uuid) {
        const skill = allSkills.find(function (s) { return s.uuid === uuid; });
        const label = skill ? skill.name : uuid;
        const pill  = document.createElement('span');
        pill.className = 'tool-pill';
        pill.innerHTML =
            '<i class="fa-solid fa-bolt"></i>' +
            '<span>' + escapeHtml(label) + '</span>' +
            '<span class="remove-tool" title="Remove sub-skill">✕</span>';
        pill.querySelector('.remove-tool').addEventListener('click', function () { removeSubSkill(uuid); });
        container.appendChild(pill);
    });
}

function removeSubSkill(uuid) {
    modalSubSkills = modalSubSkills.filter(function (s) { return s !== uuid; });
    renderSubSkillPills();
    filterSubSkills();
}

function filterSubSkills() {
    const currentId = document.getElementById('skill-id').value.trim();
    const query     = document.getElementById('subskill-search').value.toLowerCase().trim();
    const dropdown  = document.getElementById('subskill-dropdown');
    const list      = document.getElementById('subskill-list');

    const matches = allSkills.filter(function (s) {
        if (s.uuid === currentId) return false;          // exclude self
        if (modalSubSkills.includes(s.uuid)) return false;
        if (!query) return true;
        return (s.name + ' ' + (s.description || '') + ' ' + (s.groupUuid || '')).toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) { dropdown.classList.add('hidden'); return; }
    matches.forEach(function (s) {
        const li = document.createElement('li');
        li.className = 'skill-list-item cursor-pointer px-2 py-1.5 hover:bg-zinc-800';
        li.innerHTML =
            '<div class="flex items-center gap-2">' +
            '  <i class="fa-solid fa-bolt fa-xs text-zinc-400"></i>' +
            '  <span class="text-xs font-semibold text-zinc-100">' + escapeHtml(s.name) + '</span>' +
            (s.groupUuid ? '  <span class="inline-flex rounded-md border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[0.65rem] text-zinc-400">' + escapeHtml(resolveGroupName(s.groupUuid)) + '</span>' : '') +
            '</div>' +
            (s.description ? '<div class="text-[0.7rem] text-zinc-400">' + escapeHtml(s.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addSubSkill(s.uuid);
            document.getElementById('subskill-search').value = '';
            dropdown.classList.add('hidden');
        });
        list.appendChild(li);
    });
    dropdown.classList.remove('hidden');
}

function addSubSkill(uuid) {
    if (!modalSubSkills.includes(uuid)) { modalSubSkills.push(uuid); renderSubSkillPills(); }
}

// ── Save ──────────────────────────────────────────────────────────────────────
async function saveSkill() {
    const id             = document.getElementById('skill-id').value.trim();
    const name           = document.getElementById('skill-name').value.trim();
    const groupUuid      = document.getElementById('skill-group').value;
    const visibility     = document.getElementById('skill-visibility').value || 'PUBLIC';
    const description    = document.getElementById('skill-description').value;
    const instructions   = document.getElementById('skill-instructions').value;

    if (!name) { showAlertIn('skill-modal-alert', 'Name is required.', 'warning'); return; }
    if (!groupUuid) { showAlertIn('skill-modal-alert', 'Skill group is required.', 'warning'); return; }

    // Validate parameters: names must be non-empty
    for (let i = 0; i < modalParams.length; i++) {
        if (!modalParams[i].name || !modalParams[i].name.trim()) {
            showAlertIn('skill-modal-alert', 'All parameters must have a name (row ' + (i + 1) + ').', 'warning');
            return;
        }
    }

    const secretPattern = /^[A-Z][A-Z0-9_]*$/;
    const seenSecretNames = new Set();
    for (let i = 0; i < modalSecrets.length; i++) {
        const name = (modalSecrets[i].name || '').trim();
        if (!name) {
            showAlertIn('skill-modal-alert', 'All secrets must have a name (row ' + (i + 1) + ').', 'warning');
            return;
        }
        if (!secretPattern.test(name)) {
            showAlertIn('skill-modal-alert', 'Secret names must be UPPER_SNAKE_CASE (row ' + (i + 1) + ').', 'warning');
            return;
        }
        if (seenSecretNames.has(name)) {
            showAlertIn('skill-modal-alert', 'Secret names must be unique (' + name + ').', 'warning');
            return;
        }
        seenSecretNames.add(name);
    }

    const body = {
        name:           name,
        description:    description,
        groupUuid:      groupUuid,
        visibility:     visibility,
        parameters:     modalParams.map(function (p) {
            return {
                name: p.name.trim(),
                type: p.type,
                description: p.description,
                inputMode: p.inputMode || 'AI_REQUIRED'
            };
        }),
        instructions:   instructions,
        allowedTools:   modalTools.slice(),
        allowedTypes:   modalTypes.slice(),
        subSkillUuids:  modalSubSkills.slice(),
        secrets:        modalSecrets.map(function (s) {
            return {
                name: (s.name || '').trim(),
                description: s.description || ''
            };
        })
    };

    const btn = document.getElementById('btn-save-skill');
    btn.disabled = true;
    btn.innerHTML = '<span class="mr-1 inline-block h-3 w-3 animate-spin rounded-full border border-current border-t-transparent align-[-0.1em]"></span>Saving...';

    try {
        const url    = id ? '/api/skills/' + id : '/api/skills';
        const method = id ? 'PUT' : 'POST';
        const res    = await fetch(url, {
            method:  method,
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) { showAlertIn('skill-modal-alert', data.error, 'danger'); return; }
        skillModal.hide();
        showAlert(id ? 'Skill updated.' : 'Skill created.', 'success');
        setTimeout(function () { location.reload(); }, 800);
    } catch (e) {
        showAlertIn('skill-modal-alert', 'Network error saving skill.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save mr-1"></i>Save Skill';
    }
}

// ── Export ────────────────────────────────────────────────────────────────────
function exportSkill(id) {
    const skill = allSkills.find(function (s) { return s.uuid === id; });
    if (!skill || !skill.groupUuid) {
        showAlert('Group for this skill could not be resolved.', 'warning');
        return;
    }
    window.location.href = '/api/skill-groups/' + skill.groupUuid + '/export';
}

function exportGroup(groupUuid) {
    window.location.href = '/api/skill-groups/' + groupUuid + '/export';
}

// ── Import ────────────────────────────────────────────────────────────────────
async function importSkill(input) {
    const file = input.files[0];
    if (!file) return;
    input.value = ''; // reset so the same file can be re-selected if needed

    let pkg;
    try {
        pkg = JSON.parse(await file.text());
    } catch (e) {
        const detail = (e && e.message) ? (': ' + e.message) : '.';
        showAlert('Could not parse file — not valid JSON' + detail, 'danger');
        return;
    }

    if (!pkg.vorkSkillGroupExport || !pkg.group || !pkg.group.uuid || !Array.isArray(pkg.group.skills) || pkg.group.skills.length === 0) {
        showAlert('Not a valid Vork skill-group export file.', 'danger');
        return;
    }

    try {
        const res  = await fetch('/api/skill-groups/import', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(pkg)
        });
        const data = await res.json();
        if (data.status === 'already_installed') {
            showAlert(
                'Group "' + escapeHtml(pkg.group.name) + '" is already installed (UUID: ' + escapeHtml(pkg.group.uuid) + ').',
                'warning'
            );
        } else if (data.status === 'missing_dependencies') {
            showAlert('Import blocked: missing dependencies: ' + escapeHtml((data.missingDependencies || []).join(', ')), 'danger');
        } else if (data.status === 'imported') {
            const extra = data.message ? ' — ' + data.message : '';
            showAlert('Group "' + escapeHtml(pkg.group.name) + '" imported successfully.' + extra, 'success');
            setTimeout(function () { location.reload(); }, 900);
        } else {
            const detail = data.detail ? (' (' + data.detail + ')') : '';
            showAlert('Import failed: ' + escapeHtml((data.message || 'Unknown error') + detail), 'danger');
        }
    } catch (e) {
        showAlert('Network error during import.', 'danger');
    }
}

// ── Delete ────────────────────────────────────────────────────────────────────
async function deleteSkill(id) {
    if (!confirm('Delete this skill? This cannot be undone.')) return;
    try {
        const res = await fetch('/api/skills/' + id, { method: 'DELETE' });
        let data = {};
        try {
            data = await res.json();
        } catch (_ignored) {
            data = {};
        }
        if (!res.ok) {
            showAlert(data.error || 'Failed to delete skill.', 'danger');
            return;
        }
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Skill deleted.', 'success');
        setTimeout(function () { location.reload(); }, 600);
    } catch (e) {
        showAlert('Network error deleting skill.', 'danger');
    }
}

function deleteCurrentSkillFromModal() {
    const id = document.getElementById('skill-id').value.trim();
    if (!id) {
        showAlertIn('skill-modal-alert', 'Only saved skills can be deleted.', 'warning');
        return;
    }
    skillModal.hide();
    deleteSkill(id);
}

// ── Group CRUD ───────────────────────────────────────────────────────────────
function openCreateGroup() {
    document.getElementById('groupModalLabel').textContent = 'New Group';
    document.getElementById('group-id').value = '';
    document.getElementById('group-name').value = '';
    document.getElementById('group-author').value = '';
    populateCategorySelect('');
    clearAlert('group-modal-alert');
    groupModal.show();
}

function openEditGroup(groupUuid) {
    const group = allGroups.find(function (g) { return g.uuid === groupUuid; });
    if (!group) {
        showAlert('Group not found.', 'warning');
        return;
    }
    document.getElementById('groupModalLabel').textContent = 'Edit Group: ' + group.name;
    document.getElementById('group-id').value = group.uuid;
    document.getElementById('group-name').value = group.name || '';
    document.getElementById('group-author').value = group.author || '';
    populateCategorySelect(group.category || '');
    clearAlert('group-modal-alert');
    groupModal.show();
}

async function saveGroup() {
    const id = document.getElementById('group-id').value.trim();
    const name = document.getElementById('group-name').value.trim();
    const author = document.getElementById('group-author').value.trim();
    const category = document.getElementById('group-category').value.trim();

    if (!name) {
        showAlertIn('group-modal-alert', 'Group name is required.', 'warning');
        return;
    }

    if (!category) {
        showAlertIn('group-modal-alert', 'Category is required. Please select from supported categories.', 'warning');
        return;
    }

    if (!allCategories.includes(category)) {
        showAlertIn('group-modal-alert', 'Unsupported category selected. Please reload and pick a supported category.', 'warning');
        return;
    }

    const btn = document.getElementById('btn-save-group');
    btn.disabled = true;

    try {
        const url = id ? '/api/skill-groups/' + id : '/api/skill-groups';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, author: author, category: category })
        });
        const data = await res.json();
        if (data.error) {
            showAlertIn('group-modal-alert', data.error, 'danger');
            return;
        }
        groupModal.hide();
        showAlert(id ? 'Group updated.' : 'Group created.', 'success');
        setTimeout(function () { location.reload(); }, 600);
    } catch (e) {
        showAlertIn('group-modal-alert', 'Network error saving group.', 'danger');
    } finally {
        btn.disabled = false;
    }
}

async function deleteGroup(groupUuid) {
    if (!confirm('Delete this group? Only empty groups can be deleted.')) return;
    try {
        const res = await fetch('/api/skill-groups/' + groupUuid, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        showAlert('Group deleted.', 'success');
        setTimeout(function () { location.reload(); }, 600);
    } catch (e) {
        showAlert('Network error deleting group.', 'danger');
    }
}

function resolveGroupName(groupUuid) {
    const group = allGroups.find(function (g) { return g.uuid === groupUuid; });
    return group ? group.name : groupUuid;
}

// ── Alert helper ──────────────────────────────────────────────────────────────
function showAlert(msg, type) {
    const area = document.getElementById('alert-area');
    if (!area) return;
    const tones = {
        success: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
        warning: 'border-amber-700/60 bg-amber-950/40 text-amber-300',
        danger: 'border-rose-700/60 bg-rose-950/40 text-rose-300',
        info: 'border-cyan-700/60 bg-cyan-950/40 text-cyan-300'
    };
    const tone = tones[type] || tones.info;
    area.innerHTML =
        '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
        '<div>' + escapeHtml(msg) + '</div>' +
        '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>' +
        '</div>';
    const closeBtn = area.querySelector('button[aria-label="Dismiss alert"]');
    if (closeBtn) {
        closeBtn.addEventListener('click', function () {
            area.innerHTML = '';
        });
    }
}

function showAlertIn(areaId, msg, type) {
    const area = document.getElementById(areaId);
    if (!area) {
        showAlert(msg, type);
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
        '<div class="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm ' + tone + '" role="alert">' +
        '<div>' + escapeHtml(msg) + '</div>' +
        '<button type="button" class="shrink-0 rounded-md border border-current/35 px-2 py-0.5 text-xs" aria-label="Dismiss alert">Close</button>' +
        '</div>';
    const closeBtn = area.querySelector('button[aria-label="Dismiss alert"]');
    if (closeBtn) {
        closeBtn.addEventListener('click', function () {
            area.innerHTML = '';
        });
    }
}

function clearAlert(areaId) {
    const area = document.getElementById(areaId);
    if (area) {
        area.innerHTML = '';
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
