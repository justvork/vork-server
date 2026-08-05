(function () {
  'use strict';

  if (window.vorkPreviewConsole) {
    return;
  }

  var RETENTION_LIMIT = 200;
  var DEFAULT_HEIGHT = 260;
  var MIN_HEIGHT = 120;
  var MAX_HEIGHT_RATIO = 0.8;

  var state = {
    entries: [],
    nextId: 1,
    filter: 'all',
    collapsed: false,
    visible: true,
    height: DEFAULT_HEIGHT,
    initialized: false,
    styleLoaded: false,
    host: null,
    shadow: null,
    refs: null
  };

  function nowIso() {
    return new Date().toISOString();
  }

  function safeString(value) {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    try {
      return JSON.stringify(value);
    } catch (err) {
      return String(value);
    }
  }

  function isPreviewPath() {
    return /^\/surface\/[^/]+\/preview(?:\/|$)/.test(window.location.pathname || '');
  }

  function parseStatus(entry) {
    var data = entry && entry.data && typeof entry.data === 'object' ? entry.data : null;
    if (!data) {
      return null;
    }
    var status = data.statusCode || data.status || null;
    if (typeof status === 'number') {
      return status;
    }
    var asNumber = parseInt(String(status || '').trim(), 10);
    return Number.isNaN(asNumber) ? null : asNumber;
  }

  function classify(type, data) {
    var normalized = (type || 'info').toLowerCase();
    if (normalized === 'reflection:start' || normalized === 'reflection:success' || normalized === 'reflection:error') {
      return normalized;
    }
    if (normalized === 'success' || normalized === 'error' || normalized === 'warning' || normalized === 'info') {
      return normalized;
    }

    var status = parseStatus({ data: data });
    if (status !== null) {
      if (status >= 400) {
        return 'error';
      }
      if (status >= 200 && status < 400) {
        return 'success';
      }
    }
    return 'info';
  }

  function summarize(type, data) {
    var status = parseStatus({ data: data });
    var response = data && data.response;
    var reflectionName = data && (data.reflectionName || data.reflectionId);
    var bindingProfileName = data && data.bindingProfileName;
    var bindingGroupToolId = data && data.bindingGroupToolId;
    var bindingContext = null;
    if (bindingProfileName || bindingGroupToolId) {
      bindingContext = (bindingProfileName || 'default')
        + (bindingGroupToolId ? (' @ ' + bindingGroupToolId) : '');
    }
    var context = '';
    if (reflectionName || bindingContext) {
      context = ' - ' + (reflectionName || 'unknown reflection') + ' / ' + (bindingContext || 'default');
    }
    if (response && typeof response === 'object') {
      var rs = parseStatus({ data: response });
      if (rs !== null) {
        status = rs;
      }
    }

    if (type === 'reflection:start') {
      return 'Reflection started' + context;
    }
    if (type === 'reflection:success') {
      return status ? ('Reflection succeeded (' + status + ')' + context) : ('Reflection succeeded' + context);
    }
    if (type === 'reflection:error') {
      var message = data && (data.error || data.message);
      if (message) {
        return 'Reflection failed' + context + ': ' + String(message);
      }
      return status ? ('Reflection failed (' + status + ')' + context) : ('Reflection failed' + context);
    }

    if (status !== null) {
      if (status >= 400) {
        return 'Request failed (' + status + ')';
      }
      if (status >= 200 && status < 400) {
        return 'Request succeeded (' + status + ')';
      }
    }
    return type || 'info';
  }

  function iconForEntry(entry) {
    var c = entry.classification;
    if (c === 'reflection:start' || c === 'reflection:success' || c === 'reflection:error') {
      return '⟳';
    }
    if (c === 'success') {
      return '✓';
    }
    if (c === 'error') {
      return '!';
    }
    if (c === 'warning') {
      return '⚠';
    }
    return 'i';
  }

  function limitEntries() {
    if (state.entries.length > RETENTION_LIMIT) {
      state.entries.splice(0, state.entries.length - RETENTION_LIMIT);
    }
  }

  function toPrettyJson(value) {
    if (value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      var trimmed = value.trim();
      if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
        try {
          return JSON.stringify(JSON.parse(trimmed), null, 2);
        } catch (err) {
          return value;
        }
      }
      return value;
    }
    try {
      return JSON.stringify(value, null, 2);
    } catch (err) {
      return safeString(value);
    }
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function pickCallId(entry) {
    var data = entry.data || {};
    return data.callId || data.invocationId || data.id || null;
  }

  function pickDuration(entry) {
    var data = entry.data || {};
    var ms = data.durationMs || data.elapsedMs || null;
    if (typeof ms === 'number') {
      return Math.round(ms) + 'ms';
    }
    var n = parseInt(String(ms || '').trim(), 10);
    return Number.isNaN(n) ? null : (n + 'ms');
  }

  function deriveSections(entry) {
    var data = entry.data;
    if (!data || typeof data !== 'object') {
      return { request: null, response: null, metadata: null, other: null };
    }
    var request = data.request || data.args || null;
    var response = data.response || null;
    var metadata = data.metadata || null;

    var coreKeys = {
      request: true,
      response: true,
      metadata: true,
      args: true,
      summary: true,
      message: true,
      error: true,
      callId: true,
      invocationId: true,
      id: true,
      durationMs: true,
      elapsedMs: true,
      status: true,
      statusCode: true
    };

    var other = {};
    Object.keys(data).forEach(function (k) {
      if (!coreKeys[k]) {
        other[k] = data[k];
      }
    });

    if (Object.keys(other).length === 0) {
      other = null;
    }

    return {
      request: request,
      response: response,
      metadata: metadata,
      other: other
    };
  }

  function makeRow(key, value) {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    return '<div class="vpc-row"><div class="vpc-key">' + escapeHtml(key) + '</div><div class="vpc-value">' + escapeHtml(String(value)) + '</div></div>';
  }

  function sectionTemplate(title, value, openByDefault) {
    if (value === null || value === undefined) {
      return '';
    }
    var body = toPrettyJson(value);
    return '<details class="vpc-section"' + (openByDefault ? ' open' : '') + '>' +
      '<summary>' + escapeHtml(title) + '</summary>' +
      '<div class="vpc-section-body"><pre class="vpc-json">' + escapeHtml(body) + '</pre></div>' +
      '</details>';
  }

  function renderEntry(entry) {
    var sections = deriveSections(entry);
    var callId = pickCallId(entry);
    var duration = pickDuration(entry);
    var metaRows = [
      makeRow('Type', entry.type),
      makeRow('Time', entry.timestamp),
      makeRow('Call ID', callId),
      makeRow('Duration', duration)
    ].join('');

    var status = parseStatus(entry);
    if (status !== null) {
      metaRows += makeRow('Status', status);
    }

    var extraSummary = entry.data && (entry.data.message || entry.data.summary || entry.data.error);
    var icon = iconForEntry(entry);

    return '' +
      '<details class="vpc-entry vpc-type-' + escapeHtml(entry.classification.replace('/', '-')) + '">' +
      '<summary class="vpc-summary">' +
      '<div class="vpc-summary-left">' +
      '<span class="vpc-chevron">▸</span>' +
      '<span class="vpc-icon" aria-hidden="true">' + escapeHtml(icon) + '</span>' +
      '<span class="vpc-title-text">' + escapeHtml(entry.summary) + '</span>' +
      '</div>' +
      '<span class="vpc-meta">' + escapeHtml(new Date(entry.timestamp).toLocaleTimeString()) + '</span>' +
      '</summary>' +
      '<div class="vpc-content">' +
      (extraSummary ? '<div class="vpc-row"><div class="vpc-key">Summary</div><div class="vpc-value">' + escapeHtml(String(extraSummary)) + '</div></div>' : '') +
      (metaRows ? ('<div class="vpc-section"><div class="vpc-section-body">' + metaRows + '</div></div>') : '') +
      sectionTemplate('Request', sections.request, true) +
      sectionTemplate('Response', sections.response, true) +
      sectionTemplate('Metadata', sections.metadata, false) +
      sectionTemplate('Other', sections.other, false) +
      '</div>' +
      '</details>';
  }

  function visibleEntries() {
    if (state.filter === 'all') {
      return state.entries;
    }
    if (state.filter === 'errors') {
      return state.entries.filter(function (e) {
        return e.classification === 'error' || e.classification === 'reflection:error';
      });
    }
    return state.entries;
  }

  function updateFilterButtons() {
    if (!state.refs) {
      return;
    }
    state.refs.filterAll.dataset.active = state.filter === 'all' ? 'true' : 'false';
    state.refs.filterErrors.dataset.active = state.filter === 'errors' ? 'true' : 'false';
  }

  function render() {
    if (!state.refs) {
      return;
    }

    state.refs.root.style.display = state.visible ? 'flex' : 'none';
    if (!state.visible) {
      return;
    }

    state.refs.root.style.height = (state.collapsed ? 42 : state.height) + 'px';
    state.refs.list.style.display = state.collapsed ? 'none' : 'flex';

    var entries = visibleEntries();
    if (entries.length === 0) {
      state.refs.list.innerHTML = '<div class="vpc-empty">No log entries</div>';
      return;
    }

    state.refs.list.innerHTML = entries.map(renderEntry).join('');
    var details = state.refs.list.querySelectorAll('.vpc-entry');
    details.forEach(function (el) {
      var summary = el.querySelector('.vpc-summary');
      var chevron = summary ? summary.querySelector('.vpc-chevron') : null;
      if (!summary || !chevron) {
        return;
      }
      function sync() {
        chevron.textContent = el.open ? '▾' : '▸';
      }
      sync();
      el.addEventListener('toggle', sync);
    });
    state.refs.list.scrollTop = state.refs.list.scrollHeight;
  }

  function clear() {
    state.entries = [];
    render();
  }

  function setFilter(filter) {
    state.filter = filter === 'errors' ? 'errors' : 'all';
    updateFilterButtons();
    render();
  }

  function setCollapsed(collapsed) {
    state.collapsed = !!collapsed;
    state.refs.toggleCollapse.textContent = state.collapsed ? 'Expand' : 'Collapse';
    render();
  }

  function hide() {
    state.visible = false;
    render();
  }

  function show() {
    state.visible = true;
    render();
  }

  function normalizeLogArgs(typeOrObject, dataMaybe) {
    if (typeof typeOrObject === 'string') {
      return {
        type: typeOrObject,
        data: dataMaybe
      };
    }
    if (typeOrObject && typeof typeOrObject === 'object') {
      return {
        type: typeOrObject.type || 'info',
        data: typeOrObject.data !== undefined ? typeOrObject.data : typeOrObject
      };
    }
    return {
      type: 'info',
      data: dataMaybe
    };
  }

  function logEvent(typeOrObject, dataMaybe) {
    var normalized = normalizeLogArgs(typeOrObject, dataMaybe);
    var type = String(normalized.type || 'info');
    var data = normalized.data;

    var entry = {
      id: state.nextId++,
      timestamp: nowIso(),
      type: type,
      data: data,
      classification: classify(type, data)
    };
    entry.summary = summarize(entry.type, entry.data);

    state.entries.push(entry);
    limitEntries();
    render();

    return {
      id: entry.id,
      timestamp: entry.timestamp,
      type: entry.type,
      summary: entry.summary
    };
  }

  function createHost() {
    var host = document.createElement('div');
    host.setAttribute('data-vork-preview-console', 'true');
    document.documentElement.appendChild(host);
    var shadow = host.attachShadow({ mode: 'open' });
    state.host = host;
    state.shadow = shadow;
    return shadow;
  }

  function loadStylesheet(shadow, onDone) {
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = '/css/surface-preview-console.css';

    var fallback = document.createElement('style');
    fallback.textContent = '.vpc-root{position:fixed;left:0;right:0;bottom:0;z-index:2147483200;display:flex;flex-direction:column;background:#0b0d10;border-top:1px solid #23272e;color:#d4d4d8}.vpc-toolbar{display:flex;justify-content:space-between;align-items:center;padding:8px;border-bottom:1px solid #23272e}.vpc-list{overflow:auto;flex:1;padding:8px}.vpc-btn{font-size:12px;border:1px solid #2b3140;background:#171b24;color:#d4d4d8;border-radius:6px;padding:4px 8px;cursor:pointer}.vpc-entry{border:1px solid #23272e;border-left-width:4px;margin-bottom:8px;border-radius:8px;background:#12161f}.vpc-summary{display:flex;justify-content:space-between;padding:8px;cursor:pointer}.vpc-content{padding:8px;border-top:1px solid #23272e}.vpc-empty{text-align:center;color:#7f8491;padding:10px}';

    var completed = false;
    function done() {
      if (completed) {
        return;
      }
      completed = true;
      state.styleLoaded = true;
      onDone();
    }

    link.addEventListener('load', done);
    link.addEventListener('error', function () {
      shadow.appendChild(fallback);
      done();
    });

    shadow.appendChild(link);
    setTimeout(function () {
      done();
    }, 1200);
  }

  function buildMarkup() {
    state.shadow.innerHTML += '' +
      '<div class="vpc-root" aria-live="polite" aria-label="Surface Console">' +
      '<div class="vpc-resizer" title="Resize"></div>' +
      '<div class="vpc-toolbar">' +
      '<div class="vpc-title">Surface Console</div>' +
      '<div class="vpc-controls">' +
      '<button type="button" class="vpc-btn" data-action="filter-all" data-active="true">All</button>' +
      '<button type="button" class="vpc-btn" data-action="filter-errors">Errors only</button>' +
      '<button type="button" class="vpc-btn" data-action="clear">Clear</button>' +
      '<button type="button" class="vpc-btn" data-action="collapse">Collapse</button>' +
      '<button type="button" class="vpc-btn" data-action="hide">Hide</button>' +
      '</div>' +
      '</div>' +
      '<div class="vpc-list"></div>' +
      '</div>';

    var root = state.shadow.querySelector('.vpc-root');
    state.refs = {
      root: root,
      resizer: state.shadow.querySelector('.vpc-resizer'),
      list: state.shadow.querySelector('.vpc-list'),
      filterAll: state.shadow.querySelector('[data-action="filter-all"]'),
      filterErrors: state.shadow.querySelector('[data-action="filter-errors"]'),
      clear: state.shadow.querySelector('[data-action="clear"]'),
      toggleCollapse: state.shadow.querySelector('[data-action="collapse"]'),
      hide: state.shadow.querySelector('[data-action="hide"]')
    };

    state.refs.filterAll.addEventListener('click', function () { setFilter('all'); });
    state.refs.filterErrors.addEventListener('click', function () { setFilter('errors'); });
    state.refs.clear.addEventListener('click', clear);
    state.refs.toggleCollapse.addEventListener('click', function () {
      setCollapsed(!state.collapsed);
    });
    state.refs.hide.addEventListener('click', hide);

    initResizer();
    updateFilterButtons();
    render();
  }

  function initResizer() {
    var startY = 0;
    var startHeight = 0;

    function onMouseMove(event) {
      var delta = startY - event.clientY;
      var maxHeight = Math.floor(window.innerHeight * MAX_HEIGHT_RATIO);
      var next = Math.max(MIN_HEIGHT, Math.min(maxHeight, startHeight + delta));
      state.height = next;
      if (state.collapsed) {
        state.collapsed = false;
        state.refs.toggleCollapse.textContent = 'Collapse';
      }
      render();
    }

    function onMouseUp() {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    }

    state.refs.resizer.addEventListener('mousedown', function (event) {
      startY = event.clientY;
      startHeight = state.height;
      window.addEventListener('mousemove', onMouseMove);
      window.addEventListener('mouseup', onMouseUp);
      event.preventDefault();
    });
  }

  function init() {
    if (state.initialized || !isPreviewPath()) {
      return;
    }

    var shadow = createHost();
    loadStylesheet(shadow, function () {
      buildMarkup();
      state.initialized = true;

      logEvent('info', {
        message: 'Surface console initialized',
        location: window.location.pathname
      });
    });
  }

  function destroy() {
    if (state.host && state.host.parentNode) {
      state.host.parentNode.removeChild(state.host);
    }
    state.initialized = false;
    state.host = null;
    state.shadow = null;
    state.refs = null;
    state.entries = [];
  }

  window.vorkPreviewConsole = {
    init: init,
    destroy: destroy,
    log: logEvent,
    clear: clear,
    show: show,
    hide: hide,
    setFilter: setFilter,
    getState: function () {
      return {
        initialized: state.initialized,
        collapsed: state.collapsed,
        visible: state.visible,
        filter: state.filter,
        size: state.entries.length,
        height: state.height
      };
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
