/* setup-page.js */

let selectedProvider = null;
let selectedDbBackend = 'nitrite';

function showAlert(msg, type) {
    document.getElementById('alert-area').innerHTML =
        '<div class="alert alert-' + type + ' alert-dismissible fade show py-2 small" role="alert">' +
        msg +
        '<button type="button" class="btn-close" data-bs-dismiss="alert"></button>' +
        '</div>';
}

function clearAlert() {
    document.getElementById('alert-area').innerHTML = '';
}

function setStep(n) {
    ['step-database', 'step-account', 'step-ai', 'step-complete'].forEach(function (id, i) {
        const panel = document.getElementById(id);
        if (panel) panel.classList.toggle('active', i + 1 === n);
    });
    for (let i = 1; i <= 4; i++) {
        const dot = document.getElementById('dot-' + i);
        if (!dot) continue;
        dot.classList.remove('active', 'done');
        if (i < n) {
            dot.classList.add('done');
        } else if (i === n) {
            dot.classList.add('active');
        }
    }
    clearAlert();
}

function selectProvider(provider) {
    selectedProvider = provider;
    document.querySelectorAll('#step-ai .provider-btn').forEach(function (b) {
        b.classList.remove('selected');
    });
    const providerButton = document.getElementById('btn-' + provider);
    if (providerButton) providerButton.classList.add('selected');
    document.querySelectorAll('#step-ai .provider-config').forEach(function (d) {
        d.classList.add('hidden');
    });
    const providerConfig = document.getElementById('config-' + provider);
    if (providerConfig) providerConfig.classList.remove('hidden');
    const providerActions = document.getElementById('provider-actions');
    if (providerActions) providerActions.classList.remove('hidden');
    const modelSelectArea = document.getElementById('model-select-area');
    if (modelSelectArea) modelSelectArea.classList.add('hidden');
    clearAlert();
}

function escapeHtml(s) {
    if (!s) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

async function createAccount() {
    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value;
    const confirm = document.getElementById('confirmPassword').value;

    if (!username) {
        showAlert('Username is required.', 'danger');
        return;
    }
    if (password.length < 8) {
        showAlert('Password must be at least 8 characters.', 'danger');
        return;
    }
    if (password !== confirm) {
        showAlert('Passwords do not match.', 'danger');
        return;
    }

    const btn = document.getElementById('btn-create-account');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" role="status"></span>Creating&hellip;';

    try {
        const res = await fetch('/api/setup/account', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, password: password, confirmPassword: confirm })
        });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        setStep(3);
    } catch (_e) {
        showAlert('Request failed - please try again.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-arrow-right me-2"></i>Create Account';
    }
}

