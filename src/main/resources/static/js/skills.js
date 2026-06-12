/* skills.js — Vork Skills management page */
/* jshint esversion: 6 */

const PARAM_TYPES = ['string', 'int', 'double', 'boolean', 'secret'];

let skillModal;
let allSkills     = [];
let allTools      = [];
let allTypes      = [];
let allCategories = [];
let modalTools      = [];
let modalTypes      = [];
let modalSubSkills  = [];
let modalParams     = []; // [{name, type, description}]

// ── Init ──────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', function () {
    skillModal = new VorkModal(document.getElementById('skillModal'));
    loadData();

    document.getElementById('skillModal').addEventListener('hidden.bs.modal', function () {
        document.getElementById('tool-search').value        = '';
        document.getElementById('type-search').value        = '';
        document.getElementById('subskill-search').value    = '';
        document.getElementById('tool-dropdown').style.display     = 'none';
        document.getElementById('type-dropdown').style.display     = 'none';
        document.getElementById('subskill-dropdown').style.display = 'none';
    });
    document.addEventListener('click', function (e) {
        if (!e.target.closest('#tool-search') && !e.target.closest('#tool-dropdown')) {
            document.getElementById('tool-dropdown').style.display = 'none';
        }
        if (!e.target.closest('#type-search') && !e.target.closest('#type-dropdown')) {
            document.getElementById('type-dropdown').style.display = 'none';
        }
        if (!e.target.closest('#subskill-search') && !e.target.closest('#subskill-dropdown')) {
            document.getElementById('subskill-dropdown').style.display = 'none';
        }
    });
});

async function loadData() {
    try {
        const [skillsRes, toolsRes, typesRes, catsRes] = await Promise.all([
            fetch('/api/skills'),
            fetch('/api/management/tools'),
            fetch('/api/types/java-types'),
            fetch('/api/skills/categories')
        ]);
        allSkills     = skillsRes.ok ? await skillsRes.json() : [];
        allTools      = toolsRes.ok  ? await toolsRes.json()  : [];
        allTypes      = typesRes.ok  ? await typesRes.json()  : [];
        allCategories = catsRes.ok   ? await catsRes.json()   : [];
    } catch (e) {
        showAlert('Failed to load data.', 'warning');
    }
}

// ── Category select ───────────────────────────────────────────────────────────
function populateCategorySelect(selected) {
    const sel = document.getElementById('skill-category');
    sel.innerHTML = '<option value="">— select a category —</option>';
    allCategories.forEach(function (cat) {
        const opt = document.createElement('option');
        opt.value = cat;
        opt.textContent = cat;
        if (cat === selected) opt.selected = true;
        sel.appendChild(opt);
    });
    // If the stored category is not in the fetched list, add it as a disabled placeholder
    if (selected && !allCategories.includes(selected)) {
        const opt = document.createElement('option');
        opt.value = selected;
        opt.textContent = selected + ' (unknown)';
        opt.selected = true;
        sel.appendChild(opt);
    }
}

// ── Open modal ────────────────────────────────────────────────────────────────
function openCreate() {
    document.getElementById('skillModalLabel').textContent      = 'New Skill';
    document.getElementById('skill-id').value                   = '';
    document.getElementById('skill-name').value                 = '';
    document.getElementById('skill-author').value               = '';
    document.getElementById('skill-description').value          = '';
    document.getElementById('skill-instructions').value         = '';
    populateCategorySelect('');
    modalTools      = [];
    modalTypes      = [];
    modalSubSkills  = [];
    modalParams     = [];
    renderToolPills();
    renderTypePills();
    renderSubSkillPills();
    renderParams();
    skillModal.show();
}

function openEdit(id) {
    const skill = allSkills.find(function (s) { return s.uuid === id; });
    if (!skill) { showAlert('Skill not found — reload the page.', 'warning'); return; }

    document.getElementById('skillModalLabel').textContent      = 'Edit Skill: ' + skill.name;
    document.getElementById('skill-id').value                   = skill.uuid;
    document.getElementById('skill-name').value                 = skill.name;
    document.getElementById('skill-author').value               = skill.author || '';
    document.getElementById('skill-description').value          = skill.description || '';
    document.getElementById('skill-instructions').value         = skill.instructions || '';
    populateCategorySelect(skill.category || '');
    modalTools      = skill.allowedTools  ? skill.allowedTools.slice()  : [];
    modalTypes      = skill.allowedTypes  ? skill.allowedTypes.slice()  : [];
    modalSubSkills  = skill.subSkillUuids ? skill.subSkillUuids.slice() : [];
    modalParams     = skill.parameters    ? skill.parameters.map(function (p) {
        return { name: p.name || '', type: p.type || 'string', description: p.description || '' };
    }) : [];
    renderToolPills();
    renderTypePills();
    renderSubSkillPills();
    renderParams();
    skillModal.show();
}

