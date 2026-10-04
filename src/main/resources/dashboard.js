/* Boutons/infos persistants : communs, personnels et transférables. */
(() => {
    "use strict";
    let user, snapshot, active = false, hydrating = false;
    let hidden = new Set(), timer, writeQueue = Promise.resolve(), lastQueued = "";
    let definitions = "", manager, legacy;

    const safeJson = (value, fallback) => { try { return JSON.parse(value) ?? fallback; } catch (_) { return fallback; } };
    const old = key => { try { return localStorage.getItem(key); } catch (_) { return null; } };
    async function request(path, values, method = "POST") {
        const response = await window.xiaovvAuth.fetch(path, values === undefined ? {} : {
            method, body: new URLSearchParams(values)
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Opération impossible.");
        return data;
    }
    function status(node, text, error = false) {
        node.textContent = text;
        node.classList.toggle("error", error);
    }
    const notify = error => showToast("Boutons et infos", error.message || String(error), "error");
    function layoutValues() {
        return {
            selected: JSON.stringify(selected), order: JSON.stringify(order), sizes: JSON.stringify(sizes),
            wallOnly: document.body.classList.contains("wall-only") ? "1" : "0",
            controlOrder: JSON.stringify(window.xiaovvMqttBridge.getOrder()), hidden: [...hidden].join(","),
            ...window.xiaovvAudio.preferences()
        };
    }
    function saveLayout() {
        if (!active || hydrating) return;
        clearTimeout(timer);
        timer = setTimeout(() => flushLayout().catch(notify), 500);
    }
    function flushLayout() {
        const values = layoutValues(), signature = JSON.stringify(values);
        if (signature === lastQueued) return writeQueue;
        lastQueued = signature;
        writeQueue = writeQueue.catch(() => {}).then(() => request("/api/dashboard/layout", values)).catch(error => {
            lastQueued = ""; throw error;
        });
        return writeQueue;
    }
    function saveVisibility() {
        [...actionTiles, ...window.xiaovvMqttBridge.getInfos()].forEach(item => {
            if (item.active) hidden.delete(item.id); else hidden.add(item.id);
        });
        saveLayout();
    }
    function markTiles() {
        document.querySelectorAll("#action-wall [data-action-id], #action-wall [data-info-id]").forEach(tile => {
            const id = tile.dataset.actionId || tile.dataset.infoId;
            const item = snapshot.items.find(x => x.id === id);
            if (!item) return;
            const header = tile.querySelector(".action-tile-header, .info-tile-header");
            header?.querySelector(".dashboard-sharing")?.remove();
            const badge = document.createElement("span");
            badge.className = "dashboard-sharing";
            badge.textContent = item.scope === "common" ? "Commun" : "Personnel";
            header?.appendChild(badge);
            if (!item.editable) tile.querySelectorAll("[title='Configurer ce bouton'], .info-settings")
                .forEach(button => (button.hidden = true));
        });
    }
    function applyItems() {
        const tiles = snapshot.items.map(item => ({ ...item, active: item.enabled === "true" && !hidden.has(item.id) }));
        actionTiles = tiles.filter(item => item.kind === "action");
        window.xiaovvMqttBridge.setInfos(tiles.filter(item => item.kind === "info"));
        hydrating = true;
        try { renderActionTiles(); } finally { hydrating = false; }
    }
    async function reload() {
        snapshot = await request("/api/dashboard");
        definitions = JSON.stringify(snapshot.items);
        applyItems();
    }
    async function start() {
        user = await window.xiaovvAuth.ready();
        if (!user.authenticated) return;
        legacy = {
            actions: safeJson(old("xiaovvActionTilesV1"), []), infos: safeJson(old("xiaovvInfoTilesV1"), []),
            selected: safeJson(old("xiaovvLiveSelected"), []), order: safeJson(old("xiaovvLiveOrder"), []),
            sizes: safeJson(old("xiaovvLiveSizes"), {}), controlOrder: safeJson(old("xiaovvControlOrderV2"), [])
        };
        if (!Array.isArray(legacy.actions)) legacy.actions = [];
        if (!Array.isArray(legacy.infos)) legacy.infos = [];
        snapshot = await request("/api/dashboard");
        const layout = snapshot.layout;
        selected = safeJson(layout.selected, []); if (!Array.isArray(selected)) selected = [];
        order = safeJson(layout.order, []); if (!Array.isArray(order)) order = [];
        sizes = safeJson(layout.sizes, {}); if (!sizes || typeof sizes !== "object" || Array.isArray(sizes)) sizes = {};
        const controls = safeJson(layout.controlOrder, []);
        window.xiaovvMqttBridge.setOrder(Array.isArray(controls) ? controls : []);
        hidden = new Set((layout.hidden || "").split(',').filter(Boolean));
        document.body.classList.toggle("wall-only", layout.wallOnly === "1");
        saveState = saveLayout;
        saveActionTiles = saveVisibility;
        setWallOnly = enabled => { document.body.classList.toggle("wall-only", enabled); saveLayout(); };
        active = true;
        const originalRenderActionTiles = renderActionTiles;
        renderActionTiles = (...args) => { originalRenderActionTiles(...args); markTiles(); };
        window.xiaovvAudio.start(layout, saveLayout);
        definitions = JSON.stringify(snapshot.items);
        applyItems();
        const button = document.createElement("button");
        button.type = "button"; button.textContent = "Boutons et infos"; button.id = "dashboard-button";
        button.onclick = () => open();
        const accountButton = document.getElementById("token-button");
        button.className = accountButton.className;
        accountButton.parentNode.insertBefore(button, accountButton);
        if (legacy.actions.length + legacy.infos.length && !old("xiaovvDashboardImported:" + user.username)) {
            const notice = document.createElement("div");
            notice.className = "dashboard-import-notice"; notice.id = "dashboard-import-notice";
            const text = document.createElement("span");
            text.textContent = "Tes anciens boutons et infos sont encore dans ce navigateur. ";
            const importButton = document.createElement("button");
            importButton.type = "button"; importButton.textContent = "Les importer sur le serveur";
            importButton.onclick = () => open(undefined, undefined, true);
            notice.append(text, importButton);
            document.getElementById("action-wall").before(notice);
        }
        window.addEventListener("pagehide", () => {
            // keepalive permet d'enregistrer la dernière disposition avant un changement de page.
            if (active && !hydrating) window.xiaovvAuth.fetch("/api/dashboard/layout", {
                method: "POST", body: new URLSearchParams(layoutValues()), keepalive: true
            }).catch(() => {});
        });
        setInterval(async () => {
            try {
                if (!active || document.hidden) return;
                const next = await request("/api/dashboard");
                const signature = JSON.stringify(next.items);
                if (signature !== definitions) { snapshot = next; definitions = signature; applyItems(); }
            } catch (error) { console.warn("Actualisation du tableau indisponible"); }
        }, 10000);
    }

    async function execute(action, button) {
        if (button) { button.disabled = true; button.classList.add("busy"); }
        try {
            const data = await request("/api/dashboard/" + encodeURIComponent(action.id) + "/execute", {});
            showToast(action.name, action.method + " envoyé — HTTP " + data.status, "info");
        } catch (error) { notify(error); }
        finally { if (button) { button.disabled = false; button.classList.remove("busy"); } }
    }

    async function open(kind, id, showImport = false) {
        if (!active) return;
        if (manager) manager.remove();
        const box = document.createElement("dialog"); manager = box;
        box.className = "auth-dialog dashboard-dialog";
        box.setAttribute("aria-labelledby", "dashboard-title");
        box.innerHTML = `
          <div class="auth-heading"><h2 id="dashboard-title">Boutons et infos</h2><button id="dashboard-close" type="button">Fermer</button></div>
          <p class="auth-muted">Les éléments communs sont disponibles pour tous. Les éléments personnels suivent leur propriétaire sur ses appareils.</p>
          <div id="dashboard-owner-field" hidden><label for="dashboard-view-owner">Tableau personnel affiché</label><select id="dashboard-view-owner"></select></div>
          <div class="auth-actions dashboard-toolbar"><button id="dashboard-add-action" type="button">+ Bouton</button><button id="dashboard-add-info" type="button">+ Info</button><button id="dashboard-refresh" type="button">Actualiser</button></div>
          <div id="dashboard-items" class="dashboard-items"></div>
          <p id="dashboard-message" class="auth-message" role="status" aria-live="polite"></p>
          <section id="dashboard-transfer" hidden>
            <h3 id="dashboard-transfer-title">Transférer un élément</h3>
            <label for="dashboard-recipient">Destinataire</label><select id="dashboard-recipient"></select>
            <label for="dashboard-transfer-mode">Opération</label><select id="dashboard-transfer-mode"><option value="copy">Copier : conserver aussi l'original</option><option value="move">Déplacer : changer de propriétaire</option></select>
            <p class="auth-muted">Les droits caméra du destinataire restent identiques.</p>
            <div class="auth-actions"><button id="dashboard-transfer-cancel" type="button">Annuler</button><button id="dashboard-transfer-submit" class="auth-primary" type="button">Transférer</button></div>
          </section>
          <section id="dashboard-import" hidden>
            <h3>Importer les anciens éléments de ce navigateur</h3>
            <p id="dashboard-import-count" class="auth-muted"></p>
            <label for="dashboard-import-scope">Destination de l'import</label><select id="dashboard-import-scope"><option value="personal">Mes éléments personnels</option></select>
            <label class="dashboard-check"><input id="dashboard-import-layout" type="checkbox" checked> Reprendre aussi ma disposition</label>
            <p class="auth-muted">Les données locales sont conservées. Relancer l'import ne crée pas de doublons.</p>
            <div class="auth-actions"><button id="dashboard-import-submit" type="button" class="auth-primary">Importer sur le serveur</button></div>
          </section>
          <form id="dashboard-form" hidden>
            <hr class="auth-separator"><h3 id="dashboard-edit-title">Ajouter un bouton</h3>
            <label for="dashboard-name">Nom</label><input id="dashboard-name" required maxlength="80">
            <div class="auth-columns"><div><label for="dashboard-scope">Partage</label><select id="dashboard-scope"><option value="personal">Personnel</option></select></div>
              <div id="dashboard-personal-owner-field" hidden><label for="dashboard-personal-owner">Propriétaire</label><select id="dashboard-personal-owner"></select></div></div>
            <p id="dashboard-share-help" class="auth-muted"></p>
            <div id="dashboard-action-fields"><label for="dashboard-url">URL HTTP ou HTTPS</label><input id="dashboard-url" type="url" maxlength="4096">
              <label for="dashboard-method">Méthode</label><select id="dashboard-method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select></div>
            <div id="dashboard-info-fields" hidden>
              <label for="dashboard-server">Serveur MQTT</label><select id="dashboard-server"></select>
              <label for="dashboard-topic">Topic exact</label><input id="dashboard-topic" maxlength="512">
              <div class="auth-columns"><div><label for="dashboard-value-type">Type de valeur</label><select id="dashboard-value-type"><option value="number">Nombre</option><option value="text">Texte</option><option value="boolean">ON / OFF</option></select></div>
                <div><label for="dashboard-unit">Unité</label><input id="dashboard-unit" maxlength="32" placeholder="°C"></div></div>
              <label for="dashboard-measure">Type de mesure</label><select id="dashboard-measure">
                <option value="">Autre / non défini</option><option value="temperature">Température</option><option value="power">Puissance</option>
                <option value="energy">Énergie</option><option value="humidity">Humidité</option><option value="light">Luminosité</option>
                <option value="pressure">Pression</option><option value="voltage">Tension</option><option value="current">Intensité</option>
                <option value="speed">Vitesse</option><option value="flow">Débit</option></select>
              <div class="auth-columns"><div><label for="dashboard-decimals">Décimales</label><select id="dashboard-decimals"><option value="auto">Automatique</option><option>0</option><option>1</option><option>2</option><option>3</option></select></div>
                <div><label for="dashboard-stale">Périmée après (secondes ; 0 = jamais)</label><input id="dashboard-stale" type="number" min="0" max="31536000" step="1" value="300"></div></div>
              <label for="dashboard-json-path">Extraction JSON (facultative)</label><input id="dashboard-json-path" maxlength="512" placeholder="$.temperature">
            </div>
            <label class="dashboard-check"><input id="dashboard-enabled" type="checkbox" checked> Élément disponible</label>
            <p id="dashboard-form-message" class="auth-message" role="status" aria-live="polite"></p>
            <div class="auth-actions"><button id="dashboard-edit-cancel" type="button">Annuler</button><button id="dashboard-save" type="submit" class="auth-primary">Enregistrer</button></div>
          </form>`;
        document.body.appendChild(box); box.showModal();
        box.querySelector("#dashboard-close").onclick = () => box.close();
        box.addEventListener("close", () => { box.remove(); if (manager === box) manager = null; });
        const node = selector => box.querySelector(selector);
        const form = node("#dashboard-form"), message = node("#dashboard-message");
        let viewOwner = user.username, viewItems = snapshot.items, editing, editKind = kind || "action", transferItem;
        const recipientNames = await request("/api/dashboard/recipients").catch(() => [user.username]);
        const option = (select, value, label = value) => { const element = document.createElement("option"); element.value = value; element.textContent = label; select.appendChild(element); };
        recipientNames.forEach(name => {
            option(node("#dashboard-view-owner"), name); option(node("#dashboard-personal-owner"), name);
            option(node("#dashboard-recipient"), name);
        });
        node("#dashboard-view-owner").value = user.username;
        if (user.role === "admin") {
            node("#dashboard-owner-field").hidden = false;
            option(node("#dashboard-scope"), "common", "Commun à tous");
            option(node("#dashboard-import-scope"), "common", "Commun à tous");
            node("#dashboard-import-scope").value = "common";
        }
        function shareVisibility() {
            const common = node("#dashboard-scope").value === "common";
            node("#dashboard-personal-owner-field").hidden = common || user.role !== "admin";
            node("#dashboard-share-help").textContent = common
                ? "Tous les comptes pourront utiliser cet élément. Seuls les administrateurs peuvent le modifier."
                : "Cet élément sera visible uniquement par son propriétaire et les administrateurs.";
        }
        function edit(item, requestedKind = "action") {
            editing = item || null; editKind = item ? item.kind : requestedKind;
            form.reset(); form.hidden = false;
            node("#dashboard-transfer").hidden = true;
            node("#dashboard-edit-title").textContent = (item ? "Modifier " : "Ajouter ") + (editKind === "action" ? "un bouton" : "une information");
            node("#dashboard-name").value = item?.name || "";
            node("#dashboard-scope").value = item?.scope || "personal";
            node("#dashboard-personal-owner").value = item?.owner || viewOwner;
            node("#dashboard-url").value = item?.url || "";
            node("#dashboard-method").value = item?.method || "GET";
            node("#dashboard-server").value = item?.serverId || node("#dashboard-server").options[0]?.value || "";
            node("#dashboard-topic").value = item?.topic || "";
            node("#dashboard-value-type").value = item?.valueType || "number";
            node("#dashboard-measure").value = item?.measureType || "";
            node("#dashboard-unit").value = item?.unit || "";
            node("#dashboard-decimals").value = item?.decimals || "auto";
            node("#dashboard-stale").value = item?.staleSeconds || "300";
            node("#dashboard-json-path").value = item?.jsonPath || "";
            node("#dashboard-enabled").checked = !item || item.enabled === "true";
            node("#dashboard-action-fields").hidden = editKind !== "action";
            node("#dashboard-info-fields").hidden = editKind !== "info";
            node("#dashboard-url").required = editKind === "action";
            node("#dashboard-server").required = editKind === "info";
            node("#dashboard-topic").required = editKind === "info";
            status(node("#dashboard-form-message"), ""); shareVisibility();
            node("#dashboard-name").focus();
            form.scrollIntoView({ block: "nearest" });
        }
        function renderList() {
            const list = node("#dashboard-items"); list.replaceChildren();
            if (!viewItems.length) { list.textContent = "Aucun bouton ou info pour le moment."; return; }
            viewItems.forEach(item => {
                const row = document.createElement("div"); row.className = "dashboard-item-row";
                const label = document.createElement("div"); label.className = "dashboard-item-name";
                const name = document.createElement("strong"); name.textContent = item.name;
                const detail = document.createElement("span"); detail.className = "auth-muted";
                detail.textContent = (item.kind === "action" ? "Bouton" : "Info MQTT") + " · " + (item.scope === "common" ? "Commun à tous" : "Personnel : " + item.owner) +
                    (item.enabled === "true" ? "" : " · désactivé");
                label.append(name, detail);
                const actions = document.createElement("div"); actions.className = "auth-row-actions";
                function button(text, action) { const b = document.createElement("button"); b.type = "button"; b.textContent = text; b.onclick = action; actions.appendChild(b); return b; }
                if (viewOwner === user.username) button(hidden.has(item.id) ? "Afficher" : "Masquer", () => {
                    if (hidden.has(item.id)) hidden.delete(item.id); else hidden.add(item.id);
                    applyItems(); saveLayout(); renderList();
                });
                if (item.editable) {
                    button("Modifier", () => edit(item));
                    button(item.scope === "common" ? "Copier" : "Transférer", () => {
                        transferItem = item; form.hidden = true; node("#dashboard-transfer").hidden = false;
                        node("#dashboard-transfer-title").textContent = "Transférer « " + item.name + " »";
                        node("#dashboard-transfer-mode").value = "copy";
                        node("#dashboard-transfer-mode option[value='move']").disabled = item.scope === "common";
                        node("#dashboard-transfer").scrollIntoView({ block: "nearest" });
                    });
                    button("Supprimer", () => {
                        const b = button("Confirmer la suppression", async () => {
                            b.disabled = true;
                            try { await request("/api/dashboard/" + item.id, { revision: item.revision }, "DELETE"); await refreshList(); status(message, "Élément supprimé."); }
                            catch (error) { status(message, error.message, true); b.disabled = false; }
                        });
                        b.className = "auth-danger";
                    });
                }
                row.append(label, actions); list.appendChild(row);
            });
        }
        async function refreshList() {
            const data = await request("/api/dashboard?owner=" + encodeURIComponent(viewOwner));
            viewItems = data.items;
            await reload(); renderList();
        }
        node("#dashboard-view-owner").onchange = async () => {
            viewOwner = node("#dashboard-view-owner").value; form.hidden = true;
            try { await refreshList(); } catch (error) { status(message, error.message, true); }
        };
        node("#dashboard-refresh").onclick = async () => { try { await refreshList(); } catch (error) { status(message, error.message, true); } };
        node("#dashboard-add-action").onclick = () => edit(null, "action");
        node("#dashboard-add-info").onclick = () => edit(null, "info");
        node("#dashboard-scope").onchange = shareVisibility;
        node("#dashboard-edit-cancel").onclick = () => (form.hidden = true);
        node("#dashboard-transfer-cancel").onclick = () => (node("#dashboard-transfer").hidden = true);
        node("#dashboard-transfer-submit").onclick = async () => {
            const button = node("#dashboard-transfer-submit"); button.disabled = true;
            try {
                await request("/api/dashboard/" + transferItem.id + "/transfer", {
                    revision: transferItem.revision, target: node("#dashboard-recipient").value, mode: node("#dashboard-transfer-mode").value
                });
                node("#dashboard-transfer").hidden = true; await refreshList();
                status(message, "Élément transféré vers " + node("#dashboard-recipient").value + ".");
            } catch (error) { status(message, error.message, true); }
            finally { button.disabled = false; }
        };
        form.onsubmit = async event => {
            event.preventDefault(); const button = node("#dashboard-save"); button.disabled = true;
            const values = {
                kind: editKind, name: node("#dashboard-name").value, scope: node("#dashboard-scope").value,
                owner: user.role === "admin" ? node("#dashboard-personal-owner").value : user.username,
                enabled: String(node("#dashboard-enabled").checked)
            };
            if (editing) { values.id = editing.id; values.revision = editing.revision; }
            if (editKind === "action") Object.assign(values, { url: node("#dashboard-url").value, method: node("#dashboard-method").value });
            else Object.assign(values, { serverId: node("#dashboard-server").value, topic: node("#dashboard-topic").value,
                valueType: node("#dashboard-value-type").value, unit: node("#dashboard-unit").value,
                decimals: node("#dashboard-decimals").value, staleSeconds: node("#dashboard-stale").value,
                jsonPath: node("#dashboard-json-path").value, measureType: node("#dashboard-measure").value });
            try { await request("/api/dashboard", values); form.hidden = true; await refreshList(); status(message, "Élément enregistré sur le serveur."); }
            catch (error) { status(node("#dashboard-form-message"), error.message, true); }
            finally { button.disabled = false; }
        };
        const importCount = legacy.actions.length + legacy.infos.length;
        node("#dashboard-import").hidden = !importCount;
        node("#dashboard-import-count").textContent = legacy.actions.length + " bouton(s) et " + legacy.infos.length + " information(s).";
        node("#dashboard-import-submit").onclick = async () => {
            const button = node("#dashboard-import-submit"); button.disabled = true;
            const mapping = new Map();
            try {
                for (const [itemKind, list] of [["action", legacy.actions], ["info", legacy.infos]]) {
                    for (const item of list) {
                        const values = { ...item, kind: itemKind, scope: node("#dashboard-import-scope").value,
                            owner: user.username, enabled: "true", importId: item.id };
                        delete values.id; delete values.revision;
                        const imported = await request("/api/dashboard", values);
                        mapping.set(itemKind + ":" + item.id, itemKind + ":" + imported.id);
                        if (item.active === false) hidden.add(imported.id);
                    }
                }
                if (node("#dashboard-import-layout").checked) {
                    selected = Array.isArray(legacy.selected) ? legacy.selected : [];
                    order = Array.isArray(legacy.order) ? legacy.order : [];
                    sizes = Object.fromEntries(Object.entries(legacy.sizes || {}).map(([key, value]) => [mapping.get(key) || key, value]));
                    window.xiaovvMqttBridge.setOrder((Array.isArray(legacy.controlOrder) ? legacy.controlOrder : []).map(key => mapping.get(key) || key));
                }
                await flushLayout(); await refreshList(); await refreshCameras(true);
                try { localStorage.setItem("xiaovvDashboardImported:" + user.username, "1"); } catch (_) {}
                document.getElementById("dashboard-import-notice")?.remove();
                node("#dashboard-import").hidden = true;
                status(message, "Import terminé. Les données locales ont été conservées.");
            } catch (error) { status(message, "Import interrompu : " + error.message + " Les éléments déjà importés sont conservés.", true); }
            finally { button.disabled = false; }
        };
        renderList();
        try {
            const sources = await request("/api/mqtt/sources");
            (sources.brokers || []).forEach(source => option(node("#dashboard-server"), source.id, source.name));
        } catch (error) { status(message, error.message, true); }
        const item = id ? viewItems.find(x => x.id === id) : null;
        if (item && item.editable) edit(item);
        else if (kind && !id) edit(null, kind);
        if (showImport) node("#dashboard-import").scrollIntoView({ block: "nearest" });
    }
    window.xiaovvDashboard = { start, open, execute, saveLayout, saveVisibility, get active() { return active; } };
})();
