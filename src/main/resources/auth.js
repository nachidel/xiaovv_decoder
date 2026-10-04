/* Connexion et comptes Xiaovv. La session reste dans un cookie HttpOnly. */
(() => {
    "use strict";
    let currentSession;
    const sessionReady = fetch("/api/auth/session", { cache: "no-store", credentials: "same-origin" })
        .then(response => {
            if (!response.ok) throw new Error("Impossible de vérifier la connexion.");
            return response.json();
        }).then(session => (currentSession = session));

    async function apiFetch(url, options = {}) {
        const session = await sessionReady;
        if (!session.authenticated) {
            location.replace("/login");
            throw new Error("Connexion nécessaire");
        }
        const headers = new Headers(options.headers || {});
        headers.set("X-CSRF-Token", currentSession.csrfToken);
        const response = await fetch(url, { ...options, headers, credentials: "same-origin" });
        if (response.status === 401 && url !== "/api/auth/password") {
            location.replace("/login");
            throw new Error("Votre session a expiré. Reconnectez-vous.");
        }
        return response;
    }

    async function result(response) {
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Opération impossible.");
        return data;
    }

    function message(element, text, error = false) {
        element.textContent = text;
        element.classList.toggle("error", error);
    }

    async function submitForm(form, status, action) {
        const buttons = [...form.querySelectorAll("button")];
        buttons.forEach(button => (button.disabled = true));
        message(status, "");
        try { await action(); }
        catch (error) { message(status, error.message, true); }
        finally { buttons.forEach(button => (button.disabled = false)); }
    }

    function dialog(title, content) {
        const previous = document.getElementById("auth-dialog");
        if (previous) previous.remove();
        const box = document.createElement("dialog");
        box.id = "auth-dialog";
        box.className = "auth-dialog";
        box.setAttribute("aria-labelledby", "auth-dialog-title");
        box.innerHTML = `<div class="auth-heading"><h2 id="auth-dialog-title"></h2>
            <button type="button" id="auth-close" aria-label="Fermer">Fermer</button></div>${content}`;
        box.querySelector("h2").textContent = title;
        box.querySelector("#auth-close").onclick = () => box.close();
        box.addEventListener("close", () => box.remove());
        document.body.appendChild(box);
        box.showModal();
        return box;
    }

    async function openAccount() {
        const session = await sessionReady;
        const box = dialog("Mon compte", `
            <p id="auth-identity"></p>
            <form id="auth-password-form">
              <h3>Changer mon mot de passe</h3>
              <label for="auth-current-password">Mot de passe actuel</label>
              <input id="auth-current-password" type="password" autocomplete="current-password" required maxlength="128">
              <label for="auth-new-password">Nouveau mot de passe</label>
              <input id="auth-new-password" type="password" autocomplete="new-password" required minlength="12" maxlength="128">
              <label for="auth-confirm-password">Confirmer le nouveau mot de passe</label>
              <input id="auth-confirm-password" type="password" autocomplete="new-password" required maxlength="128">
              <p class="auth-muted">Au moins 12 caractères.</p>
              <p id="auth-password-message" class="auth-message" role="status" aria-live="polite"></p>
              <div class="auth-actions"><button type="submit" class="auth-primary">Changer le mot de passe</button></div>
            </form>
            <hr class="auth-separator">
            <div class="auth-actions"><button id="auth-logout" type="button">Se déconnecter</button></div>
            <p id="auth-logout-message" class="auth-message" role="status"></p>`);
        box.querySelector("#auth-identity").textContent = session.username + " · " +
            (session.role === "admin" ? "Administrateur" : "Utilisateur");
        const form = box.querySelector("#auth-password-form");
        form.onsubmit = event => {
            event.preventDefault();
            submitForm(form, box.querySelector("#auth-password-message"), async () => {
                const password = box.querySelector("#auth-new-password").value;
                if (password !== box.querySelector("#auth-confirm-password").value) throw new Error("Les mots de passe ne correspondent pas.");
                await result(await apiFetch("/api/auth/password", { method: "POST", body: new URLSearchParams({
                    currentPassword: box.querySelector("#auth-current-password").value, password
                }) }));
                currentSession = await fetch("/api/auth/session", { cache: "no-store" }).then(result);
                form.reset();
                message(box.querySelector("#auth-password-message"), "Mot de passe modifié. Les autres sessions sont déconnectées.");
            });
        };
        box.querySelector("#auth-logout").onclick = async () => {
            try {
                await result(await apiFetch("/api/auth/logout", { method: "POST" }));
                location.replace("/login");
            } catch (error) { message(box.querySelector("#auth-logout-message"), error.message, true); }
        };
    }

    async function openUsers() {
        const box = dialog("Utilisateurs", `
            <p class="auth-muted">Les administrateurs gèrent les comptes et la configuration. Les utilisateurs accèdent aux caméras.</p>
            <div class="auth-table-wrap"><table><thead><tr><th>Nom</th><th>Rôle</th><th>Caméras</th><th>Actions</th></tr></thead>
              <tbody id="auth-user-list"></tbody></table></div>
            <p id="auth-users-message" class="auth-message" role="status" aria-live="polite"></p>
            <div id="auth-delete-confirm" hidden>
              <p id="auth-delete-label"></p><div class="auth-actions">
                <button id="auth-delete-cancel" type="button">Annuler</button>
                <button id="auth-delete-submit" type="button" class="auth-danger">Confirmer la suppression</button>
              </div>
            </div>
            <hr class="auth-separator">
            <form id="auth-user-form">
              <h3 id="auth-edit-title">Créer un utilisateur</h3>
              <div class="auth-columns">
                <div><label for="auth-username">Nom d'utilisateur</label>
                  <input id="auth-username" autocomplete="off" required minlength="3" maxlength="32" pattern="[A-Za-z0-9][A-Za-z0-9_.-]{2,31}"></div>
                <div><label for="auth-role">Rôle</label><select id="auth-role"><option value="user">Utilisateur</option><option value="admin">Administrateur</option></select></div>
              </div>
              <section id="auth-camera-access">
                <h3>Caméras autorisées</h3>
                <p class="auth-muted">L'utilisateur pourra voir et piloter uniquement les caméras cochées.</p>
                <div class="auth-row-actions"><button id="auth-cameras-all" type="button">Toutes</button>
                  <button id="auth-cameras-none" type="button">Aucune</button></div>
                <div id="auth-camera-checkboxes" class="auth-camera-checkboxes"></div>
              </section>
              <p id="auth-admin-cameras" class="auth-muted" hidden>Un administrateur a accès à toutes les caméras.</p>
              <label for="auth-user-password">Mot de passe</label>
              <input id="auth-user-password" type="password" autocomplete="new-password" required minlength="12" maxlength="128">
              <label for="auth-user-confirm">Confirmer le mot de passe</label>
              <input id="auth-user-confirm" type="password" autocomplete="new-password" maxlength="128">
              <p id="auth-password-help" class="auth-muted">Au moins 12 caractères.</p>
              <p id="auth-user-form-message" class="auth-message" role="status" aria-live="polite"></p>
              <div class="auth-actions"><button id="auth-create-new" type="button" hidden>Nouvel utilisateur</button>
                <button id="auth-user-save" class="auth-primary" type="submit">Créer l'utilisateur</button></div>
            </form>`);
        const form = box.querySelector("#auth-user-form");
        const status = box.querySelector("#auth-user-form-message");
        let editing = null;
        function edit(account) {
            editing = account ? account.username : null;
            form.reset();
            box.querySelector("#auth-username").value = editing || "";
            box.querySelector("#auth-username").readOnly = !!editing;
            box.querySelector("#auth-role").value = account ? account.role : "user";
            box.querySelectorAll("#auth-camera-checkboxes input").forEach(input => {
                input.checked = !!account && (account.cameras || []).includes(input.value);
            });
            cameraAccessVisibility();
            box.querySelector("#auth-user-password").required = !editing;
            box.querySelector("#auth-edit-title").textContent = editing ? "Modifier " + editing : "Créer un utilisateur";
            box.querySelector("#auth-user-save").textContent = editing ? "Enregistrer" : "Créer l'utilisateur";
            box.querySelector("#auth-password-help").textContent = editing ? "Laisser le mot de passe vide pour le conserver. Sinon, au moins 12 caractères." : "Au moins 12 caractères.";
            box.querySelector("#auth-create-new").hidden = !editing;
            message(status, "");
            box.querySelector(editing ? "#auth-role" : "#auth-username").focus();
        }
        async function loadUsers() {
            const accounts = await result(await apiFetch("/api/auth/users"));
            const list = box.querySelector("#auth-user-list");
            list.replaceChildren();
            for (const account of accounts) {
                const row = document.createElement("tr");
                const name = document.createElement("td");
                name.textContent = account.username;
                const role = document.createElement("td");
                role.textContent = account.role === "admin" ? "Administrateur" : "Utilisateur";
                const cameras = document.createElement("td");
                cameras.textContent = account.role === "admin" ? "Toutes" : ((account.cameras || []).join(", ") || "Aucune");
                const actions = document.createElement("td");
                const buttons = document.createElement("div");
                buttons.className = "auth-row-actions";
                const modify = document.createElement("button");
                modify.type = "button";
                modify.textContent = "Modifier";
                modify.onclick = () => edit(account);
                const remove = document.createElement("button");
                remove.type = "button";
                remove.className = "auth-danger";
                remove.textContent = "Supprimer";
                remove.onclick = () => {
                    box.querySelector("#auth-delete-label").textContent = "Supprimer le compte « " + account.username + " » ?";
                    box.querySelector("#auth-delete-confirm").hidden = false;
                    box.querySelector("#auth-delete-submit").onclick = async () => {
                        try {
                            await result(await apiFetch("/api/auth/users/" + encodeURIComponent(account.username), { method: "DELETE" }));
                            box.querySelector("#auth-delete-confirm").hidden = true;
                            if (account.username === currentSession.username) { location.replace("/login"); return; }
                            await loadUsers();
                            message(box.querySelector("#auth-users-message"), "Utilisateur supprimé.");
                        } catch (error) { message(box.querySelector("#auth-users-message"), error.message, true); }
                    };
                };
                buttons.append(modify, remove);
                actions.appendChild(buttons);
                row.append(name, role, cameras, actions);
                list.appendChild(row);
            }
        }
        function cameraAccessVisibility() {
            const admin = box.querySelector("#auth-role").value === "admin";
            box.querySelector("#auth-camera-access").hidden = admin;
            box.querySelector("#auth-admin-cameras").hidden = !admin;
        }
        box.querySelector("#auth-role").onchange = cameraAccessVisibility;
        box.querySelector("#auth-cameras-all").onclick = () => {
            box.querySelectorAll("#auth-camera-checkboxes input").forEach(input => (input.checked = true));
        };
        box.querySelector("#auth-cameras-none").onclick = () => {
            box.querySelectorAll("#auth-camera-checkboxes input").forEach(input => (input.checked = false));
        };
        box.querySelector("#auth-delete-cancel").onclick = () => (box.querySelector("#auth-delete-confirm").hidden = true);
        box.querySelector("#auth-create-new").onclick = () => edit(null);
        form.onsubmit = event => {
            event.preventDefault();
            submitForm(form, status, async () => {
                const password = box.querySelector("#auth-user-password").value;
                if (password !== box.querySelector("#auth-user-confirm").value) throw new Error("Les mots de passe ne correspondent pas.");
                const values = new URLSearchParams({ username: box.querySelector("#auth-username").value,
                    role: box.querySelector("#auth-role").value, password,
                    cameras: [...box.querySelectorAll("#auth-camera-checkboxes input:checked")].map(input => input.value).join(",") });
                await result(await apiFetch(editing ? "/api/auth/users/" + encodeURIComponent(editing) : "/api/auth/users", { method: "POST", body: values }));
                if (editing === currentSession.username) { location.replace("/login"); return; }
                await loadUsers();
                edit(null);
                message(status, "Utilisateur enregistré.");
            });
        };
        try {
            const config = await result(await apiFetch("/api/config/cameras"));
            const cameras = config.cameras || [];
            const choices = box.querySelector("#auth-camera-checkboxes");
            if (!cameras.length) choices.textContent = "Aucune caméra configurée.";
            for (const camera of cameras) {
                const label = document.createElement("label");
                const input = document.createElement("input");
                input.type = "checkbox";
                input.value = camera.id;
                const name = document.createElement("span");
                name.textContent = camera.id + (camera.enabled ? "" : " (désactivée)");
                label.append(input, name);
                choices.appendChild(label);
            }
            await loadUsers();
        }
        catch (error) { message(box.querySelector("#auth-users-message"), error.message, true); }
    }

    async function initialize() {
        const form = document.getElementById("login-form");
        try {
            const session = await sessionReady;
            if (form) {
                if (session.authenticated) { location.replace("/"); return; }
                const setup = session.setupRequired;
                const button = document.getElementById("login-submit");
                document.getElementById("setup-fields").hidden = !setup;
                document.getElementById("login-confirm").required = setup;
                document.getElementById("setup-token").required = setup;
                document.getElementById("login-password").autocomplete = setup ? "new-password" : "current-password";
                if (setup) {
                    document.getElementById("login-password").minLength = 12;
                    document.getElementById("login-title").textContent = "Créer l'administrateur";
                    document.getElementById("login-description").textContent = "Choisissez les identifiants de votre premier compte.";
                    button.textContent = "Créer mon compte";
                    try { document.getElementById("setup-token").value = localStorage.getItem("xiaovvApiToken") || ""; } catch (_) { }
                }
                button.disabled = false;
                form.onsubmit = event => {
                    event.preventDefault();
                    submitForm(form, document.getElementById("login-message"), async () => {
                        const password = document.getElementById("login-password").value;
                        if (setup && password !== document.getElementById("login-confirm").value) throw new Error("Les mots de passe ne correspondent pas.");
                        const headers = setup ? { "X-API-Token": document.getElementById("setup-token").value.trim() } : {};
                        await result(await fetch(setup ? "/api/auth/setup" : "/api/auth/login", {
                            method: "POST", headers, credentials: "same-origin", body: new URLSearchParams({
                                username: document.getElementById("login-username").value,
                                password
                            })
                        }));
                        try { localStorage.removeItem("xiaovvApiToken"); } catch (_) { }
                        location.replace("/");
                    });
                };
                return;
            }
            if (!session.authenticated) { location.replace("/login"); return; }
            try { localStorage.removeItem("xiaovvApiToken"); } catch (_) { }
            const button = document.getElementById("token-button");
            if (button) {
                button.textContent = "Mon compte · " + session.username;
                if (session.role === "admin") {
                    const users = document.createElement("button");
                    users.type = "button";
                    users.className = button.className;
                    users.textContent = "Utilisateurs";
                    users.id = "users-button";
                    users.onclick = openUsers;
                    button.parentNode.insertBefore(users, button);
                } else {
                    for (const id of ["config-button", "mqtt-button"]) {
                        const element = document.getElementById(id);
                        if (element) element.hidden = true;
                    }
                }
            }
        } catch (error) {
            const status = document.getElementById("login-message");
            if (status) message(status, error.message, true);
            else console.error("Connexion Xiaovv indisponible");
        }
    }
    window.xiaovvAuth = { fetch: apiFetch, openAccount, ready: () => sessionReady };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initialize);
    else initialize();
})();
