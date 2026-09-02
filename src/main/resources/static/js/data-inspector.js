// ── Data Inspector ───────────────────────────────────────────────────────────
'use strict';

(function () {

    var csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';

    // ── DOM refs ──────────────────────────────────────────────────────────────

    var typeSelect      = document.getElementById('type-select');
    var typeSelectEmpty = document.getElementById('type-select-empty');
    var scopeLabel      = document.getElementById('scope-label');
    var searchToolSelect = document.getElementById('search-tool-select');
    var searchToolEmpty = document.getElementById('search-tool-empty');
    var typeActions     = document.getElementById('type-actions');
    var createBtn       = document.getElementById('create-btn');
    var emptyCreateBtn  = document.getElementById('empty-create-btn');
    var tableHead       = document.getElementById('table-head');
    var tableBody       = document.getElementById('table-body');
    var searchQueryType = document.getElementById('search-query-type');
    var searchQuery     = document.getElementById('search-query');
    var searchExtraParams = document.getElementById('search-extra-params');
    var searchApplyBtn  = document.getElementById('search-apply-btn');
    var searchClearBtn  = document.getElementById('search-clear-btn');
    var prevBtn         = document.getElementById('prev-btn');
    var nextBtn         = document.getElementById('next-btn');
    var paginationInfo  = document.getElementById('pagination-info');
    var modalEl         = document.getElementById('record-modal');
    var modalLabel      = document.getElementById('record-modal-label');
    var modalBody       = document.getElementById('modal-body');
    var modalSaveBtn    = document.getElementById('modal-save-btn');
    var modalError      = document.getElementById('modal-error');
    var contextHint     = document.getElementById('explorer-context-hint');

    var bsModal = new VorkModal(modalEl);

    // ── State ─────────────────────────────────────────────────────────────────

    var currentFqn    = null;
    var currentSchema = null;
    var currentPage   = 0;
    var pageSize      = 20;
    var totalCount    = 0;
    var activeQuery   = '';
    var activeQueryType = 'SQL';
    var activeSortColumn = '';
    var activeSortOrder = 'ASC';
    var searchTools = [];
    var selectedSearchToolId = '';
    var selectedSearchToolMetadata = null;
    var dynamicSearchParameters = [];
    var explorerContext = resolveExplorerContext();

    function resolveExplorerContext() {
        var params = new URLSearchParams(window.location.search || '');
        return {
            groupUuid: (params.get('groupUuid') || '').trim(),
            bindingName: (params.get('bindingName') || '').trim(),
            bindingUuid: (params.get('bindingUuid') || '').trim(),
            sessionUuid: (params.get('sessionUuid') || '').trim(),
            reflectionType: '',
            recordFqn: ''
        };
    }

    function resolveGroupContext() {
        if (!explorerContext.groupUuid) {
            explorerContext.reflectionType = 'RECORD';
            return Promise.resolve();
        }
        return apiFetch('/api/reflection-groups/' + encodeURIComponent(explorerContext.groupUuid))
            .then(function (r) { return r.ok ? r.json() : Promise.resolve(null); })
            .then(function (groupView) {
                var group = groupView && groupView.group ? groupView.group : null;
                var type = group && group.type ? String(group.type).toUpperCase() : '';
                if (type !== 'RECORD' && type !== 'MONGO') {
                    type = 'RECORD';
                }
                explorerContext.reflectionType = type;
            })
            .catch(function () {
                explorerContext.reflectionType = 'RECORD';
            });
    }

    function isRecordReflectionContext() {
        return explorerContext.reflectionType === 'RECORD';
    }

    function isMongoReflectionContext() {
        return explorerContext.reflectionType === 'MONGO';
    }

    function hasReflectionGroupContext() {
        return !!explorerContext.groupUuid && (isRecordReflectionContext() || isMongoReflectionContext());
    }

    function isLockedRecordSchema() {
        return isRecordReflectionContext() && !!explorerContext.recordFqn;
    }

    function extractRecordFqnFromOutputSchema(outputSchema) {
        if (!outputSchema) {
            return '';
        }
        try {
            var parsed = JSON.parse(outputSchema);
            var recordFqn = parsed && parsed.recordFqn ? String(parsed.recordFqn).trim() : '';
            if (recordFqn) {
                return recordFqn;
            }
        } catch (_err) {
            var match = String(outputSchema).match(/"recordFqn"\s*:\s*"([^"]+)"/);
            if (match && match[1]) {
                return match[1].trim();
            }
        }
        return '';
    }

    function parseSearchToolMetadata(reflection) {
        if (!reflection || !reflection.outputSchema) {
            return null;
        }
        try {
            var root = JSON.parse(reflection.outputSchema);
            var operation = String(root.operation || '').trim().toUpperCase();
            if (operation !== 'SEARCH') {
                return null;
            }

            var isRecordTool = !!root['x-vork-record-tool'] || !!root['x-vork-mandatory-record-tool'];
            var isMongoTool = !!root['x-vork-mongo-tool'] || !!root['x-vork-mandatory-mongo-tool'];
            var isMandatoryTool = !!root['x-vork-mandatory-record-tool'] || !!root['x-vork-mandatory-mongo-tool'];
            if (!isRecordTool && !isMongoTool) {
                return null;
            }

            return {
                kind: isMongoTool ? 'MONGO' : 'RECORD',
                mandatory: isMandatoryTool,
                operation: operation,
                recordFqn: String(root.recordFqn || '').trim(),
                queryType: String(root.queryType || (isMongoTool ? 'MONGO' : 'SQL')).trim().toUpperCase(),
                collection: String(root.collection || '').trim(),
                database: String(root.database || '').trim(),
                queryTemplate: String(root.queryTemplate || '').trim()
            };
        } catch (_err) {
            return null;
        }
    }

    function shouldAutoExecuteSearchTool(tool) {
        if (!tool || !tool.metadata) {
            return false;
        }
        return !!tool.metadata.mandatory;
    }

    function shouldIncludeQueryArguments(tool) {
        if (!hasReflectionGroupContext()) {
            return true;
        }
        return shouldAutoExecuteSearchTool(tool);
    }

    function syncSearchInputVisibility() {
        var showQueryInputs = true;
        if (hasReflectionGroupContext()) {
            showQueryInputs = shouldIncludeQueryArguments(currentSearchTool());
        }

        if (searchQueryType) {
            searchQueryType.classList.toggle('hidden', !showQueryInputs);
        }
        if (searchQuery) {
            searchQuery.classList.toggle('hidden', !showQueryInputs);
        }
    }

    function resetAutoSortForContext() {
        if (!hasReflectionGroupContext()) {
            activeSortColumn = '';
            activeSortOrder = 'ASC';
            return;
        }
        activeSortColumn = isMongoReflectionContext() ? '_id' : 'uuid';
        activeSortOrder = 'ASC';
    }

    function normalizeSortOrder(value) {
        return String(value || '').toUpperCase() === 'DESC' ? 'DESC' : 'ASC';
    }

    function loadSearchToolsForGroup() {
        if (!explorerContext.groupUuid) {
            return Promise.resolve([]);
        }
        return apiFetch('/api/reflections?groupUuid=' + encodeURIComponent(explorerContext.groupUuid))
            .then(function (r) { return r.ok ? r.json() : Promise.resolve([]); })
            .then(function (groupReflections) {
                if (!Array.isArray(groupReflections)) {
                    return [];
                }

                var expectedKind = isMongoReflectionContext() ? 'MONGO' : 'RECORD';
                var tools = groupReflections
                    .map(function (reflection) {
                        var metadata = parseSearchToolMetadata(reflection);
                        if (!metadata || metadata.kind !== expectedKind) {
                            return null;
                        }
                        return {
                            id: String(reflection.id || '').trim(),
                            name: String(reflection.name || reflection.id || '').trim(),
                            metadata: metadata,
                            inputParameters: Array.isArray(reflection.inputParameters) ? reflection.inputParameters : []
                        };
                    })
                    .filter(function (tool) {
                        return !!tool && !!tool.id;
                    })
                    .sort(function (a, b) {
                        return a.name.localeCompare(b.name);
                    });

                searchTools = tools;
                if (tools.length > 0) {
                    selectedSearchToolId = tools[0].id;
                    selectedSearchToolMetadata = tools[0].metadata;
                } else {
                    selectedSearchToolId = '';
                    selectedSearchToolMetadata = null;
                }
                return tools;
            })
            .catch(function () {
                searchTools = [];
                selectedSearchToolId = '';
                selectedSearchToolMetadata = null;
                return [];
            });
    }

    function loadSelectableTypes() {
        return apiFetch('/api/types/java-types')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
            .then(function (types) {
                // Sort alphabetically by simple name
                types.sort(function (a, b) { return a.simpleName.localeCompare(b.simpleName); });
                types.forEach(function (t) {
                    var opt = document.createElement('option');
                    opt.value = t.fqn;
                    opt.textContent = t.simpleName;
                    typeSelect.appendChild(opt);
                });
                // Auto-select from URL hash e.g. #sh.vork.generated.Customer
                var hash = window.location.hash.slice(1);
                if (hash && typeSelect.querySelector('option[value="' + CSS.escape(hash) + '"]')) {
                    typeSelect.value = hash;
                    onTypeChange();
                }
            });
    }

    function enforceSearchModeForContext() {
        if (!searchQueryType) {
            return;
        }

        if (isRecordReflectionContext()) {
            searchQueryType.innerHTML = '<option value="SQL" selected>SQL</option>';
            searchQueryType.value = 'SQL';
            return;
        }

        if (isMongoReflectionContext()) {
            searchQueryType.innerHTML = ''
                + '<option value="MONGO">Mongo JSON</option>'
                + '<option value="SQL">SQL</option>';
            var preferred = selectedSearchToolMetadata && selectedSearchToolMetadata.queryType
                ? selectedSearchToolMetadata.queryType
                : 'MONGO';
            searchQueryType.value = preferred === 'SQL' ? 'SQL' : 'MONGO';
            return;
        }

        searchQueryType.innerHTML = ''
            + '<option value="SQL" selected>SQL</option>'
            + '<option value="MONGO">Mongo JSON</option>';
        searchQueryType.value = 'SQL';
    }

    function buildReflectionContextHeaders() {
        if (!explorerContext.bindingUuid) {
            return {};
        }
        var headers = {
            'X-Vork-Reflection-Type': explorerContext.reflectionType,
            'X-Vork-Reflection-Binding-UUID': explorerContext.bindingUuid,
            'X-Vork-Reflection-Binding-Name': explorerContext.bindingName || ''
        };
        if (explorerContext.sessionUuid) {
            headers['X-Vork-Session-UUID'] = explorerContext.sessionUuid;
        }
        return headers;
    }

    function apiFetch(url, options) {
        var opts = options || {};
        var mergedHeaders = Object.assign({}, buildReflectionContextHeaders(), opts.headers || {});
        if (csrfToken) {
            mergedHeaders[csrfHeader] = csrfToken;
        }
        return fetch(url, Object.assign({}, opts, { headers: mergedHeaders }));
    }

    function renderExplorerContextHint() {
        if (!contextHint) {
            return;
        }
        var bindingLabel = explorerContext.bindingName || explorerContext.bindingUuid || 'selected binding';
        var message;
        if (isRecordReflectionContext()) {
            message = 'Binding scope: executing selected RECORD search tools for ' + bindingLabel + '.';
        } else {
            message = 'Binding scope: executing selected MONGO search tools for ' + bindingLabel + '.';
        }
        contextHint.textContent = message;
        contextHint.classList.remove('hidden');
    }

    function syncCreateActionsForContext() {
        if (!createBtn) {
            return;
        }

        if (!hasReflectionGroupContext()) {
            createBtn.classList.remove('hidden');
            if (emptyCreateBtn) {
                emptyCreateBtn.classList.remove('hidden');
            }
            return;
        }

        var showCreate = isRecordReflectionContext();
        createBtn.classList.toggle('hidden', !showCreate);
        if (emptyCreateBtn) {
            emptyCreateBtn.classList.toggle('hidden', !showCreate);
        }
    }

    function applyScopeUiForContext() {
        if (!hasReflectionGroupContext()) {
            if (scopeLabel) {
                scopeLabel.innerHTML = '<i class="fa-solid fa-wand-magic-sparkles mr-1"></i> Tool';
            }
            if (searchToolSelect) {
                searchToolSelect.classList.add('hidden');
            }
            if (searchToolEmpty) {
                searchToolEmpty.classList.add('hidden');
            }
            if (typeSelect) {
                typeSelect.classList.remove('hidden');
                typeSelect.disabled = false;
            }
            if (typeSelectEmpty) {
                typeSelectEmpty.classList.add('hidden');
            }
            return;
        }

        if (scopeLabel) {
            scopeLabel.innerHTML = '<i class="fa-solid fa-wand-magic-sparkles mr-1"></i> Tool';
        }
        if (typeSelect) {
            typeSelect.classList.add('hidden');
        }
        if (searchToolSelect) {
            searchToolSelect.classList.remove('hidden');
        }
        if (typeSelectEmpty) {
            typeSelectEmpty.classList.add('hidden');
        }
    }

    function renderSearchToolOptions() {
        if (!searchToolSelect || !searchToolEmpty) {
            return;
        }
        searchToolSelect.innerHTML = '<option value="">— select a tool —</option>';
        if (!searchTools || searchTools.length === 0) {
            searchToolSelect.value = '';
            searchToolSelect.classList.add('hidden');
            searchToolEmpty.classList.remove('hidden');
            return;
        }

        searchTools.forEach(function (tool) {
            var opt = document.createElement('option');
            opt.value = tool.id;
            var suffix = '';
            if (tool.metadata && tool.metadata.kind === 'MONGO') {
                var parts = [];
                if (tool.metadata.database) {
                    parts.push(tool.metadata.database);
                }
                if (tool.metadata.collection) {
                    parts.push(tool.metadata.collection);
                }
                if (parts.length > 0) {
                    suffix = ' [' + parts.join('.') + ']';
                }
            }
            opt.textContent = tool.name + suffix;
            searchToolSelect.appendChild(opt);
        });

        searchToolSelect.value = selectedSearchToolId || searchTools[0].id;
        selectedSearchToolId = searchToolSelect.value;
        selectedSearchToolMetadata = (searchTools.find(function (tool) { return tool.id === selectedSearchToolId; }) || {}).metadata || null;
        searchToolSelect.classList.remove('hidden');
        searchToolEmpty.classList.add('hidden');
    }

    function currentSearchTool() {
        if (!selectedSearchToolId) {
            return null;
        }
        return searchTools.find(function (tool) { return tool.id === selectedSearchToolId; }) || null;
    }

    function isCoreOrPagingSearchParameter(name) {
        var n = String(name || '').trim().toLowerCase();
        return n === 'query'
            || n === 'querytype'
            || n === 'page'
            || n === 'pagesize'
            || n === 'sortfield'
            || n === 'sortcolumn'
            || n === 'sortorder';
    }

    function resolveDynamicSearchParameters(tool) {
        var source = tool && Array.isArray(tool.inputParameters) ? tool.inputParameters : [];
        var seen = Object.create(null);
        return source.filter(function (param) {
            var name = param && param.name ? String(param.name).trim() : '';
            if (!name || isCoreOrPagingSearchParameter(name)) {
                return false;
            }
            var key = name.toLowerCase();
            if (seen[key]) {
                return false;
            }
            seen[key] = true;
            return true;
        });
    }

    function renderDynamicSearchParameters() {
        if (!searchExtraParams) {
            return;
        }
        searchExtraParams.innerHTML = '';
        searchExtraParams.classList.add('hidden');
        searchExtraParams.classList.remove('inspector-extra-params');

        var tool = currentSearchTool();
        dynamicSearchParameters = resolveDynamicSearchParameters(tool);
        if (!dynamicSearchParameters.length) {
            return;
        }

        dynamicSearchParameters.forEach(function (param, index) {
            var name = String(param.name || '').trim();
            var type = String(param.type || 'string').trim().toLowerCase();
            var required = !!param.required;

            var wrapper = document.createElement('div');
            wrapper.className = 'inspector-extra-param-row';

            var label = document.createElement('label');
            label.className = 'inspector-extra-param-label';
            label.setAttribute('for', 'search-extra-param-' + index);
            label.textContent = required ? (name + ' *') : name;
            if (param.description) {
                label.title = String(param.description);
            }

            var input;
            if (type === 'boolean') {
                input = document.createElement('select');
                input.className = 'form-select form-select-sm';
                input.innerHTML = '<option value="">(auto)</option><option value="true">true</option><option value="false">false</option>';
            } else {
                input = document.createElement('input');
                input.className = 'form-control form-control-sm';
                input.type = (type === 'int' || type === 'integer' || type === 'double' || type === 'number') ? 'number' : 'text';
                if (type === 'double' || type === 'number') {
                    input.step = 'any';
                }
                if (param.array) {
                    input.placeholder = 'comma-separated';
                }
            }

            input.id = 'search-extra-param-' + index;
            input.setAttribute('data-param-name', name);
            input.setAttribute('data-param-type', type);
            input.setAttribute('data-param-array', param.array ? 'true' : 'false');
            input.setAttribute('data-param-required', required ? 'true' : 'false');
            if (param.description) {
                input.title = String(param.description);
            }
            input.classList.add('inspector-extra-param-input');

            wrapper.appendChild(label);
            wrapper.appendChild(input);
            searchExtraParams.appendChild(wrapper);
        });

        searchExtraParams.classList.remove('hidden');
        searchExtraParams.classList.add('inspector-extra-params');
    }

    function collectDynamicSearchArguments() {
        if (!searchExtraParams || !dynamicSearchParameters.length) {
            return {};
        }

        var args = {};
        var inputs = searchExtraParams.querySelectorAll('[data-param-name]');
        inputs.forEach(function (input) {
            var name = input.getAttribute('data-param-name');
            var type = (input.getAttribute('data-param-type') || 'string').toLowerCase();
            var isArray = input.getAttribute('data-param-array') === 'true';
            var required = input.getAttribute('data-param-required') === 'true';
            var raw = input.value == null ? '' : String(input.value).trim();

            if (!raw) {
                if (required) {
                    args[name] = '';
                }
                return;
            }

            if (isArray) {
                args[name] = raw.split(',').map(function (item) { return item.trim(); }).filter(function (item) { return item.length > 0; });
                return;
            }

            if (type === 'boolean') {
                if (raw === 'true' || raw === 'false') {
                    args[name] = raw === 'true';
                }
                return;
            }

            if (type === 'int' || type === 'integer') {
                var intValue = Number.parseInt(raw, 10);
                args[name] = Number.isNaN(intValue) ? raw : intValue;
                return;
            }

            if (type === 'double' || type === 'number') {
                var numberValue = Number.parseFloat(raw);
                args[name] = Number.isNaN(numberValue) ? raw : numberValue;
                return;
            }

            args[name] = raw;
        });
        return args;
    }

    function onSearchToolChange() {
        if (!searchToolSelect) {
            return;
        }
        selectedSearchToolId = searchToolSelect.value;
        selectedSearchToolMetadata = (currentSearchTool() || {}).metadata || null;
        enforceSearchModeForContext();

        if (isRecordReflectionContext()) {
            explorerContext.recordFqn = selectedSearchToolMetadata && selectedSearchToolMetadata.recordFqn
                ? selectedSearchToolMetadata.recordFqn
                : '';
            if (explorerContext.recordFqn) {
                lockTypeSelectorToRecord(explorerContext.recordFqn);
                currentFqn = explorerContext.recordFqn;
            }
        }

        syncCreateActionsForContext();
        syncSearchInputVisibility();
        resetAutoSortForContext();
        renderDynamicSearchParameters();
        activeQuery = '';
        currentPage = 0;
        if (searchQuery) {
            searchQuery.value = '';
        }
        if (!currentSearchTool()) {
            showState('initial');
            typeActions.classList.add('hidden');
            return;
        }
        typeActions.classList.remove('hidden');
        if (shouldAutoExecuteSearchTool(currentSearchTool())) {
            loadPage(currentFqn, 0).catch(function (err) {
                showError('Search tool execution failed: ' + err);
            });
            return;
        }
        showState('initial');
        updatePagination();
    }

    // ── Boot: populate type selector ──────────────────────────────────────────

    if (searchToolSelect) {
        searchToolSelect.addEventListener('change', onSearchToolChange);
    }

    resolveGroupContext()
        .then(function () {
            applyScopeUiForContext();
            syncCreateActionsForContext();
            syncSearchInputVisibility();
            resetAutoSortForContext();
            if (!hasReflectionGroupContext()) {
                enforceSearchModeForContext();
                return loadSelectableTypes();
            }

            renderExplorerContextHint();
            return loadSearchToolsForGroup().then(function () {
                renderSearchToolOptions();
                enforceSearchModeForContext();
                if (isRecordReflectionContext() && selectedSearchToolMetadata && selectedSearchToolMetadata.recordFqn) {
                    explorerContext.recordFqn = selectedSearchToolMetadata.recordFqn;
                    lockTypeSelectorToRecord(explorerContext.recordFqn);
                    currentFqn = explorerContext.recordFqn;
                }
                syncCreateActionsForContext();
                syncSearchInputVisibility();
                renderDynamicSearchParameters();
                if (currentSearchTool() && shouldAutoExecuteSearchTool(currentSearchTool())) {
                    return loadPage(currentFqn, 0);
                }
                if (currentSearchTool()) {
                    typeActions.classList.remove('hidden');
                }
                showState('initial');
                return Promise.resolve();
            });
        })
        .catch(function (err) {
            showError('Failed to initialize data explorer: ' + err);
        });

    function lockTypeSelectorToRecord(recordFqn) {
        var fqn = (recordFqn || '').trim();
        if (!fqn) {
            return;
        }
        var simpleName = fqn;
        var lastDot = fqn.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fqn.length - 1) {
            simpleName = fqn.substring(lastDot + 1);
        }
        typeSelect.innerHTML = '';
        var opt = document.createElement('option');
        opt.value = fqn;
        opt.textContent = simpleName + ' (locked)';
        typeSelect.appendChild(opt);
        typeSelect.value = fqn;
        typeSelect.disabled = true;
        window.location.hash = fqn;
    }

    // ── Type selector ─────────────────────────────────────────────────────────

    typeSelect.addEventListener('change', onTypeChange);

    function onTypeChange() {
        if (hasReflectionGroupContext()) {
            return;
        }
        var fqn = typeSelect.value;
        if (!fqn) {
            showState('initial');
            typeActions.classList.add('hidden');
            currentFqn = null;
            currentSchema = null;
            activeQuery = '';
            activeQueryType = 'SQL';
            return;
        }
        window.location.hash = fqn;
        currentFqn = fqn;
        currentPage = 0;
        activeQuery = '';
        activeQueryType = 'SQL';
        if (searchQuery) searchQuery.value = '';
        if (searchQueryType) searchQueryType.value = 'SQL';
        showState('loading');
        typeActions.classList.add('hidden');

        loadSchema(fqn).then(function (schema) {
            currentSchema = schema;
            return loadPage(fqn, 0);
        }).catch(function (err) {
            showError('Failed to load type: ' + err);
        });
    }

    // ── Schema loading ────────────────────────────────────────────────────────

    function loadSchema(fqn) {
        return apiFetch('/api/types/' + encodeURIComponent(fqn) + '/schema')
            .then(function (r) { return r.ok ? r.json() : r.json().then(function (e) { return Promise.reject(e.message || 'Schema error'); }); });
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    function hasActiveSearch() {
        return activeQuery && activeQuery.trim().length > 0;
    }

    function loadPage(fqn, page) {
        if (hasReflectionGroupContext()) {
            return loadPageFromSearchTool(page);
        }

        showState('loading');
        var countPromise;
        var listPromise;

        if (hasActiveSearch()) {
            var encodedQuery = encodeURIComponent(activeQuery.trim());
            var encodedType = encodeURIComponent(activeQueryType || 'SQL');
            var base = '/api/types/' + encodeURIComponent(fqn) + '/search';
            countPromise = apiFetch(base + '/count?query=' + encodedQuery + '&queryType=' + encodedType)
                .then(function (r) { return r.ok ? r.json() : r.json().then(function (e) { return Promise.reject(e.message || 'search count error'); }); })
                .then(function (d) { return d.count || 0; });
            listPromise = apiFetch(
                base + '?query=' + encodedQuery + '&queryType=' + encodedType + '&page=' + page + '&pageSize=' + pageSize
            ).then(function (r) { return r.ok ? r.json() : r.json().then(function (e) { return Promise.reject(e.message || 'search error'); }); })
                .then(function (payload) { return payload.results || []; });
        } else {
            countPromise = apiFetch('/api/types/' + encodeURIComponent(fqn) + '/count')
                .then(function (r) { return r.ok ? r.json() : Promise.reject('count error'); })
                .then(function (d) { return d.count || 0; });

            listPromise = apiFetch(
                '/api/types/' + encodeURIComponent(fqn) + '/list?page=' + page + '&pageSize=' + pageSize
            ).then(function (r) { return r.ok ? r.json() : Promise.reject('list error'); });
        }

        return Promise.all([countPromise, listPromise]).then(function (results) {
            totalCount = results[0];
            var items = results[1];
            currentPage = page;
            typeActions.classList.remove('hidden');

            if (items.length === 0 && page === 0) {
                showState('empty');
                return;
            }

            renderTable(currentSchema, items);
            updatePagination();
            showState('table');
        });
    }

    function loadPageFromSearchTool(page) {
        var tool = currentSearchTool();
        if (!tool) {
            showState('initial');
            typeActions.classList.add('hidden');
            return Promise.resolve();
        }

        showState('loading');
        var args = {
            page: page,
            pageSize: pageSize
        };
        if (hasReflectionGroupContext()) {
            var defaultSortColumn = isMongoReflectionContext() ? '_id' : 'uuid';
            var resolvedSortColumn = activeSortColumn || defaultSortColumn;
            args.sortOrder = normalizeSortOrder(activeSortOrder);
            args.sortField = resolvedSortColumn;
            args.sortColumn = resolvedSortColumn;
        }
        Object.assign(args, collectDynamicSearchArguments());

        if (shouldIncludeQueryArguments(tool)) {
            var queryValue = (activeQuery || '').trim();
            var effectiveQueryType = searchQueryType && searchQueryType.value
                ? searchQueryType.value
                : (tool.metadata && tool.metadata.queryType ? tool.metadata.queryType : 'SQL');

            if (!queryValue) {
                var templateQuery = tool.metadata && tool.metadata.queryTemplate
                    ? String(tool.metadata.queryTemplate).trim()
                    : '';
                queryValue = templateQuery || (String(effectiveQueryType).toUpperCase() === 'MONGO'
                    ? '{}'
                    : 'uuid IS NOT NULL');
            }

            args.query = queryValue;
            args.queryType = effectiveQueryType;
        }

        return apiFetch('/api/reflections/search-execute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                reflectionId: tool.id,
                bindingName: explorerContext.bindingName || '',
                args: args
            })
        })
            .then(function (r) { return r.ok ? r.json() : r.json().then(function (e) { return Promise.reject(e.error || 'search execution error'); }); })
            .then(function (payload) {
                var status = String(payload.status || 'ok').toLowerCase();
                if (status !== 'ok' && status !== 'not_found') {
                    return Promise.reject(payload.message || payload.error || 'search failed');
                }

                var items = Array.isArray(payload.results) ? payload.results : [];
                totalCount = typeof payload.total === 'number' ? payload.total : items.length;
                currentPage = page;
                typeActions.classList.remove('hidden');
                syncCreateActionsForContext();

                if (items.length === 0 && page === 0) {
                    showState('empty');
                    updatePagination();
                    return;
                }

                renderDynamicTable(items);
                updatePagination();
                showState('table');
            });
    }

    if (searchApplyBtn) {
        searchApplyBtn.addEventListener('click', function () {
            if (!currentFqn && !hasReflectionGroupContext()) return;
            activeQuery = searchQuery ? searchQuery.value : '';
            activeQueryType = searchQueryType ? searchQueryType.value : 'SQL';
            if (isRecordReflectionContext()) {
                activeQueryType = 'SQL';
            }
            currentPage = 0;
            loadPage(currentFqn, 0).catch(function (err) {
                showError('Search failed: ' + err);
            });
        });
    }

    if (searchClearBtn) {
        searchClearBtn.addEventListener('click', function () {
            if (!currentFqn && !hasReflectionGroupContext()) return;
            activeQuery = '';
            activeQueryType = 'SQL';
            if (searchQuery) searchQuery.value = '';
            if (searchQueryType) searchQueryType.value = 'SQL';
            currentPage = 0;

            if (hasReflectionGroupContext() && !shouldIncludeQueryArguments(currentSearchTool())) {
                showState('initial');
                updatePagination();
                return;
            }

            loadPage(currentFqn, 0).catch(function (err) {
                showError('Reload failed: ' + err);
            });
        });
    }

    if (searchQuery) {
        searchQuery.addEventListener('keydown', function (event) {
            if (event.key !== 'Enter') return;
            event.preventDefault();
            if (searchApplyBtn) searchApplyBtn.click();
        });
    }

    function onSortHeaderClick(columnName) {
        var column = String(columnName || '').trim();
        if (!column || !hasReflectionGroupContext()) {
            return;
        }
        if (activeSortColumn === column) {
            activeSortOrder = activeSortOrder === 'ASC' ? 'DESC' : 'ASC';
        } else {
            activeSortColumn = column;
            activeSortOrder = 'ASC';
        }
        currentPage = 0;
        loadPage(currentFqn, 0).catch(function (err) {
            showError('Sort failed: ' + err);
        });
    }

    function buildHeaderCell(label, sortColumn) {
        var th = document.createElement('th');
        th.textContent = label;

        var column = String(sortColumn || '').trim();
        if (!hasReflectionGroupContext() || !column) {
            return th;
        }

        th.classList.add('inspector-sortable-th');
        th.setAttribute('role', 'button');
        th.setAttribute('tabindex', '0');

        if (activeSortColumn === column) {
            var indicator = document.createElement('span');
            indicator.className = 'inspector-sort-indicator';
            indicator.textContent = activeSortOrder === 'DESC' ? ' \u25BE' : ' \u25B4';
            th.appendChild(indicator);
        }

        th.addEventListener('click', function () {
            onSortHeaderClick(column);
        });
        th.addEventListener('keydown', function (event) {
            if (event.key !== 'Enter' && event.key !== ' ') {
                return;
            }
            event.preventDefault();
            onSortHeaderClick(column);
        });

        return th;
    }

    // ── Table rendering ───────────────────────────────────────────────────────

    function renderTable(schema, items) {
        var columns = tableColumns(schema);
        var showEditableActions = canEditReflectionRows();

        // thead
        tableHead.innerHTML = '';
        var headerRow = document.createElement('tr');
        columns.forEach(function (col) {
            var th = buildHeaderCell(col.label, col.name);
            headerRow.appendChild(th);
        });
        if (!hasReflectionGroupContext() || showEditableActions) {
            var thActions = document.createElement('th');
            thActions.className = 'col-actions';
            headerRow.appendChild(thActions);
        }
        tableHead.appendChild(headerRow);

        // tbody
        tableBody.innerHTML = '';
        items.forEach(function (item) {
            var tr = document.createElement('tr');
            columns.forEach(function (col) {
                var td = document.createElement('td');
                var raw = item[col.name];
                var display;
                if (col.type === 'enum' && Array.isArray(col.options)) {
                    var match = col.options.find(function (o) { return o.value === raw; });
                    display = match ? match.label : displayValue(raw);
                } else {
                    display = displayValue(raw);
                }
                td.title = display;
                td.textContent = display;
                tr.appendChild(td);
            });

            // Actions
            if (!hasReflectionGroupContext() || showEditableActions) {
                var tdAct = document.createElement('td');
                tdAct.className = 'col-actions';
                if (!hasReflectionGroupContext()) {
                    tdAct.appendChild(buildEditBtn(item));
                    tdAct.appendChild(document.createTextNode(' '));
                    tdAct.appendChild(buildDeleteBtn(item));
                } else {
                    tdAct.appendChild(buildEditBtn(item));
                    tdAct.appendChild(document.createTextNode(' '));
                    tdAct.appendChild(buildDeleteBtn(item));
                }
                tr.appendChild(tdAct);
            }

            tableBody.appendChild(tr);
        });
    }

    function renderDynamicTable(items) {
        var showEditableActions = canEditReflectionRows();
        var columns = [];
        var seen = Object.create(null);
        (items || []).forEach(function (item) {
            Object.keys(item || {}).forEach(function (key) {
                if (!seen[key]) {
                    seen[key] = true;
                    columns.push(key);
                }
            });
        });

        if (columns.length === 0) {
            tableHead.innerHTML = '';
            tableBody.innerHTML = '';
            return;
        }

        tableHead.innerHTML = '';
        var headerRow = document.createElement('tr');
        columns.forEach(function (col) {
            var th = buildHeaderCell(col, col);
            headerRow.appendChild(th);
        });
        if (showEditableActions) {
            var thActions = document.createElement('th');
            thActions.className = 'col-actions';
            headerRow.appendChild(thActions);
        }
        tableHead.appendChild(headerRow);

        tableBody.innerHTML = '';
        items.forEach(function (item) {
            var tr = document.createElement('tr');
            columns.forEach(function (col) {
                var td = document.createElement('td');
                var raw = item ? item[col] : null;
                var display = displayValue(raw);
                td.title = display;
                td.textContent = display;
                tr.appendChild(td);
            });
            if (showEditableActions) {
                var tdAct = document.createElement('td');
                tdAct.className = 'col-actions';
                tdAct.appendChild(buildEditBtn(item));
                tdAct.appendChild(document.createTextNode(' '));
                tdAct.appendChild(buildDeleteBtn(item));
                tr.appendChild(tdAct);
            }
            tableBody.appendChild(tr);
        });
    }

    function canEditReflectionRows() {
        return hasReflectionGroupContext() && isRecordReflectionContext() && !!currentFqn;
    }

    function tableColumns(schema) {
        if (!schema || !Array.isArray(schema.fields)) return [];
        return schema.fields.filter(function (f) { return f.tableColumn && f.name !== 'uuid'; });
    }

    function displayValue(val) {
        if (val === null || val === undefined) return '';
        if (typeof val === 'object') return JSON.stringify(val);
        return String(val);
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    function updatePagination() {
        var start = currentPage * pageSize + 1;
        var end   = Math.min(start + pageSize - 1, totalCount);
        paginationInfo.textContent = totalCount === 0 ? 'No records' :
            'Showing ' + start + '–' + end + ' of ' + totalCount;

        prevBtn.disabled = currentPage === 0;
        nextBtn.disabled = end >= totalCount;
    }

    prevBtn.addEventListener('click', function () {
        if (currentPage > 0) loadPage(currentFqn, currentPage - 1);
    });
    nextBtn.addEventListener('click', function () {
        if ((currentPage + 1) * pageSize < totalCount) loadPage(currentFqn, currentPage + 1);
    });

    // ── Row action buttons ────────────────────────────────────────────────────

    function buildEditBtn(item) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800';
        btn.title = 'Edit';
        btn.innerHTML = '<i class="fa-solid fa-pen"></i>';
        if (hasReflectionGroupContext() && isRecordReflectionContext() && (!item || !item.uuid)) {
            btn.disabled = true;
            btn.title = 'Edit unavailable: row has no uuid';
            btn.classList.add('opacity-60', 'cursor-not-allowed');
            return btn;
        }
        btn.addEventListener('click', function () {
            openEditRow(item);
        });
        return btn;
    }

    function openEditRow(item) {
        if (!item) {
            return;
        }
        if (hasReflectionGroupContext() && isRecordReflectionContext() && !currentSchema) {
            loadSchema(currentFqn)
                .then(function (schema) {
                    currentSchema = schema;
                    openModal(item);
                })
                .catch(function (err) {
                    showError('Failed to load schema for edit: ' + err);
                });
            return;
        }
        openModal(item);
    }

    function buildDeleteBtn(item) {
        var uuid = item.uuid || '';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15';
        btn.title = 'Delete';
        btn.innerHTML = '<i class="fa-solid fa-trash"></i>';

        if (!uuid) {
            btn.disabled = true;
            btn.title = 'Delete unavailable: row has no uuid';
            btn.classList.add('opacity-60', 'cursor-not-allowed');
            return btn;
        }

        btn.addEventListener('click', function () {
            if (btn.dataset.confirming) {
                // Second click = confirmed delete
                btn.disabled = true;
                apiFetch('/api/types/' + encodeURIComponent(currentFqn) + '/' + encodeURIComponent(uuid), {
                    method: 'DELETE'
                })
                    .then(function (r) { return r.json(); })
                    .then(function (res) {
                        if (res.status === 'ok') {
                            totalCount = Math.max(0, totalCount - 1);
                            loadPage(currentFqn, currentPage);
                        } else {
                            alert('Delete failed: ' + (res.message || 'unknown error'));
                        }
                    })
                    .catch(function (err) { alert('Delete error: ' + err); });
            } else {
                // First click = ask for confirmation by changing button appearance
                btn.dataset.confirming = '1';
                btn.innerHTML = '<i class="fa-solid fa-circle-exclamation"></i>';
                btn.title = 'Click again to confirm delete';
                btn.classList.remove('border-rose-500/40', 'text-rose-300');
                btn.classList.add('border-rose-500', 'bg-rose-600/20', 'text-rose-200');
                setTimeout(function () {
                    delete btn.dataset.confirming;
                    btn.innerHTML = '<i class="fa-solid fa-trash"></i>';
                    btn.title = 'Delete';
                    btn.classList.remove('border-rose-500', 'bg-rose-600/20', 'text-rose-200');
                    btn.classList.add('border-rose-500/40', 'text-rose-300');
                }, 3000);
            }
        });

        return btn;
    }

    // ── Create button ─────────────────────────────────────────────────────────

    createBtn.addEventListener('click', function () {
        if (hasReflectionGroupContext() && isMongoReflectionContext()) {
            showError('Create is not supported for Mongo reflection search tools in Data Inspector.');
            return;
        }

        if (hasReflectionGroupContext() && isRecordReflectionContext()) {
            if (!currentFqn && selectedSearchToolMetadata && selectedSearchToolMetadata.recordFqn) {
                currentFqn = selectedSearchToolMetadata.recordFqn;
            }
            if (!currentFqn) {
                showError('No record type is selected for this reflection tool.');
                return;
            }
            if (!currentSchema) {
                loadSchema(currentFqn)
                    .then(function (schema) {
                        currentSchema = schema;
                        openModal(null);
                    })
                    .catch(function (err) {
                        showError('Failed to load schema for create: ' + err);
                    });
                return;
            }
        }

        openModal(null);
    });

    // ── Modal ─────────────────────────────────────────────────────────────────

    function openModal(existingItem) {
        modalError.classList.add('hidden');
        modalError.textContent = '';

        var isEdit = existingItem !== null && existingItem !== undefined;
        var typeName = currentSchema ? currentSchema.title : 'Record';

        modalLabel.textContent = isEdit ? ('Edit ' + typeName) : ('Create ' + typeName);
        modalSaveBtn.disabled = false;

        modalBody.innerHTML = '';
        if (currentSchema) {
            var form = buildForm(currentSchema.fields, existingItem, '', isEdit);
            modalBody.appendChild(form);
        }

        bsModal.show();
    }

    // Save handler
    modalSaveBtn.addEventListener('click', function () {
        modalError.classList.add('hidden');
        modalSaveBtn.disabled = true;

        var formData = collectFormData(modalBody);

        // For new records the uuid hidden input is absent — generate one now.
        // For edits the hidden input is already present from the read-only row.
        if (!formData.get('uuid')) {
            formData.set('uuid', crypto.randomUUID());
        }

        apiFetch('/api/types/' + encodeURIComponent(currentFqn), {
            method: 'POST',
            body: formData
        })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (res.status === 'ok') {
                    bsModal.hide();
                    loadPage(currentFqn, currentPage);
                } else {
                    modalError.textContent = res.message || 'Save failed';
                    modalError.classList.remove('hidden');
                    modalSaveBtn.disabled = false;
                }
            })
            .catch(function (err) {
                modalError.textContent = 'Request failed: ' + err;
                modalError.classList.remove('hidden');
                modalSaveBtn.disabled = false;
            });
    });

    // ── Form builder ──────────────────────────────────────────────────────────

    /**
     * Recursively builds a form `<div>` for a list of field descriptors.
     *
     * @param {Array}   fields   Array of field descriptor objects from the schema.
     * @param {Object}  values   Existing entity values (null for create).
     * @param {string}  prefix   Dot-notation prefix for nested records (e.g. "address.").
     * @param {boolean} isEdit   True when editing an existing record.
     * @returns {HTMLElement}
     */
    function buildForm(fields, values, prefix, isEdit) {
        var container = document.createElement('div');
        container.className = 'vstack gap-3';

        fields.forEach(function (field) {
            var fieldEl = buildField(field, values, prefix, isEdit);
            if (fieldEl) container.appendChild(fieldEl);
        });

        return container;
    }

    function buildField(field, values, prefix, isEdit) {
        var val = values ? values[field.name] : null;

        // uuid is auto-generated on create; shown read-only on edit.
        if (field.name === 'uuid' && prefix === '') {
            if (!isEdit) return null;  // omit entirely — value generated at save time
            return buildReadOnlyUuid(val);
        }

        if (field.type === 'record') {
            return buildNestedRecordSection(field, val, prefix, isEdit);
        }

        if (field.type === 'array') {
            return buildArrayRepeater(field, val, prefix);
        }

        // Scalar field
        return buildScalarField(field, val, prefix + field.name);
    }

    // ── Read-only UUID row (edit mode) ─────────────────────────────────────────

    function buildReadOnlyUuid(value) {
        var wrapper = document.createElement('div');
        wrapper.className = 'mb-0';

        var label = document.createElement('label');
        label.className = 'mb-1 block text-xs text-zinc-500';
        label.textContent = 'ID';
        wrapper.appendChild(label);

        var display = document.createElement('div');
        display.className = 'uuid-readonly rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-1.5 text-sm text-zinc-200';
        display.textContent = value || '';
        display.title = value || '';
        wrapper.appendChild(display);

        // Hidden input carries the uuid value through FormData
        var hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = 'uuid';
        hidden.value = value || '';
        wrapper.appendChild(hidden);

        return wrapper;
    }

    // ── Scalar field ──────────────────────────────────────────────────────────

    function buildScalarField(field, value, inputName) {
        var wrapper = document.createElement('div');
        wrapper.className = 'mb-0';

        var label = document.createElement('label');
        label.className = 'mb-1 block text-sm font-medium text-zinc-300';
        label.textContent = field.label || field.name;
        if (field.required) {
            var req = document.createElement('span');
            req.className = 'ml-1 text-rose-400';
            req.textContent = '*';
            label.appendChild(req);
        }
        wrapper.appendChild(label);

        var inputType = field.inputType || 'text';
        if (inputType === 'auto') inputType = inferInputTypeFromSchema(field);

        if (inputType === 'checkbox') {
            var checkWrapper = document.createElement('div');
            checkWrapper.className = 'flex items-center';
            var check = document.createElement('input');
            check.type = 'checkbox';
            check.className = 'h-4 w-4 rounded border-zinc-600 bg-zinc-900 text-[#fdaa02] focus:ring-[#fdaa02]/30';
            check.name = inputName;
            check.value = 'true';
            if (value === true || value === 'true') check.checked = true;
            checkWrapper.appendChild(check);
            wrapper.appendChild(checkWrapper);
        } else if (inputType === 'textarea') {
            var ta = document.createElement('textarea');
            ta.className = 'w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-1.5 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/25';
            ta.name = inputName;
            ta.rows = 3;
            if (field.placeholder) ta.placeholder = field.placeholder;
            if (field.required) ta.required = true;
            if (value !== null && value !== undefined) ta.value = String(value);
            wrapper.appendChild(ta);
        } else if (inputType === 'select' && Array.isArray(field.options)) {
            var sel = document.createElement('select');
            sel.className = 'w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-1.5 text-sm text-zinc-100 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/25';
            sel.name = inputName;
            if (field.required) sel.required = true;
            var emptyOpt = document.createElement('option');
            emptyOpt.value = '';
            emptyOpt.textContent = '\u2014 select \u2014';
            sel.appendChild(emptyOpt);
            field.options.forEach(function (opt) {
                var option = document.createElement('option');
                option.value = opt.value;
                option.textContent = opt.label;
                if (value === opt.value) option.selected = true;
                sel.appendChild(option);
            });
            wrapper.appendChild(sel);
        } else {
            var input = document.createElement('input');
            input.type = inputType;
            input.className = 'w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-1.5 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/25';
            input.name = inputName;
            if (field.placeholder) input.placeholder = field.placeholder;
            if (field.required) input.required = true;
            if (value !== null && value !== undefined) input.value = String(value);
            wrapper.appendChild(input);
        }

        return wrapper;
    }

    function inferInputTypeFromSchema(field) {
        if (field.type === 'boolean') return 'checkbox';
        if (field.type === 'integer' || field.type === 'number') return 'number';
        return 'text';
    }

    // ── Nested record section ─────────────────────────────────────────────────

    function buildNestedRecordSection(field, values, prefix, isEdit) {
        var section = document.createElement('div');
        section.className = 'field-section';

        var title = document.createElement('div');
        title.className = 'field-section-title';
        title.textContent = field.label || field.name;
        section.appendChild(title);

        var nestedPrefix = prefix + field.name + '.';
        var subFields = field.fields || [];
        var subForm = buildForm(subFields, values, nestedPrefix, isEdit);
        section.appendChild(subForm);

        return section;
    }

    // ── Array repeater ────────────────────────────────────────────────────────

    function buildArrayRepeater(field, values, prefix) {
        var wrapper = document.createElement('div');

        var label = document.createElement('label');
        label.className = 'mb-1 block text-sm font-medium text-zinc-300';
        label.textContent = field.label || field.name;
        wrapper.appendChild(label);

        var container = document.createElement('div');
        container.className = 'repeater-container';
        container.dataset.fieldName = prefix + field.name;
        container.dataset.itemType  = field.itemType || 'string';
        wrapper.appendChild(container);

        // Add existing values
        var existingItems = Array.isArray(values) ? values : [];
        if (existingItems.length === 0) {
            // Start with one empty row for convenience
            addRepeaterRow(container, field, null, 0);
        } else {
            existingItems.forEach(function (item, idx) {
                addRepeaterRow(container, field, item, idx);
            });
        }

        var addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'repeater-add-btn rounded-md border border-zinc-600 px-2 py-1 text-xs text-zinc-200 transition-colors hover:bg-zinc-800';
        addBtn.innerHTML = '<i class="fa-solid fa-plus mr-1"></i>Add row';
        addBtn.addEventListener('click', function () {
            var rowCount = container.querySelectorAll('.repeater-row').length;
            addRepeaterRow(container, field, null, rowCount);
        });
        wrapper.appendChild(addBtn);

        return wrapper;
    }

    function addRepeaterRow(container, field, values, index) {
        var row = document.createElement('div');
        row.className = 'repeater-row';

        var inputsDiv = document.createElement('div');
        inputsDiv.className = 'repeater-inputs';

        var baseName = container.dataset.fieldName + '[' + index + ']';

        if (field.itemType === 'record' && field.itemSchema && Array.isArray(field.itemSchema.fields)) {
            // Record list: one input per sub-field
            field.itemSchema.fields.forEach(function (subField) {
                var subVal = values ? values[subField.name] : null;
                var subFieldDef = Object.assign({}, subField);
                var subContainer = buildScalarField(subFieldDef, subVal, baseName + '.' + subField.name);
                subContainer.className = 'form-group mb-0';
                inputsDiv.appendChild(subContainer);
            });
        } else {
            // Scalar list: one input per row
            var inputType = inferInputTypeFromSchema(field);
            var input = document.createElement('input');
            input.type = inputType === 'checkbox' ? 'text' : inputType;
            input.className = 'w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-1.5 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-[#fdaa02] focus:outline-none focus:ring-2 focus:ring-[#fdaa02]/25';
            input.name = baseName;
            if (values !== null && values !== undefined) input.value = String(values);
            inputsDiv.appendChild(input);
        }

        var removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'repeater-remove-btn rounded-md border border-rose-500/40 px-2 py-1 text-xs text-rose-300 transition-colors hover:bg-rose-500/15';
        removeBtn.innerHTML = '<i class="fa-solid fa-minus"></i>';
        removeBtn.addEventListener('click', function () {
            row.remove();
            reindexRepeater(container, field);
        });

        row.appendChild(inputsDiv);
        row.appendChild(removeBtn);
        container.appendChild(row);
    }

    /** Renumbers all `[N]` indices after a row is removed. */
    function reindexRepeater(container, field) {
        var rows = container.querySelectorAll('.repeater-row');
        var baseName = container.dataset.fieldName;

        rows.forEach(function (row, idx) {
            var inputs = row.querySelectorAll('[name]');
            inputs.forEach(function (input) {
                // Replace the [oldIndex] with [idx], preserving any trailing .subfield
                input.name = input.name.replace(/\[\d+\]/, '[' + idx + ']');
            });
        });
    }

    // ── Form data collection ──────────────────────────────────────────────────

    /**
     * Walks all named inputs inside `container` and builds a `FormData` object.
     * Checkbox handling: if unchecked, sets value to "false"; if checked, "true".
     */
    function collectFormData(container) {
        var fd = new FormData();
        var inputs = container.querySelectorAll('[name]');
        inputs.forEach(function (el) {
            if (el.type === 'checkbox') {
                fd.append(el.name, el.checked ? 'true' : 'false');
            } else {
                fd.append(el.name, el.value);
            }
        });
        return fd;
    }

    // ── State display ─────────────────────────────────────────────────────────

    function showState(name) {
        ['initial', 'loading', 'empty', 'error', 'table'].forEach(function (s) {
            var el = document.getElementById('state-' + s);
            if (el) el.classList.toggle('hidden', s !== name);
        });
    }

    function showError(msg) {
        document.getElementById('error-message').textContent = msg;
        showState('error');
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

}());
