(function () {
    function escapeHtml(value) {
        if (value == null) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/\"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function ensureDialog() {
        var existing = document.getElementById('dependency-precheck-modal');
        if (existing) {
            return existing;
        }

        var modal = document.createElement('div');
        modal.id = 'dependency-precheck-modal';
        modal.className = 'hidden fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4';
        modal.innerHTML = ''
            + '<div class="w-full max-w-5xl rounded-xl border border-zinc-700 bg-zinc-900 shadow-2xl">'
            + '  <div class="flex items-center justify-between border-b border-zinc-800 px-5 py-3">'
            + '    <h3 id="dependency-precheck-title" class="text-lg font-semibold text-zinc-100">Dependency Check</h3>'
            + '    <button id="dependency-precheck-close" type="button" class="rounded-md border border-zinc-700 px-2 py-1 text-zinc-300 transition-colors hover:bg-zinc-800" aria-label="Close">x</button>'
            + '  </div>'
            + '  <div class="space-y-3 px-5 py-4">'
            + '    <div id="dependency-precheck-summary" class="rounded-lg border border-rose-700/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-200"></div>'
            + '    <div id="dependency-precheck-cycles" class="hidden rounded-lg border border-amber-700/50 bg-amber-950/40 px-3 py-2 text-xs text-amber-200"></div>'
            + '    <div>'
            + '      <h4 class="mb-1 text-sm font-semibold text-zinc-100">Blocking Issues</h4>'
            + '      <div id="dependency-precheck-issues" class="max-h-64 overflow-auto rounded-lg border border-zinc-800 bg-zinc-950"></div>'
            + '    </div>'
            + '    <div>'
            + '      <h4 class="mb-1 text-sm font-semibold text-zinc-100">Checked Dependencies</h4>'
            + '      <div id="dependency-precheck-checked" class="max-h-48 overflow-auto rounded-lg border border-zinc-800 bg-zinc-950"></div>'
            + '    </div>'
            + '  </div>'
            + '  <div class="flex items-center justify-end gap-2 border-t border-zinc-800 px-5 py-3">'
            + '    <button id="dependency-precheck-ok" type="button" class="rounded-lg border border-zinc-600 px-3 py-1.5 text-sm font-medium text-zinc-200 transition-colors hover:bg-zinc-800">OK</button>'
            + '  </div>'
            + '</div>';

        document.body.appendChild(modal);

        function closeModal() {
            modal.classList.add('hidden');
        }

        var closeBtn = document.getElementById('dependency-precheck-close');
        if (closeBtn) {
            closeBtn.addEventListener('click', closeModal);
        }
        var okBtn = document.getElementById('dependency-precheck-ok');
        if (okBtn) {
            okBtn.addEventListener('click', closeModal);
        }
        modal.addEventListener('click', function (event) {
            if (event.target === modal) {
                closeModal();
            }
        });

        return modal;
    }

    function buildNameLookup(report) {
        var lookup = {};
        var checked = report && report.checked ? report.checked : [];
        var issues = report && report.issues ? report.issues : [];

        checked.forEach(function (entry) {
            var key = String(entry.componentType || '') + '|' + String(entry.componentId || '');
            var name = entry.componentName ? String(entry.componentName).trim() : '';
            if (key !== '|' && name) {
                lookup[key] = name;
            }
        });
        issues.forEach(function (entry) {
            var key = String(entry.componentType || '') + '|' + String(entry.componentId || '');
            var name = entry.componentName ? String(entry.componentName).trim() : '';
            if (key !== '|' && name && !lookup[key]) {
                lookup[key] = name;
            }
        });
        return lookup;
    }

    function displayName(name, fallback) {
        var normalized = name ? String(name).trim() : '';
        if (normalized) {
            return normalized;
        }
        return fallback ? String(fallback).trim() : '';
    }

    function humanizePath(path, nameLookup) {
        if (!path || !String(path).trim()) {
            return '';
        }
        return String(path).split(/\s*->\s*/).map(function (node) {
            var parts = String(node).split(':');
            var type = parts.length > 0 ? parts[0].trim() : '';
            var id = parts.length > 1 ? parts.slice(1).join(':').trim() : '';
            var key = type + '|' + id;
            var label = displayName(nameLookup[key], id);
            return type + ':' + label;
        }).join(' -> ');
    }

    function renderIssues(issues, report) {
        if (!issues || issues.length === 0) {
            return '<div class="px-3 py-2 text-xs text-zinc-400">No blocking issues.</div>';
        }
        var nameLookup = buildNameLookup(report);
        var rows = issues.map(function (issue) {
            var name = issue.componentName ? String(issue.componentName).trim() : '';
            return ''
                + '<tr class="border-b border-zinc-800/80 last:border-0">'
                + '  <td class="px-2 py-1 align-top text-zinc-200">' + escapeHtml(issue.componentType || '') + '</td>'
                + '  <td class="px-2 py-1 align-top text-zinc-200">' + (name ? escapeHtml(name) : '<span class="text-zinc-500">Unknown</span>') + '</td>'
                + '  <td class="px-2 py-1 align-top"><span class="inline-flex rounded border border-rose-700/60 bg-rose-950/40 px-1.5 py-0.5 text-[11px] text-rose-300">' + escapeHtml(issue.status || '') + '</span></td>'
                + '  <td class="px-2 py-1 align-top text-zinc-300">' + escapeHtml(issue.reason || '') + '</td>'
                + '  <td class="px-2 py-1 align-top text-xs text-zinc-500">' + escapeHtml(humanizePath(issue.path || '', nameLookup)) + '</td>'
                + '</tr>';
        }).join('');

        return ''
            + '<table class="min-w-full text-left text-xs">'
            + '  <thead class="border-b border-zinc-700 text-zinc-400">'
            + '    <tr>'
            + '      <th class="px-2 py-1">Type</th>'
            + '      <th class="px-2 py-1">Name</th>'
            + '      <th class="px-2 py-1">Status</th>'
            + '      <th class="px-2 py-1">Reason</th>'
            + '      <th class="px-2 py-1">Path</th>'
            + '    </tr>'
            + '  </thead>'
            + '  <tbody>' + rows + '</tbody>'
            + '</table>';
    }

    function renderChecked(checked) {
        if (!checked || checked.length === 0) {
            return '<div class="px-3 py-2 text-xs text-zinc-400">No dependency nodes were checked.</div>';
        }
        var rows = checked.map(function (entry) {
            var name = entry.componentName ? String(entry.componentName).trim() : '';
            return ''
                + '<tr class="border-b border-zinc-800/80 last:border-0">'
                + '  <td class="px-2 py-1 text-zinc-300">' + escapeHtml(entry.componentType || '') + '</td>'
                + '  <td class="px-2 py-1 text-zinc-200">' + (name ? escapeHtml(name) : '<span class="text-zinc-500">-</span>') + '</td>'
                + '  <td class="px-2 py-1 text-zinc-300">' + escapeHtml(entry.status || '') + '</td>'
                + '</tr>';
        }).join('');
        return ''
            + '<table class="min-w-full text-left text-xs">'
            + '  <thead class="border-b border-zinc-700 text-zinc-500">'
            + '    <tr><th class="px-2 py-1">Type</th><th class="px-2 py-1">Name</th><th class="px-2 py-1">Status</th></tr>'
            + '  </thead>'
            + '  <tbody>' + rows + '</tbody>'
            + '</table>';
    }

    function showReportDialog(componentLabel, report) {
        var modal = ensureDialog();
        var title = document.getElementById('dependency-precheck-title');
        var summary = document.getElementById('dependency-precheck-summary');
        var issues = document.getElementById('dependency-precheck-issues');
        var checked = document.getElementById('dependency-precheck-checked');
        var cycles = document.getElementById('dependency-precheck-cycles');
        var valid = !!(report && report.valid);

        if (title) {
            title.textContent = valid
                ? ((componentLabel || 'Artifact') + ' dependency pre-check report')
                : ((componentLabel || 'Artifact') + ' dependency pre-check failed');
        }
        if (summary) {
            summary.className = valid
                ? 'rounded-lg border border-emerald-700/50 bg-emerald-950/40 px-3 py-2 text-sm text-emerald-200'
                : 'rounded-lg border border-rose-700/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-200';
            summary.textContent = report && report.summary
                ? report.summary
                : (valid ? 'Dependency validation passed.' : 'Dependency validation failed.');
        }
        if (issues) {
            issues.innerHTML = renderIssues(report && report.issues ? report.issues : [], report || {});
        }
        if (checked) {
            checked.innerHTML = renderChecked(report && report.checked ? report.checked : []);
        }
        if (cycles) {
            var lookup = buildNameLookup(report || {});
            var cycleList = report && report.cycles ? report.cycles : [];
            if (cycleList.length > 0) {
                cycles.classList.remove('hidden');
                cycles.innerHTML = '<strong>Cycle(s) detected:</strong><br>' + cycleList.map(function (entry) {
                    return escapeHtml(humanizePath(entry, lookup));
                }).join('<br>');
            } else {
                cycles.classList.add('hidden');
                cycles.innerHTML = '';
            }
        }

        modal.classList.remove('hidden');
    }

    async function fetchReport(componentType, id, alertFn) {
        var response;
        var payload;
        try {
            response = await fetch('/api/contributions/' + encodeURIComponent(componentType) + '/' + encodeURIComponent(id) + '/dependency-check', {
                method: 'GET'
            });
            payload = await response.json().catch(function () { return {}; });
        } catch (_error) {
            if (typeof alertFn === 'function') {
                alertFn('Dependency pre-check failed due to network error.', 'danger');
            }
            return null;
        }

        if (!response.ok || payload.error) {
            if (typeof alertFn === 'function') {
                alertFn(payload.error || payload.message || 'Dependency pre-check failed.', 'danger');
            }
            return null;
        }

        return payload.report || {};
    }

    async function runAndGate(componentType, id, componentLabel, alertFn) {
        if (!componentType || !id) {
            if (typeof alertFn === 'function') {
                alertFn('Dependency pre-check is missing component identity.', 'warning');
            }
            return false;
        }

        var report = await fetchReport(componentType, id, alertFn);
        if (!report) {
            return false;
        }

        if (report.valid) {
            return true;
        }

        showReportDialog(componentLabel || 'Artifact', report);
        return false;
    }

    async function runAndDisplay(componentType, id, componentLabel, alertFn) {
        if (!componentType || !id) {
            if (typeof alertFn === 'function') {
                alertFn('Dependency pre-check is missing component identity.', 'warning');
            }
            return false;
        }

        var report = await fetchReport(componentType, id, alertFn);
        if (!report) {
            return false;
        }

        showReportDialog(componentLabel || 'Artifact', report);
        return !!report.valid;
    }

    window.VorkDependencyPrecheck = {
        runAndGate: runAndGate,
        runAndDisplay: runAndDisplay
    };
})();