// ── Parameter rows ────────────────────────────────────────────────────────────
function addParam() {
    modalParams.push({ name: '', type: 'string', description: '' });
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
        list.innerHTML = '<p class="text-muted small mb-0">No parameters defined. The skill will receive no structured input.</p>';
        return;
    }

    // Header
    const hdr = document.createElement('div');
    hdr.className = 'param-row mb-1';
    hdr.innerHTML =
        '<span class="param-name text-muted small">Name</span>' +
        '<span class="param-type text-muted small">Type</span>' +
        '<span class="param-desc text-muted small">Description (optional)</span>' +
        '<span style="width:32px"></span>';
    list.appendChild(hdr);

    modalParams.forEach(function (p, idx) {
        const row = document.createElement('div');
        row.className = 'param-row';

        const typeOptions = PARAM_TYPES.map(function (t) {
            return '<option value="' + t + '"' + (p.type === t ? ' selected' : '') + '>' + t + '</option>';
        }).join('');

        row.innerHTML =
            '<input type="text" class="form-control form-control-sm param-name" ' +
                   'placeholder="paramName" value="' + escapeHtml(p.name) + '" ' +
                   'data-idx="' + idx + '" oninput="updateParam(this)">' +
            '<select class="form-select form-select-sm param-type" ' +
                    'data-idx="' + idx + '" onchange="updateParam(this)">' +
            typeOptions +
            '</select>' +
            '<input type="text" class="form-control form-control-sm param-desc" ' +
                   'placeholder="Brief description…" value="' + escapeHtml(p.description) + '" ' +
                   'data-idx="' + idx + '" oninput="updateParam(this)">' +
            '<button type="button" class="btn btn-sm btn-outline-danger btn-remove-param" ' +
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
    if (el.classList.contains('param-desc'))  modalParams[idx].description = el.value;
}

// ── Tool pills ────────────────────────────────────────────────────────────────
function renderToolPills() {
    const container = document.getElementById('tool-pill-container');
    container.innerHTML = '';
    if (modalTools.length === 0) {
        container.innerHTML = '<span class="text-muted small">No tools assigned — skill will run with no external tools.</span>';
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
    if (matches.length === 0) { dropdown.style.display = 'none'; return; }
    matches.forEach(function (t) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action tool-list-item py-1 px-2';
        li.innerHTML =
            '<div class="d-flex align-items-center gap-2">' +
            '  <i class="fa-solid fa-screwdriver-wrench fa-xs text-secondary"></i>' +
            '  <span class="fw-semibold small">' + escapeHtml(t.friendlyName || t.name || t.id) + '</span>' +
            (t.category ? '  <span class="badge bg-dark border border-secondary text-secondary" style="font-size:0.65rem">' + escapeHtml(t.category) + '</span>' : '') +
            '</div>' +
            (t.description ? '<div class="text-muted" style="font-size:0.7rem">' + escapeHtml(t.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addTool(t.id);
            document.getElementById('tool-search').value = '';
            dropdown.style.display = 'none';
        });
        list.appendChild(li);
    });
    dropdown.style.display = '';
}

function addTool(toolId) {
    if (!modalTools.includes(toolId)) { modalTools.push(toolId); renderToolPills(); }
}

// ── Type pills ────────────────────────────────────────────────────────────────
function renderTypePills() {
    const container = document.getElementById('type-pill-container');
    container.innerHTML = '';
    if (modalTypes.length === 0) {
        container.innerHTML = '<span class="text-muted small">No types assigned.</span>';
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
    if (matches.length === 0) { dropdown.style.display = 'none'; return; }
    matches.forEach(function (t) {
        const fqn = typeof t === 'string' ? t : (t.fqn || t.name || '');
        const li  = document.createElement('li');
        li.className = 'list-group-item list-group-item-action skill-list-item py-1 px-2';
        li.innerHTML =
            '<div class="d-flex align-items-center gap-2">' +
            '  <i class="fa-solid fa-cube fa-xs text-secondary"></i>' +
            '  <span class="small">' + escapeHtml(fqn) + '</span>' +
            '</div>';
        li.addEventListener('click', function () {
            addType(fqn);
            document.getElementById('type-search').value = '';
            dropdown.style.display = 'none';
        });
        list.appendChild(li);
    });
    dropdown.style.display = '';
}

function addType(fqn) {
    if (!modalTypes.includes(fqn)) { modalTypes.push(fqn); renderTypePills(); }
}

// ── Sub-skill pills ───────────────────────────────────────────────────────────
function renderSubSkillPills() {
    const container = document.getElementById('subskill-pill-container');
    container.innerHTML = '';
    if (modalSubSkills.length === 0) {
        container.innerHTML = '<span class="text-muted small">No sub-skills assigned.</span>';
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
        return (s.name + ' ' + (s.description || '') + ' ' + (s.category || '')).toLowerCase().includes(query);
    });

    list.innerHTML = '';
    if (matches.length === 0) { dropdown.style.display = 'none'; return; }
    matches.forEach(function (s) {
        const li = document.createElement('li');
        li.className = 'list-group-item list-group-item-action skill-list-item py-1 px-2';
        li.innerHTML =
            '<div class="d-flex align-items-center gap-2">' +
            '  <i class="fa-solid fa-bolt fa-xs text-secondary"></i>' +
            '  <span class="fw-semibold small">' + escapeHtml(s.name) + '</span>' +
            (s.category ? '  <span class="badge bg-dark border border-secondary text-secondary" style="font-size:0.65rem">' + escapeHtml(s.category) + '</span>' : '') +
            '</div>' +
            (s.description ? '<div class="text-muted" style="font-size:0.7rem">' + escapeHtml(s.description) + '</div>' : '');
        li.addEventListener('click', function () {
            addSubSkill(s.uuid);
            document.getElementById('subskill-search').value = '';
            dropdown.style.display = 'none';
        });
        list.appendChild(li);
    });
    dropdown.style.display = '';
}

function addSubSkill(uuid) {
    if (!modalSubSkills.includes(uuid)) { modalSubSkills.push(uuid); renderSubSkillPills(); }
}

// ── Save ──────────────────────────────────────────────────────────────────────
async function saveSkill() {
    const id             = document.getElementById('skill-id').value.trim();
    const name           = document.getElementById('skill-name').value.trim();
    const author         = document.getElementById('skill-author').value.trim();
    const category       = document.getElementById('skill-category').value;
    const description    = document.getElementById('skill-description').value;
    const instructions   = document.getElementById('skill-instructions').value;

    if (!name) { showAlert('Name is required.', 'warning'); return; }
    if (!category) { showAlert('Category is required — please select one from the list.', 'warning'); return; }

    // Validate parameters: names must be non-empty
    for (let i = 0; i < modalParams.length; i++) {
        if (!modalParams[i].name || !modalParams[i].name.trim()) {
            showAlert('All parameters must have a name (row ' + (i + 1) + ').', 'warning');
            return;
        }
    }

    const body = {
        name:           name,
        author:         author,
        category:       category,
        description:    description,
        parameters:     modalParams.map(function (p) {
            return { name: p.name.trim(), type: p.type, description: p.description };
        }),
        instructions:   instructions,
        allowedTools:   modalTools.slice(),
        allowedTypes:   modalTypes.slice(),
        subSkillUuids:  modalSubSkills.slice()
    };

    const btn = document.getElementById('btn-save-skill');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Saving…';

    try {
        const url    = id ? '/api/skills/' + id : '/api/skills';
        const method = id ? 'PUT' : 'POST';
        const res    = await fetch(url, {
            method:  method,
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) { showAlert(data.error, 'danger'); return; }
        skillModal.hide();
        showAlert(id ? 'Skill updated.' : 'Skill created.', 'success');
        setTimeout(function () { location.reload(); }, 800);
    } catch (e) {
        showAlert('Network error saving skill.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-save me-1"></i>Save Skill';
    }
}

// ── Export ────────────────────────────────────────────────────────────────────
function exportSkill(id) {
    // Navigating to the export URL triggers the browser's file download.
    window.location.href = '/api/skills/' + id + '/export';
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
        showAlert('Could not parse file — not valid JSON.', 'danger');
        return;
    }

    if (!pkg.vorkSkillExport || !pkg.skill || !pkg.skill.uuid) {
        showAlert('Not a valid Vork skill export file.', 'danger');
        return;
    }

    try {
        const res  = await fetch('/api/skills/import', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(pkg)
        });
        const data = await res.json();
        if (data.status === 'already_installed') {
            showAlert(
                'Skill "' + escapeHtml(pkg.skill.name) + '" is already installed (UUID: ' + escapeHtml(pkg.skill.uuid) + ').',
                'warning'
            );
        } else if (data.status === 'imported') {
            const extra = data.message ? ' — ' + data.message : '';
            showAlert('Skill "' + escapeHtml(pkg.skill.name) + '" imported successfully.' + extra, 'success');
            setTimeout(function () { location.reload(); }, 900);
        } else {
            showAlert('Import failed: ' + escapeHtml(data.message || 'Unknown error'), 'danger');
        }
    } catch (e) {
        showAlert('Network error during import.', 'danger');
    }
}

// ── Delete ────────────────────────────────────────────────────────────────────
async function deleteSkill(id) {
    if (!confirm('Delete this skill? This cannot be undone.')) return;
    try {
        const res  = await fetch('/api/skills/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) { showAlert(data.error, 'danger'); return; }
        showAlert('Skill deleted.', 'success');
        const row = document.getElementById('row-' + id);
        if (row) row.remove();
        allSkills = allSkills.filter(function (s) { return s.uuid !== id; });
    } catch (e) {
        showAlert('Network error deleting skill.', 'danger');
    }
}

// ── Alert helper ──────────────────────────────────────────────────────────────
function showAlert(msg, type) {
    const area = document.getElementById('alert-area');
    area.innerHTML =
        '<div class="alert alert-' + type + ' alert-dismissible fade show" role="alert">' +
        escapeHtml(msg) +
        '<button type="button" class="btn-close" data-bs-dismiss="alert"></button>' +
        '</div>';
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