async function testProvider() {
    if (!selectedProvider) {
        showAlert('Please select a provider first.', 'warning');
        return;
    }

    const body = { provider: selectedProvider };
    const apiKeyEl = document.getElementById('apiKey-' + selectedProvider);
    const baseUrlEl = document.getElementById('baseUrl-' + selectedProvider);
    if (apiKeyEl) body.apiKey = apiKeyEl.value.trim();
    if (baseUrlEl) body.baseUrl = baseUrlEl.value.trim();

    const btn = document.getElementById('btn-test');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" role="status"></span>Testing&hellip;';

    try {
        const res = await fetch('/api/setup/ai-provider/validate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }

        const sel = document.getElementById('default-model');
        sel.innerHTML = '<option value="">- select a model -</option>';
        (data.models || []).forEach(function (m) {
            const opt = document.createElement('option');
            opt.value = m.id;
            opt.textContent = m.displayName || m.id;
            sel.appendChild(opt);
        });
        if (sel.options.length > 1) sel.selectedIndex = 1;

        document.getElementById('model-select-area').classList.remove('hidden');
        showAlert((data.models ? data.models.length : 0) + ' model(s) discovered. Select your default below.', 'success');
    } catch (_e) {
        showAlert('Connection test failed - please check your credentials.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-plug me-2"></i>Test Connection &amp; Discover Models';
    }
}

async function saveProvider() {
    const model = document.getElementById('default-model').value;
    if (!model) {
        showAlert('Please select a default model.', 'warning');
        return;
    }

    const body = {
        provider: selectedProvider,
        defaultModel: model,
        setAsGlobal: document.getElementById('set-global').checked
    };
    const apiKeyEl = document.getElementById('apiKey-' + selectedProvider);
    const baseUrlEl = document.getElementById('baseUrl-' + selectedProvider);
    if (apiKeyEl) body.apiKey = apiKeyEl.value.trim();
    if (baseUrlEl) body.baseUrl = baseUrlEl.value.trim();

    const btn = document.getElementById('btn-save-provider');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" role="status"></span>Saving&hellip;';

    try {
        const res = await fetch('/api/setup/ai-provider', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data.error) {
            showAlert(data.error, 'danger');
            return;
        }
        setStep(4);
    } catch (_e) {
        showAlert('Save failed - please try again.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-arrow-right me-2"></i>Save &amp; Continue';
    }
}

function selectDbBackend(backend) {
    selectedDbBackend = backend;
    document.getElementById('db-btn-mongo').classList.toggle('selected', backend === 'mongo');
    document.getElementById('db-btn-redis').classList.toggle('selected', backend === 'redis');
    document.getElementById('db-btn-nitrite').classList.toggle('selected', backend === 'nitrite');
    document.getElementById('db-btn-couchbase').classList.toggle('selected', backend === 'couchbase');
    document.getElementById('db-mongo-fields').classList.toggle('hidden', backend !== 'mongo');
    document.getElementById('db-redis-fields').classList.toggle('hidden', backend !== 'redis');
    document.getElementById('db-nitrite-fields').classList.toggle('hidden', backend !== 'nitrite');
    document.getElementById('db-couchbase-fields').classList.toggle('hidden', backend !== 'couchbase');
}

function showDbAlert(msg, type) {
    document.getElementById('db-alert').innerHTML =
        '<div class="alert alert-' + type + ' py-2 small mb-0">' + msg + '</div>';
}

function clearDbAlert() {
    document.getElementById('db-alert').innerHTML = '';
}

async function configureDatabase() {
    const settings = selectedDbBackend === 'redis' ? {
        backend: 'redis',
        host: document.getElementById('db-redis-host').value.trim(),
        port: parseInt(document.getElementById('db-redis-port').value, 10) || 6379,
        password: document.getElementById('db-redis-password').value
    } : selectedDbBackend === 'couchbase' ? {
        backend: 'couchbase',
        host: document.getElementById('db-couchbase-host').value.trim(),
        port: parseInt(document.getElementById('db-couchbase-port').value, 10) || 8091,
        database: document.getElementById('db-couchbase-bucket').value.trim() || 'vork',
        username: document.getElementById('db-couchbase-username').value.trim(),
        password: document.getElementById('db-couchbase-password').value
    } : selectedDbBackend === 'nitrite' ? {
        backend: 'nitrite',
        database: document.getElementById('db-nitrite-path').value.trim() || 'conf.d/vork.db'
    } : {
        backend: 'mongo',
        host: document.getElementById('db-mongo-host').value.trim(),
        port: parseInt(document.getElementById('db-mongo-port').value, 10) || 27017,
        database: document.getElementById('db-mongo-database').value.trim() || 'vork',
        username: document.getElementById('db-mongo-username').value.trim(),
        password: document.getElementById('db-mongo-password').value
    };

    const btn = document.getElementById('btn-configure-db');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Connecting...';
    clearDbAlert();

    try {
        const res = await fetch('/api/setup/database', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(settings)
        });
        const data = await res.json();
        if (data.error) {
            showDbAlert('<i class="fa-solid fa-circle-xmark me-1"></i>' + escapeHtml(data.error), 'danger');
            return;
        }
        if (data.restartRequired) {
            showDbAlert('<i class="fa-solid fa-arrows-rotate me-1"></i>Restarting to apply database settings...', 'info');
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Waiting for restart...';
            await pollAfterRestart();
            showDbAlert('<i class="fa-solid fa-circle-check me-1"></i>Server restarted. Continuing setup.', 'success');
        }
        setStep(2);
    } catch (_e) {
        showDbAlert('Request failed - please try again.', 'danger');
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-plug me-2"></i>Connect &amp; Continue';
    }
}

async function pollAfterRestart() {
    await new Promise(function (r) { setTimeout(r, 2000); });
    for (let i = 0; i < 60; i++) {
        try {
            const r = await fetch('/api/setup/status');
            if (r.ok) return;
        } catch (_e) {
            // server still restarting
        }
        await new Promise(function (r) { setTimeout(r, 1000); });
    }
}

async function loadInitialState() {
    try {
        const results = await Promise.all([
            fetch('/api/setup/status'),
            fetch('/api/setup/database')
        ]);
        const statusRes = results[0];
        const dbRes = results[1];
        const status = statusRes.ok ? await statusRes.json() : {};
        const dbData = dbRes.ok ? await dbRes.json() : {};

        if (dbData.settings) {
            prefillDbForm(dbData.settings);
        }

        const databaseConfigured = Boolean(status.databaseConfigured || dbData.configured);
        const accountConfigured = Boolean(status.accountConfigured);
        const aiConfigured = Boolean(status.aiConfigured);

        if (databaseConfigured && accountConfigured && aiConfigured) {
            setStep(4);
        } else if (databaseConfigured && accountConfigured && !aiConfigured) {
            setStep(3);
        } else if (databaseConfigured) {
            setStep(2);
        }
    } catch (_e) {
        // DB step is shown by default.
    }
}

function prefillDbForm(s) {
    if (s.backend) selectDbBackend(s.backend);
    const isRedis = s.backend === 'redis';
    const isCouchbase = s.backend === 'couchbase';
    const isNitrite = s.backend === 'nitrite';
    if (isNitrite) {
        if (s.database) document.getElementById('db-nitrite-path').value = s.database;
        return;
    }
    if (s.host) {
        const hostId = isRedis
            ? 'db-redis-host'
            : isCouchbase
                ? 'db-couchbase-host'
                : 'db-mongo-host';
        const el = document.getElementById(hostId);
        if (el) el.value = s.host;
    }
    if (s.port) {
        const portId = isRedis
            ? 'db-redis-port'
            : isCouchbase
                ? 'db-couchbase-port'
                : 'db-mongo-port';
        const el = document.getElementById(portId);
        if (el) el.value = s.port;
    }
    if (!isRedis && s.database) {
        const dbId = isCouchbase ? 'db-couchbase-bucket' : 'db-mongo-database';
        const el = document.getElementById(dbId);
        if (el) el.value = s.database;
    }
    if (!isRedis && s.username) {
        const userId = isCouchbase ? 'db-couchbase-username' : 'db-mongo-username';
        const el = document.getElementById(userId);
        if (el) el.value = s.username;
    }
}

function continueToLogin() {
    window.location.href = '/login';
}

loadInitialState();
