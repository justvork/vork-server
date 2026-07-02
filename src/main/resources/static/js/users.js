(function () {
    async function jsonRequest(url, options) {
        const response = await fetch(url, {
            headers: { "Content-Type": "application/json" },
            credentials: "same-origin",
            ...options,
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(body.message || body.error || "Request failed");
        }
        return body;
    }

    function notify(message) {
        window.alert(message);
    }

    const createForm = document.getElementById("create-user-form");
    if (createForm) {
        createForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const formData = new FormData(createForm);
            try {
                await jsonRequest("/api/users", {
                    method: "POST",
                    body: JSON.stringify({
                        username: formData.get("username"),
                        password: formData.get("password"),
                        role: formData.get("role"),
                    }),
                });
                notify("User created.");
                window.location.reload();
            } catch (error) {
                notify(error.message);
            }
        });
    }

    document.querySelectorAll(".user-role-select").forEach((select) => {
        select.addEventListener("change", async () => {
            const username = select.dataset.username;
            try {
                await jsonRequest(`/api/users/${encodeURIComponent(username)}/role`, {
                    method: "PUT",
                    body: JSON.stringify({ role: select.value }),
                });
                notify(`Role updated for ${username}.`);
                window.location.reload();
            } catch (error) {
                notify(error.message);
                window.location.reload();
            }
        });
    });

    document.querySelectorAll(".toggle-enabled-btn").forEach((button) => {
        button.addEventListener("click", async () => {
            const username = button.dataset.username;
            const currentlyEnabled = button.dataset.enabled === "true";
            try {
                await jsonRequest(`/api/users/${encodeURIComponent(username)}/enabled`, {
                    method: "PUT",
                    body: JSON.stringify({ enabled: !currentlyEnabled }),
                });
                notify(`User ${currentlyEnabled ? "disabled" : "enabled"}: ${username}`);
                window.location.reload();
            } catch (error) {
                notify(error.message);
            }
        });
    });

    document.querySelectorAll(".reset-password-btn").forEach((button) => {
        button.addEventListener("click", async () => {
            const username = button.dataset.username;
            const password = window.prompt(`Enter a new password for ${username} (min 8 chars):`);
            if (!password) {
                return;
            }
            try {
                await jsonRequest(`/api/users/${encodeURIComponent(username)}/password/reset`, {
                    method: "PUT",
                    body: JSON.stringify({ newPassword: password }),
                });
                notify(`Password reset for ${username}.`);
            } catch (error) {
                notify(error.message);
            }
        });
    });

    document.querySelectorAll(".delete-user-btn").forEach((button) => {
        button.addEventListener("click", async () => {
            const username = button.dataset.username;
            if (!window.confirm(`Delete user ${username}?`)) {
                return;
            }
            try {
                await jsonRequest(`/api/users/${encodeURIComponent(username)}`, {
                    method: "DELETE",
                });
                notify(`Deleted user ${username}.`);
                window.location.reload();
            } catch (error) {
                notify(error.message);
            }
        });
    });
})();
