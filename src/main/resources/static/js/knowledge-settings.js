// Knowledge Settings Page JavaScript

let currentBase = null;
let currentPage = 0;
const PAGE_SIZE = 20;
let editingUuid = null;

// DOM elements
const basesList = document.getElementById('basesList');
const entriesBody = document.getElementById('entriesBody');
const entriesContainer = document.getElementById('entriesContainer');
const emptyState = document.getElementById('emptyState');
const paginationContainer = document.getElementById('paginationContainer');
const newEntryBtn = document.getElementById('newEntryBtn');
const newBaseBtn = document.getElementById('newBaseBtn');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const entryModal = document.getElementById('entryModal');
const modalTitle = document.getElementById('modalTitle');
const modalBase = document.getElementById('modalBase');
const modalContent = document.getElementById('modalContent');
const saveBtn = document.getElementById('saveBtn');
const cancelBtn = document.getElementById('cancelBtn');
const closeModalBtn = document.getElementById('closeModalBtn');
const statusMessage = document.getElementById('statusMessage');

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    loadBases();
    setupEventListeners();
});

function setupEventListeners() {
    newEntryBtn.addEventListener('click', openNewEntryModal);
    newBaseBtn.addEventListener('click', openNewBaseModal);
    prevBtn.addEventListener('click', previousPage);
    nextBtn.addEventListener('click', nextPage);
    saveBtn.addEventListener('click', saveEntry);
    cancelBtn.addEventListener('click', closeModal);
    closeModalBtn.addEventListener('click', closeModal);

    // Close modal on ESC
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && entryModal.style.display !== 'none') {
            closeModal();
        }
    });
}

function loadBases() {
    fetch('/api/knowledge/bases')
        .then(res => res.json())
        .then(data => {
            if (data.bases && Array.isArray(data.bases)) {
                const sortedBases = data.bases.sort();
                renderBases(sortedBases);
            } else {
                basesList.innerHTML = '<div class="px-4 py-3 text-sm text-zinc-400 italic">No categories yet</div>';
            }
        })
        .catch(err => {
            console.error('Error loading bases:', err);
            basesList.innerHTML = '<div class="px-4 py-3 text-sm text-rose-400">Error loading categories</div>';
        });
}

function renderBases(bases) {
    basesList.innerHTML = '';
    if (bases.length === 0) {
        basesList.innerHTML = '<div class="px-4 py-3 text-sm text-zinc-400 italic">No categories</div>';
        return;
    }

    Promise.all(bases.map(base => 
        fetch(`/api/knowledge/entries?base=${encodeURIComponent(base)}&page=0&pageSize=1`)
            .then(r => r.json())
            .then(d => ({ base, count: d.total || 0 }))
            .catch(() => ({ base, count: 0 }))
    )).then(results => {
        basesList.innerHTML = '';
        results.forEach(({ base, count }) => {
            const btn = document.createElement('button');
            btn.className = 'base-item';
            btn.dataset.base = base;
            btn.innerHTML = `<span>${escapeHtml(base)}</span><span class="base-item-badge">${count}</span>`;
            btn.addEventListener('click', () => selectBase(base));
            basesList.appendChild(btn);
        });
    });
}

function selectBase(base) {
    currentBase = base;
    currentPage = 0;
    editingUuid = null;

    // Update active state
    document.querySelectorAll('#basesList .base-item').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.base === base);
    });

    newEntryBtn.disabled = false;
    loadEntries();
}

function loadEntries() {
    if (!currentBase) return;

    fetch(`/api/knowledge/entries?base=${encodeURIComponent(currentBase)}&page=${currentPage}&pageSize=${PAGE_SIZE}`)
        .then(res => res.json())
        .then(data => renderEntries(data))
        .catch(err => {
            console.error('Error loading entries:', err);
            showError('Error loading entries');
        });
}

function renderEntries(data) {
    const entries = data.entries || [];
    const total = data.total || 0;

    entriesBody.innerHTML = '';

    if (entries.length === 0) {
        emptyState.classList.remove('hidden');
        entriesContainer.classList.add('hidden');
        paginationContainer.classList.add('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    entriesContainer.classList.remove('hidden');

    entries.forEach(entry => {
        const row = document.createElement('tr');
        row.className = 'border-b border-zinc-800/80 last:border-0 hover:bg-zinc-800/30 transition-colors';

        const contentPreview = entry.content.length > 100
            ? entry.content.substring(0, 100) + '…'
            : entry.content;

        row.innerHTML = `
            <td class="px-4 py-2 align-top">${escapeHtml(entry.base)}</td>
            <td class="px-4 py-2 align-top content-preview" title="${escapeHtml(entry.content)}">${escapeHtml(contentPreview)}</td>
            <td class="px-4 py-2 align-top text-zinc-400 text-xs">${formatDate(entry.createdAt)}</td>
            <td class="px-4 py-2 align-top text-zinc-400 text-xs">${formatDate(entry.updatedAt)}</td>
            <td class="px-4 py-2 align-top flex gap-2">
                <button class="action-button edit" onclick="editEntry('${entry.uuid}')" title="Edit">
                    <i class="fa-solid fa-pen text-sm"></i>
                </button>
                <button class="action-button delete" onclick="deleteEntry('${entry.uuid}')" title="Delete">
                    <i class="fa-solid fa-trash text-sm"></i>
                </button>
            </td>
        `;
        entriesBody.appendChild(row);
    });

    // Update pagination
    const pageStart = currentPage * PAGE_SIZE + 1;
    const pageEnd = Math.min((currentPage + 1) * PAGE_SIZE, total);
    document.getElementById('pageStart').textContent = pageStart;
    document.getElementById('pageEnd').textContent = pageEnd;
    document.getElementById('totalCount').textContent = total;

    prevBtn.disabled = currentPage === 0;
    nextBtn.disabled = pageEnd >= total;
    paginationContainer.classList.remove('hidden');
}

function previousPage() {
    if (currentPage > 0) {
        currentPage--;
        loadEntries();
    }
}

function nextPage() {
    currentPage++;
    loadEntries();
}

function openNewEntryModal() {
    editingUuid = null;
    modalTitle.textContent = 'New Knowledge Entry';
    modalBase.value = currentBase;
    modalBase.disabled = true;
    modalContent.value = '';
    showModal();
}

function openNewBaseModal() {
    const newBase = prompt('Enter new category name:');
    if (newBase && newBase.trim()) {
        // Create an entry in the new base (this implicitly creates the category)
        const base = newBase.trim();
        editingUuid = null;
        modalTitle.textContent = 'New Knowledge Entry';
        modalBase.value = base;
        modalBase.disabled = false;
        modalContent.value = '';
        showModal();
    }
}

function editEntry(uuid) {
    fetch(`/api/knowledge/entries?base=${encodeURIComponent(currentBase)}&page=0&pageSize=1000`)
        .then(res => res.json())
        .then(data => {
            const entry = data.entries.find(e => e.uuid === uuid);
            if (entry) {
                editingUuid = uuid;
                modalTitle.textContent = 'Edit Knowledge Entry';
                modalBase.value = entry.base;
                modalBase.disabled = true;
                modalContent.value = entry.content;
                showModal();
            }
        })
        .catch(err => showError('Error loading entry'));
}

function deleteEntry(uuid) {
    if (confirm('Delete this knowledge entry?')) {
        fetch(`/api/knowledge/entries/${uuid}`, { method: 'DELETE' })
            .then(res => res.json())
            .then(data => {
                if (data.status === 'ok') {
                    showSuccess('Entry deleted');
                    loadEntries();
                    loadBases();
                } else {
                    showError(data.message || 'Error deleting entry');
                }
            })
            .catch(err => showError('Error deleting entry'));
    }
}

function saveEntry() {
    const base = modalBase.value.trim();
    const content = modalContent.value.trim();

    if (!base) {
        showError('Category is required');
        return;
    }
    if (!content) {
        showError('Content is required');
        return;
    }

    const url = editingUuid
        ? `/api/knowledge/entries/${editingUuid}`
        : '/api/knowledge/entries';
    const method = editingUuid ? 'PUT' : 'POST';
    const body = JSON.stringify({ base, content });

    fetch(url, { method, body, headers: { 'Content-Type': 'application/json' } })
        .then(res => res.json())
        .then(data => {
            if (data.status === 'ok') {
                showSuccess(editingUuid ? 'Entry updated' : 'Entry created');
                closeModal();
                loadEntries();
                loadBases();
            } else {
                showError(data.message || 'Error saving entry');
            }
        })
        .catch(err => showError('Error saving entry'));
}

function showModal() {
    entryModal.style.display = 'flex';
}

function closeModal() {
    entryModal.style.display = 'none';
    editingUuid = null;
}

function showSuccess(message) {
    showStatus(message, 'success');
}

function showError(message) {
    showStatus(message, 'error');
}

function showStatus(message, type) {
    const div = document.createElement('div');
    const bgClass = type === 'success'
        ? 'rounded-lg border border-emerald-700/60 bg-emerald-950/40 px-3 py-2 text-sm text-emerald-300'
        : 'rounded-lg border border-rose-700/60 bg-rose-950/40 px-3 py-2 text-sm text-rose-300';
    div.className = bgClass;
    div.textContent = message;
    statusMessage.innerHTML = '';
    statusMessage.appendChild(div);

    setTimeout(() => statusMessage.innerHTML = '', 5000);
}

function formatDate(epoch) {
    if (!epoch) return '—';
    const date = new Date(epoch);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}
