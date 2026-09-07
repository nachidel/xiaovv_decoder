/*
 * Xiaovv - extension Dashboard MQTT
 *
 * Prérequis : ce script est injecté APRÈS la déclaration des fonctions
 * createTile/createActionTile/syncWall/normalizeState/moveBefore et AVANT
 * l'initialisation finale du mur.
 */
(function () {
    const STORAGE_INFO_TILES = "xiaovvInfoTilesV1";
    const STORAGE_CONTROL_ORDER = "xiaovvControlOrderV2";
    const INFO_POLL_MS = 2000;

    let infoTiles = [];
    let editingInfoId = null;
    let mqttServers = [];
    let editingMqttId = null;
    let controlOrder = [];
    let draggedControlKey = null;
    const pollBusy = new Set();

    /* ======================== STYLE ======================== */

    const style = document.createElement("style");
    style.textContent = `
        .dashboard-item { box-sizing:border-box; }
        .dashboard-item.drop-before { outline:2px solid #70aee8; outline-offset:3px; }
        .dashboard-item.dragging { opacity:.45; }

        .action-tile.dashboard-item {
            width:250px; min-width:180px; max-width:100%;
            resize:horizontal; overflow:auto;
        }
        .action-tile.dashboard-item .action-tile-header {
            cursor:grab; user-select:none;
        }

        .info-tile {
            position:relative; display:flex; flex-direction:column;
            width:250px; min-width:180px; min-height:128px; max-width:100%;
            overflow:hidden; resize:horizontal;
            border:1px solid #3a3f45; border-radius:10px;
            background:#202328;
            box-shadow:0 6px 18px rgba(0,0,0,.22);
        }
        .info-tile-header {
            display:flex; align-items:center; min-height:40px;
            padding:6px 7px 6px 10px;
            border-bottom:1px solid #353a40; background:#24282d;
            cursor:grab; user-select:none;
        }
        .info-source-badge {
            margin-right:7px; padding:3px 7px; border-radius:999px;
            background:#353a40; color:#aeb4ba;
            font-size:10px; font-weight:bold;
        }
        .info-name {
            min-width:0; overflow:hidden; text-overflow:ellipsis;
            white-space:nowrap; font-weight:bold;
        }
        .info-spacer { flex:1; }
        .info-icon-button {
            display:inline-flex; align-items:center; justify-content:center;
            width:31px; height:29px; margin-left:5px;
            border:0; border-radius:6px; background:transparent;
            color:#c9cdd1; font-size:16px; cursor:pointer;
        }
        .info-icon-button:hover { background:#3a3f45; color:white; }

        .info-body {
            flex:1; display:flex; flex-direction:column;
            align-items:center; justify-content:center;
            min-height:86px; padding:11px 13px;
        }
        .info-value-line {
            display:flex; align-items:baseline; justify-content:center;
            gap:7px; max-width:100%;
        }
        .info-value {
            overflow:hidden; text-overflow:ellipsis; white-space:nowrap;
            color:white; font-size:32px; font-weight:bold; line-height:1.1;
        }
        .info-unit { color:#aeb4ba; font-size:15px; font-weight:bold; }
        .info-meta {
            width:100%; margin-top:9px; overflow:hidden;
            text-overflow:ellipsis; white-space:nowrap;
            color:#858b92; font-size:10px; text-align:center;
        }
        .info-tile.stale .info-value,
        .info-tile.offline .info-value { color:#e2b84f; }
        .info-tile.error .info-value { color:#ef7777; }

        .info-grid,.mqtt-grid {
            display:grid; grid-template-columns:repeat(2,minmax(0,1fr));
            gap:0 12px;
        }
        .full { grid-column:1/-1; }
        .small-help { margin:-5px 0 12px; color:#858b92; font-size:11px; }

        .mqtt-meta {
            margin-bottom:12px; padding:9px 10px;
            border:1px solid #383d43; border-radius:8px;
            background:#181b1f; color:#9aa0a6;
            font-size:11px; overflow-wrap:anywhere;
        }
        .mqtt-toolbar {
            display:flex; align-items:center; justify-content:space-between;
            flex-wrap:wrap; gap:8px; margin-bottom:12px;
        }
        .mqtt-toolbar button,.mqtt-row-buttons button {
            min-height:36px; padding:7px 10px; border:0; border-radius:7px;
            background:#41464d; color:white; cursor:pointer;
        }
        .mqtt-toolbar button { background:#365d82; }
        .mqtt-list { display:flex; flex-direction:column; gap:8px; }
        .mqtt-row {
            display:flex; align-items:center; flex-wrap:wrap; gap:9px;
            padding:10px; border:1px solid #383d43; border-radius:8px;
            background:#181b1f;
        }
        .mqtt-row-main { flex:1; min-width:180px; }
        .mqtt-row-name { font-weight:bold; }
        .mqtt-row-detail { margin-top:4px; color:#858b92; font-size:11px; }
        .mqtt-badge {
            padding:3px 7px; border-radius:999px;
            font-size:10px; font-weight:bold;
        }
        .mqtt-badge.connected { background:#274a34; color:#70d88b; }
        .mqtt-badge.disconnected { background:#4a3e26; color:#e2b84f; }
        .mqtt-row-buttons { display:flex; flex-wrap:wrap; gap:6px; }
        .mqtt-row-buttons .danger { background:#763f3f; }

        .config-check {
            display:flex; align-items:center; gap:8px; min-height:42px;
            margin-bottom:12px;
        }
        .config-check input {
            width:18px !important; height:18px !important;
            min-height:18px !important; margin:0 !important; padding:0 !important;
        }

        @media(max-width:700px) {
            .info-grid,.mqtt-grid { grid-template-columns:1fr; }
            .info-tile,.action-tile.dashboard-item {
                width:100% !important; min-width:0; resize:none;
            }
        }
    `;
    document.head.appendChild(style);

    /* ======================== MODALES ======================== */

    const modalHtml = `
    <div id="info-modal-backdrop" class="action-modal-backdrop">
      <div class="action-modal" role="dialog" aria-modal="true">
        <h2 id="info-modal-title">Ajouter une information</h2>

        <section id="saved-info-section" class="action-modal-section">
          <div class="action-modal-label">Informations déjà créées mais masquées</div>
          <div id="saved-info" class="saved-actions"></div>
        </section>

        <section class="action-modal-section">
          <div class="info-grid">
            <div class="full">
              <label class="action-modal-label" for="info-name">Nom</label>
              <input id="info-name" type="text" maxlength="80"
                     placeholder="Ex. Température piscine">
            </div>

            <div>
              <label class="action-modal-label" for="info-server">Serveur MQTT</label>
              <select id="info-server"></select>
            </div>

            <div>
              <label class="action-modal-label" for="info-type">Type de valeur</label>
              <select id="info-type">
                <option value="number">Numérique</option>
                <option value="text">Texte</option>
                <option value="boolean">Booléen</option>
              </select>
            </div>

            <div class="full">
              <label class="action-modal-label" for="info-topic">Topic MQTT</label>
              <input id="info-topic" type="text"
                     placeholder="piscine/status/temperature:100">
            </div>

            <div>
              <label class="action-modal-label" for="info-measure">Type de mesure</label>
              <select id="info-measure">
                <option value="">Autre / non défini</option>
                <option value="temperature">Température</option>
                <option value="power">Puissance</option>
                <option value="energy">Énergie</option>
                <option value="humidity">Humidité</option>
                <option value="light">Luminosité</option>
                <option value="pressure">Pression</option>
                <option value="voltage">Tension</option>
                <option value="current">Intensité</option>
                <option value="speed">Vitesse</option>
                <option value="flow">Débit</option>
              </select>
            </div>

            <div>
              <label class="action-modal-label" for="info-unit">Unité</label>
              <input id="info-unit" type="text" list="info-unit-list" placeholder="°C">
              <datalist id="info-unit-list">
                <option value="°C"><option value="°F"><option value="%">
                <option value="W"><option value="kW"><option value="Wh">
                <option value="kWh"><option value="V"><option value="A">
                <option value="mA"><option value="Hz"><option value="lx">
                <option value="lm"><option value="bar"><option value="Pa">
                <option value="hPa"><option value="L"><option value="L/min">
                <option value="m³"><option value="m³/h"><option value="ppm">
                <option value="mm"><option value="cm"><option value="m">
                <option value="km/h"><option value="rpm"><option value="dB">
                <option value="ms"><option value="s"><option value="min">
                <option value="h">
              </datalist>
            </div>

            <div>
              <label class="action-modal-label" for="info-decimals">Décimales</label>
              <select id="info-decimals">
                <option value="auto">Auto</option>
                <option value="0">0</option><option value="1">1</option>
                <option value="2">2</option><option value="3">3</option>
              </select>
            </div>

            <div>
              <label class="action-modal-label" for="info-stale">Périmée après (s)</label>
              <input id="info-stale" type="number" min="0" step="1" value="300">
            </div>

            <div class="full">
              <label class="action-modal-label" for="info-json-path">
                Extraction JSON (facultatif)
              </label>
              <input id="info-json-path" type="text" placeholder="$.temperatureSortie">
              <div class="small-help">
                Laissez vide si le payload est directement la valeur.
                Chemins simples : $.champ.sousChamp et tableaux [0].
              </div>
            </div>
          </div>
        </section>

        <div class="action-modal-actions">
          <button id="info-cancel" type="button">Annuler</button>
          <button id="info-save" class="primary" type="button">Enregistrer</button>
        </div>
      </div>
    </div>

    <div id="mqtt-config-backdrop" class="action-modal-backdrop">
      <div class="action-modal" role="dialog" aria-modal="true">
        <h2>Configuration MQTT</h2>

        <div id="mqtt-list-panel">
          <div id="mqtt-meta" class="mqtt-meta">Chargement...</div>
          <div class="mqtt-toolbar">
            <strong>Serveurs MQTT globaux</strong>
            <button id="mqtt-add" type="button">+ Ajouter un serveur</button>
          </div>
          <div id="mqtt-list" class="mqtt-list"></div>
          <div class="action-modal-actions">
            <button id="mqtt-close" type="button">Fermer</button>
          </div>
        </div>

        <div id="mqtt-form-panel" style="display:none">
          <div class="mqtt-grid">
            <div>
              <label class="action-modal-label" for="mqtt-id">Identifiant</label>
              <input id="mqtt-id" type="text" maxlength="80" placeholder="jeedom">
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-name">Nom</label>
              <input id="mqtt-name" type="text" maxlength="80" placeholder="MQTT Jeedom">
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-host">Adresse / IP</label>
              <input id="mqtt-host" type="text" placeholder="192.168.1.10">
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-port">Port</label>
              <input id="mqtt-port" type="number" min="1" max="65535" value="1883">
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-username">Utilisateur</label>
              <input id="mqtt-username" type="text" autocomplete="username">
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-password-env">Variable d'environnement du mot de passe</label>
              <input id="mqtt-password-env" type="text" autocomplete="off"
                     placeholder="Vide = nom automatique">
              <div id="mqtt-password-env-help" class="small-help"></div>
            </div>
            <div>
              <label class="action-modal-label" for="mqtt-password">Mot de passe local (ancien mode)</label>
              <input id="mqtt-password" type="password" autocomplete="new-password"
                     placeholder="Déconseillé : laisser vide">
            </div>
            <div>
              <label class="config-check">
                <input id="mqtt-enabled" type="checkbox" checked>
                <span>Serveur activé</span>
              </label>
            </div>
            <div>
              <label class="config-check">
                <input id="mqtt-tls" type="checkbox">
                <span>TLS</span>
              </label>
            </div>
            <div class="full">
              <label class="config-check">
                <input id="mqtt-clear-password" type="checkbox">
                <span>Supprimer le mot de passe stocké dans mqtt.properties</span>
              </label>
              <div id="mqtt-password-state" class="small-help"></div>
            </div>
          </div>
          <div class="small-help">
            Recommandé : stocker le mot de passe dans une variable d'environnement
            de la configuration Run/Debug, jamais dans le projet.
            En TLS, le truststore Java par défaut est utilisé.
          </div>
          <div class="action-modal-actions">
            <button id="mqtt-back" type="button">Retour</button>
            <button id="mqtt-save" class="primary" type="button">Enregistrer</button>
          </div>
        </div>
      </div>
    </div>`;

    document.body.insertAdjacentHTML("beforeend", modalHtml);

    document.getElementById("mqtt-id").addEventListener(
        "input",
        () => updateMqttPasswordHelp(
            editingMqttId
                ? mqttServers.find(x => x.id === editingMqttId)
                : null
        )
    );

    document.getElementById("mqtt-password-env").addEventListener(
        "input",
        () => updateMqttPasswordHelp(
            editingMqttId
                ? mqttServers.find(x => x.id === editingMqttId)
                : null
        )
    );

    /* ======================== BOUTONS UI ======================== */

    const addActionButton = document.getElementById("add-action-button");
    const addInfoButton = document.createElement("button");
    addInfoButton.id = "add-info-button";
    addInfoButton.type = "button";
    addInfoButton.textContent = "+ Info";
    addActionButton.parentNode.insertBefore(addInfoButton, addActionButton.nextSibling);

    const tokenButton = document.getElementById("token-button");
    const mqttButton = document.createElement("button");
    mqttButton.id = "mqtt-button";
    mqttButton.className = "button";
    mqttButton.type = "button";
    mqttButton.textContent = "MQTT";
    tokenButton.parentNode.insertBefore(mqttButton, tokenButton);

    /* ======================== INFOS : STOCKAGE ======================== */

    function createInfoId() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }
        return "info-" + Date.now() + "-" + Math.random().toString(16).slice(2);
    }

    function infoById(id) {
        return infoTiles.find(x => x.id === id) || null;
    }

    function loadInfoTiles() {
        try {
            const raw = JSON.parse(localStorage.getItem(STORAGE_INFO_TILES) || "[]");
            if (!Array.isArray(raw)) {
                infoTiles = [];
                return;
            }

            infoTiles = raw
                .filter(x => x && typeof x.id === "string" &&
                             typeof x.name === "string" &&
                             typeof x.serverId === "string" &&
                             typeof x.topic === "string")
                .map(x => ({
                    id: x.id,
                    name: x.name,
                    serverId: x.serverId,
                    topic: x.topic,
                    valueType: ["number","text","boolean"].includes(x.valueType)
                        ? x.valueType : "number",
                    measureType: x.measureType || "",
                    unit: x.unit || "",
                    decimals: ["auto","0","1","2","3"].includes(String(x.decimals))
                        ? String(x.decimals) : "auto",
                    jsonPath: x.jsonPath || "",
                    staleSeconds: Number.isFinite(Number(x.staleSeconds))
                        ? Math.max(0, Number(x.staleSeconds)) : 300,
                    active: x.active !== false
                }));
        } catch (_) {
            infoTiles = [];
        }
    }

    function saveInfoTiles() {
        localStorage.setItem(STORAGE_INFO_TILES, JSON.stringify(infoTiles));
    }

    /* ======================== ORDRE ZONE HAUTE ======================== */

    const actionKey = id => "action:" + id;
    const infoKey = id => "info:" + id;

    function loadControlOrder() {
        try {
            const stored = JSON.parse(
                localStorage.getItem(STORAGE_CONTROL_ORDER) || "[]"
            );
            controlOrder = Array.isArray(stored)
                ? stored.filter(x => typeof x === "string")
                : [];
        } catch (_) {
            controlOrder = [];
        }
    }

    function saveControlOrder() {
        localStorage.setItem(
            STORAGE_CONTROL_ORDER,
            JSON.stringify(controlOrder)
        );
    }

    function allControlKeys() {
        return [
            ...actionTiles.map(a => actionKey(a.id)),
            ...infoTiles.map(i => infoKey(i.id))
        ];
    }

    function ensureControlOrder() {
        const known = allControlKeys();
        controlOrder = controlOrder.filter(k => known.includes(k));
        known.forEach(k => {
            if (!controlOrder.includes(k)) controlOrder.push(k);
        });
        saveControlOrder();
    }

    function applyControlOrder() {
        document.querySelectorAll("#action-wall [data-control-key]")
            .forEach(item => {
                const index = controlOrder.indexOf(item.dataset.controlKey);
                item.style.order = index >= 0 ? String(index) : "9999";
            });
    }

    function moveControlBefore(source, target) {
        const sourceIndex = controlOrder.indexOf(source);
        const targetIndex = controlOrder.indexOf(target);
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return;

        controlOrder.splice(sourceIndex, 1);
        const newTarget = controlOrder.indexOf(target);
        controlOrder.splice(newTarget, 0, source);
        saveControlOrder();
        applyControlOrder();
    }

    function clearControlDropState() {
        document.querySelectorAll("#action-wall [data-control-key]")
            .forEach(item => item.classList.remove("dragging", "drop-before"));
    }

    function attachControlDrag(item, handle, key) {
        item.classList.add("dashboard-item");
        item.dataset.controlKey = key;
        handle.draggable = true;
        handle.title = "Glisser pour déplacer dans la zone boutons & informations";

        handle.addEventListener("dragstart", event => {
            draggedControlKey = key;
            item.classList.add("dragging");
            if (event.dataTransfer) {
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/x-xiaovv-control", key);
            }
        });

        handle.addEventListener("dragend", () => {
            draggedControlKey = null;
            clearControlDropState();
        });

        item.addEventListener("dragover", event => {
            if (!draggedControlKey || draggedControlKey === key) return;
            event.preventDefault();
            item.classList.add("drop-before");
            if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
        });

        item.addEventListener("dragleave", () => {
            item.classList.remove("drop-before");
        });

        item.addEventListener("drop", event => {
            if (!draggedControlKey) return;
            event.preventDefault();
            const source = draggedControlKey;
            draggedControlKey = null;
            item.classList.remove("drop-before");
            if (source && source !== key) moveControlBefore(source, key);
        });
    }

    function restoreControlWidth(item, key, minimum) {
        const saved = sizes[key];
        if (saved && Number.isFinite(saved.width)) {
            item.style.width = Math.max(minimum, saved.width) + "px";
        }

        if (typeof ResizeObserver !== "undefined") {
            const observer = new ResizeObserver(entries => {
                const entry = entries[0];
                if (!entry) return;
                const width = Math.round(entry.contentRect.width);
                if (width < minimum) return;
                sizes[key] = { width };
                saveState();
            });
            observer.observe(item);
        }
    }

    /*
     * IMPORTANT : on ne touche plus du tout à createTile(), normalizeState(),
     * applyTileOrder() ou syncWall() des caméras.
     * Le mur vidéo reste 100 % géré par le code d'origine.
     */

    const originalCreateActionTile = createActionTile;
    createActionTile = function(action) {
        const tile = originalCreateActionTile(action);
        const key = actionKey(action.id);
        const header = tile.querySelector(".action-tile-header");
        if (header) attachControlDrag(tile, header, key);
        tile.querySelectorAll("button").forEach(button => {
            button.draggable = false;
        });
        restoreControlWidth(tile, key, 180);
        return tile;
    };

    /* ======================== FORMATAGE MQTT ======================== */

    function extractJsonPath(raw, path) {
        path = (path || "").trim();
        if (!path) return raw;

        let current = JSON.parse(raw);
        if (path === "$") return current;

        if (path.startsWith("$.")) path = path.substring(2);
        else if (path.startsWith("$")) path = path.substring(1);

        path = path.replace(/\[(\d+)\]/g, ".$1");
        const tokens = path.split(".").filter(Boolean);

        for (const token of tokens) {
            if (current === null || typeof current === "undefined") {
                throw new Error("Chemin JSON introuvable");
            }
            current = current[token];
        }

        if (typeof current === "undefined") {
            throw new Error("Chemin JSON introuvable");
        }

        return current;
    }

    function formatPayload(info, payload) {
        const value = extractJsonPath(payload, info.jsonPath);

        if (info.valueType === "number") {
            const n = Number(value);
            if (!Number.isFinite(n)) {
                throw new Error("La valeur MQTT n'est pas numérique");
            }
            return info.decimals === "auto"
                ? String(n)
                : n.toFixed(Number(info.decimals));
        }

        if (info.valueType === "boolean") {
            const s = String(value).toLowerCase();
            if (value === true || value === 1 || ["true","on","1"].includes(s)) return "ON";
            if (value === false || value === 0 || ["false","off","0"].includes(s)) return "OFF";
        }

        return typeof value === "object" ? JSON.stringify(value) : String(value);
    }

    /* ======================== TUILE INFO ======================== */

    function createInfoTile(info) {
        const tile = document.createElement("section");
        tile.className = "info-tile dashboard-item";
        tile.dataset.infoId = info.id;
        tile.dataset.dashboardKey = infoKey(info.id);

        tile.innerHTML = `
          <div class="info-tile-header">
            <span class="info-source-badge">MQTT</span>
            <div class="info-name"></div>
            <div class="info-spacer"></div>
            <button class="info-icon-button info-settings" type="button"
                    title="Configurer">⚙</button>
            <button class="info-icon-button info-hide" type="button"
                    title="Retirer du dashboard">×</button>
          </div>
          <div class="info-body">
            <div class="info-value-line">
              <div class="info-value">--</div>
              <div class="info-unit"></div>
            </div>
            <div class="info-meta">En attente de la première valeur MQTT…</div>
          </div>`;

        tile.querySelector(".info-name").textContent = info.name;
        tile.querySelector(".info-unit").textContent = info.unit || "";

        const header = tile.querySelector(".info-tile-header");
        attachControlDrag(tile, header, infoKey(info.id));
        restoreControlWidth(tile, infoKey(info.id), 180);

        tile.querySelectorAll("button").forEach(b => {
            b.draggable = false;
            b.addEventListener("pointerdown", e => e.stopPropagation());
        });

        tile.querySelector(".info-settings").addEventListener("click", e => {
            e.stopPropagation();
            openInfoModal(info.id);
        });

        tile.querySelector(".info-hide").addEventListener("click", e => {
            e.stopPropagation();
            info.active = false;
            saveInfoTiles();
            renderActionTiles();
            showToast(
                info.name,
                "Information masquée. Sa configuration reste disponible via « + Info ».",
                "info"
            );
        });

        refreshInfoTile(info);
        return tile;
    }

    function updateInfoTile(info, data) {
        const tile = document.querySelector(
            '#action-wall .info-tile[data-info-id="' + CSS.escape(info.id) + '"]'
        );
        if (!tile) return;

        const valueNode = tile.querySelector(".info-value");
        const metaNode = tile.querySelector(".info-meta");
        tile.classList.remove("stale", "offline", "error");

        if (!data || data.success !== true) {
            valueNode.textContent = "--";
            metaNode.textContent = data && data.error ? data.error : "Lecture MQTT impossible";
            tile.classList.add("error");
            return;
        }

        if (data.payload === null || typeof data.payload === "undefined") {
            valueNode.textContent = "--";
            metaNode.textContent = data.connected
                ? "Connecté — en attente d'une valeur"
                : "Broker hors ligne";
            if (!data.connected) tile.classList.add("offline");
            return;
        }

        try {
            valueNode.textContent = formatPayload(info, data.payload);
        } catch (e) {
            valueNode.textContent = "--";
            metaNode.textContent = e.message || "Valeur invalide";
            tile.classList.add("error");
            return;
        }

        const ts = Number(data.receivedAt);
        const hasDate = Number.isFinite(ts) && ts > 0;
        const stale = hasDate &&
            Number(info.staleSeconds) > 0 &&
            Date.now() - ts > Number(info.staleSeconds) * 1000;

        if (stale) {
            tile.classList.add("stale");
            metaNode.textContent = "Donnée périmée — " +
                new Date(ts).toLocaleString("fr-FR");
        } else if (!data.connected) {
            tile.classList.add("offline");
            metaNode.textContent = hasDate
                ? "Broker hors ligne — dernière valeur " +
                  new Date(ts).toLocaleString("fr-FR")
                : "Broker hors ligne";
        } else {
            metaNode.textContent = hasDate
                ? "Mis à jour " + new Date(ts).toLocaleString("fr-FR")
                : "Connecté";
        }
    }

    async function refreshInfoTile(info) {
        if (!info || !info.active || pollBusy.has(info.id)) return;
        pollBusy.add(info.id);

        try {
            const response = await apiFetch(
                "/api/mqtt/value?server=" + encodeURIComponent(info.serverId) +
                "&topic=" + encodeURIComponent(info.topic)
            );

            let data = null;
            try { data = await response.json(); } catch (_) {}

            if (!response.ok) {
                updateInfoTile(info, data || {
                    success:false,
                    error:"HTTP " + response.status
                });
            } else {
                updateInfoTile(info, data);
            }
        } catch (e) {
            updateInfoTile(info, {
                success:false,
                error:e.message || "MQTT indisponible"
            });
        } finally {
            pollBusy.delete(info.id);
        }
    }

    function refreshAllInfoTiles() {
        infoTiles.filter(i => i.active).forEach(refreshInfoTile);
    }

    /* ======================== ZONE BOUTONS & INFORMATIONS ======================== */

    function appendInfoTilesToControlWall() {
        const controlWall = document.getElementById("action-wall");

        infoTiles
            .filter(info => info.active)
            .forEach(info => {
                const tile = createInfoTile(info);
                controlWall.appendChild(tile);
            });

        ensureControlOrder();
        applyControlOrder();
    }

    /*
     * renderActionTiles() d'origine écrit déjà les boutons dans #action-wall.
     * On le conserve tel quel et on ajoute simplement les infos MQTT après.
     * AUCUN bouton/info n'est ajouté dans #wall.
     */
    const originalRenderActionTiles = renderActionTiles;
    renderActionTiles = function() {
        originalRenderActionTiles();
        appendInfoTilesToControlWall();
        enforcePhysicalZones();
    };

    /* ======================== API SERVEURS MQTT ======================== */

    async function loadMqttServers() {
        const response = await apiFetch("/api/mqtt/servers");
        const data = await response.json();

        if (!response.ok || !data || data.success !== true) {
            throw new Error(
                data && data.error
                    ? data.error
                    : "Impossible de lire les serveurs MQTT."
            );
        }

        mqttServers = Array.isArray(data.brokers) ? data.brokers : [];
        return data;
    }

    async function populateServerSelect(selectedId) {
        try {
            await loadMqttServers();
        } catch (e) {
            mqttServers = [];
            showToast("MQTT", e.message || "Chargement MQTT impossible", "error");
        }

        const select = document.getElementById("info-server");
        select.innerHTML = "";

        mqttServers.filter(s => s.enabled).forEach(server => {
            const option = document.createElement("option");
            option.value = server.id;
            option.textContent =
                server.name + " (" + server.host + ":" + server.port + ")";
            select.appendChild(option);
        });

        if (selectedId &&
            Array.from(select.options).some(o => o.value === selectedId)) {
            select.value = selectedId;
        }
    }

    /* ======================== MODALE INFO ======================== */

    function renderSavedInfo() {
        const container = document.getElementById("saved-info");
        container.innerHTML = "";

        const archived = infoTiles.filter(i => !i.active);
        if (archived.length === 0) {
            const empty = document.createElement("div");
            empty.className = "saved-actions-empty";
            empty.textContent = "Aucune ancienne information à restaurer.";
            container.appendChild(empty);
            return;
        }

        archived.forEach(info => {
            const row = document.createElement("div");
            row.className = "saved-action";

            const main = document.createElement("div");
            main.className = "saved-action-info";
            main.innerHTML =
                '<div class="saved-action-name"></div>' +
                '<div class="saved-action-detail"></div>';
            main.querySelector(".saved-action-name").textContent = info.name;
            main.querySelector(".saved-action-detail").textContent =
                info.serverId + " — " + info.topic +
                (info.unit ? " — " + info.unit : "");

            const buttons = document.createElement("div");
            buttons.className = "saved-action-buttons";

            const restore = document.createElement("button");
            restore.type = "button";
            restore.textContent = "Restaurer";
            restore.onclick = () => {
                info.active = true;
                saveInfoTiles();
                renderSavedInfo();
                renderActionTiles();
                showToast(info.name, "Information restaurée.", "info");
            };

            const edit = document.createElement("button");
            edit.type = "button";
            edit.textContent = "Configurer";
            edit.onclick = () => openInfoModal(info.id);

            const remove = document.createElement("button");
            remove.type = "button";
            remove.className = "danger";
            remove.textContent = "Supprimer définitivement";
            remove.onclick = () => {
                if (!window.confirm(
                    "Supprimer définitivement l'information « " + info.name + " » ?"
                )) return;

                infoTiles = infoTiles.filter(x => x.id !== info.id);
                delete sizes[infoKey(info.id)];
                saveInfoTiles();
                ensureControlOrder();
                renderSavedInfo();
                renderActionTiles();
            };

            buttons.append(restore, edit, remove);
            row.append(main, buttons);
            container.appendChild(row);
        });
    }

    async function openInfoModal(editId) {
        editingInfoId = editId || null;
        const info = editingInfoId ? infoById(editingInfoId) : null;

        document.getElementById("info-modal-title").textContent =
            info ? "Configurer l'information" : "Ajouter une information";
        document.getElementById("saved-info-section").style.display =
            info ? "none" : "";

        await populateServerSelect(info ? info.serverId : null);

        document.getElementById("info-name").value = info ? info.name : "";
        document.getElementById("info-topic").value = info ? info.topic : "";
        document.getElementById("info-type").value = info ? info.valueType : "number";
        document.getElementById("info-measure").value = info ? info.measureType : "";
        document.getElementById("info-unit").value = info ? info.unit : "";
        document.getElementById("info-decimals").value = info ? info.decimals : "auto";
        document.getElementById("info-stale").value =
            info ? String(info.staleSeconds) : "300";
        document.getElementById("info-json-path").value = info ? info.jsonPath : "";

        if (!info) renderSavedInfo();

        document.getElementById("info-modal-backdrop").classList.add("open");
        setTimeout(() => document.getElementById("info-name").focus(), 0);
    }

    function closeInfoModal() {
        editingInfoId = null;
        document.getElementById("info-modal-backdrop").classList.remove("open");
    }

    function saveInfoFromModal() {
        const name = document.getElementById("info-name").value.trim();
        const serverId = document.getElementById("info-server").value.trim();
        const topic = document.getElementById("info-topic").value.trim();

        if (!name) {
            showToast("Information", "Donnez un nom à la bulle.", "warning");
            return;
        }
        if (!serverId) {
            showToast(name, "Sélectionnez un serveur MQTT.", "warning");
            return;
        }
        if (!topic) {
            showToast(name, "Le topic MQTT est obligatoire.", "warning");
            return;
        }
        if (topic.includes("#") || topic.includes("+")) {
            showToast(
                name,
                "Utilisez un topic exact : # et + ne sont pas acceptés.",
                "warning"
            );
            return;
        }

        const values = {
            name,
            serverId,
            topic,
            valueType: document.getElementById("info-type").value,
            measureType: document.getElementById("info-measure").value,
            unit: document.getElementById("info-unit").value.trim(),
            decimals: document.getElementById("info-decimals").value,
            staleSeconds: Math.max(
                0,
                Number(document.getElementById("info-stale").value || 0)
            ),
            jsonPath: document.getElementById("info-json-path").value.trim(),
            active: true
        };

        if (editingInfoId) {
            const info = infoById(editingInfoId);
            if (!info) {
                closeInfoModal();
                return;
            }
            Object.assign(info, values);

            const oldTile = document.querySelector(
                '#action-wall .info-tile[data-info-id="' + CSS.escape(editingInfoId) + '"]'
            );
            if (oldTile) oldTile.remove();
        } else {
            infoTiles.push({
                id: createInfoId(),
                ...values
            });
        }

        saveInfoTiles();
        ensureControlOrder();
        renderActionTiles();
        closeInfoModal();
    }

    /* ======================== MODALE MQTT ======================== */

    async function openMqttConfiguration() {
        document.getElementById("mqtt-config-backdrop").classList.add("open");
        showMqttList();
        await renderMqttList();
    }

    function closeMqttConfiguration() {
        editingMqttId = null;
        document.getElementById("mqtt-config-backdrop").classList.remove("open");
    }

    function showMqttList() {
        editingMqttId = null;
        document.getElementById("mqtt-list-panel").style.display = "";
        document.getElementById("mqtt-form-panel").style.display = "none";
    }

    async function renderMqttList() {
        const meta = document.getElementById("mqtt-meta");
        const list = document.getElementById("mqtt-list");
        meta.textContent = "Chargement...";
        list.innerHTML = "";

        try {
            const data = await loadMqttServers();
            meta.textContent =
                "Fichier : " + data.path + (data.editable ? "" : " — lecture seule");
            document.getElementById("mqtt-add").disabled = !data.editable;

            if (mqttServers.length === 0) {
                const empty = document.createElement("div");
                empty.className = "saved-actions-empty";
                empty.textContent = "Aucun serveur MQTT configuré.";
                list.appendChild(empty);
                return;
            }

            mqttServers.forEach(server => {
                const row = document.createElement("div");
                row.className = "mqtt-row";

                const main = document.createElement("div");
                main.className = "mqtt-row-main";
                main.innerHTML =
                    '<div class="mqtt-row-name"></div>' +
                    '<div class="mqtt-row-detail"></div>';
                main.querySelector(".mqtt-row-name").textContent = server.name;
                main.querySelector(".mqtt-row-detail").textContent =
                    (server.tls ? "ssl://" : "tcp://") +
                    server.host + ":" + server.port +
                    " — id=" + server.id +
                    (server.username ? " — utilisateur=" + server.username : "");

                const badge = document.createElement("span");
                badge.className = "mqtt-badge " +
                    (server.connected ? "connected" : "disconnected");
                badge.textContent = !server.enabled
                    ? "DÉSACTIVÉ"
                    : (server.connected ? "CONNECTÉ" : "HORS LIGNE");

                const buttons = document.createElement("div");
                buttons.className = "mqtt-row-buttons";

                const edit = document.createElement("button");
                edit.type = "button";
                edit.textContent = "Configurer";
                edit.disabled = !data.editable;
                edit.onclick = () => openMqttEditor(server.id);

                const remove = document.createElement("button");
                remove.type = "button";
                remove.className = "danger";
                remove.textContent = "Supprimer";
                remove.disabled = !data.editable;
                remove.onclick = () => deleteMqttServer(server);

                buttons.append(edit, remove);
                row.append(main, badge, buttons);
                list.appendChild(row);
            });
        } catch (e) {
            meta.textContent = e.message || "Impossible de charger MQTT.";
            showToast("MQTT", meta.textContent, "error");
        }
    }

    function mqttAutomaticPasswordEnvironment(id) {
        const normalized = String(id || "")
            .trim()
            .toUpperCase()
            .replace(/[^A-Z0-9]/g, "_");

        return "XIAOVV_MQTT_" + normalized + "_PASSWORD";
    }

    function updateMqttPasswordHelp(server) {
        const id = document.getElementById("mqtt-id").value.trim();
        const explicitEnv =
            document.getElementById("mqtt-password-env").value.trim();
        const automaticEnv = mqttAutomaticPasswordEnvironment(id);
        const targetEnv = explicitEnv || automaticEnv;

        document.getElementById("mqtt-password-env-help").textContent =
            id
                ? "À définir dans Run/Debug > Environment variables : " + targetEnv
                : "Saisissez d'abord l'identifiant du serveur.";

        const state = document.getElementById("mqtt-password-state");

        if (server && server.passwordFromEnvironment) {
            state.textContent =
                "Mot de passe fourni par la variable d'environnement " +
                (server.activePasswordEnvironment || targetEnv) +
                ".";
        } else if (server && server.passwordStoredInFile) {
            state.textContent =
                "Un mot de passe est encore stocké dans mqtt.properties. " +
                "Configurez " + targetEnv + " puis cochez sa suppression.";
        } else if (server && server.passwordConfigured) {
            state.textContent =
                "Un mot de passe est configuré.";
        } else {
            state.textContent =
                "Aucun mot de passe actif. Configurez " + targetEnv +
                " dans la configuration Run/Debug.";
        }
    }

    function openMqttEditor(id) {
        editingMqttId = id || null;
        const server = editingMqttId
            ? mqttServers.find(x => x.id === editingMqttId)
            : null;

        document.getElementById("mqtt-list-panel").style.display = "none";
        document.getElementById("mqtt-form-panel").style.display = "";

        document.getElementById("mqtt-id").value = server ? server.id : "";
        document.getElementById("mqtt-name").value = server ? server.name : "";
        document.getElementById("mqtt-host").value = server ? server.host : "";
        document.getElementById("mqtt-port").value = server ? String(server.port) : "1883";
        document.getElementById("mqtt-username").value =
            server && server.username ? server.username : "";
        document.getElementById("mqtt-password-env").value =
            server && server.passwordEnv ? server.passwordEnv : "";
        document.getElementById("mqtt-password").value = "";
        document.getElementById("mqtt-enabled").checked = server ? server.enabled : true;
        document.getElementById("mqtt-tls").checked = server ? server.tls : false;
        document.getElementById("mqtt-clear-password").checked = false;

        updateMqttPasswordHelp(server);

        setTimeout(() => document.getElementById("mqtt-id").focus(), 0);
    }

    async function saveMqttServer() {
        const id = document.getElementById("mqtt-id").value.trim();
        const name = document.getElementById("mqtt-name").value.trim();
        const host = document.getElementById("mqtt-host").value.trim();
        const port = document.getElementById("mqtt-port").value.trim();
        const username = document.getElementById("mqtt-username").value.trim();
        const passwordEnv =
            document.getElementById("mqtt-password-env").value.trim();
        const password = document.getElementById("mqtt-password").value;
        const clearPassword =
            document.getElementById("mqtt-clear-password").checked;

        if (!/^[A-Za-z0-9_-]+$/.test(id)) {
            showToast(
                "MQTT",
                "Identifiant invalide : lettres, chiffres, tiret et underscore uniquement.",
                "warning"
            );
            return;
        }

        if (!name || !host) {
            showToast(
                "MQTT",
                "Nom et adresse du serveur sont obligatoires.",
                "warning"
            );
            return;
        }

        const body = new URLSearchParams();
        body.set("previousId", editingMqttId || "");
        body.set("id", id);
        body.set("name", name);
        body.set("host", host);
        body.set("port", port);
        body.set("username", username);
        body.set("passwordEnv", passwordEnv);
        body.set(
            "enabled",
            document.getElementById("mqtt-enabled").checked ? "true" : "false"
        );
        body.set(
            "tls",
            document.getElementById("mqtt-tls").checked ? "true" : "false"
        );

        if (clearPassword) {
            body.set("passwordMode", "clear");
        } else if (password) {
            body.set("passwordMode", "set");
            body.set("password", password);
        } else {
            body.set("passwordMode", "keep");
        }

        const saveButton = document.getElementById("mqtt-save");
        saveButton.disabled = true;

        try {
            const response = await apiFetch(
                "/api/mqtt/servers/save",
                {
                    method: "POST",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: body.toString()
                }
            );

            const data = await response.json();
            if (!response.ok || !data || data.success !== true) {
                throw new Error(
                    data && data.error
                        ? data.error
                        : "Enregistrement MQTT impossible."
                );
            }

            showToast(name, "Configuration MQTT enregistrée.", "info");
            showMqttList();
            await renderMqttList();
            refreshAllInfoTiles();
        } catch (e) {
            showToast(
                "MQTT",
                e.message || "Enregistrement MQTT impossible.",
                "error"
            );
        } finally {
            saveButton.disabled = false;
        }
    }

    async function deleteMqttServer(server) {
        const usedBy = infoTiles.filter(i => i.serverId === server.id);
        const extra = usedBy.length
            ? "\n\nAttention : " + usedBy.length +
              " bulle(s) utilisent encore ce serveur."
            : "";

        if (!window.confirm(
            "Supprimer définitivement le serveur MQTT « " +
            server.name + " » ?" + extra
        )) return;

        try {
            const response = await apiFetch(
                "/api/mqtt/servers/" + encodeURIComponent(server.id),
                { method:"DELETE" }
            );

            const data = await response.json();
            if (!response.ok || !data || data.success !== true) {
                throw new Error(
                    data && data.error ? data.error : "Suppression MQTT impossible."
                );
            }

            await renderMqttList();
            refreshAllInfoTiles();
        } catch (e) {
            showToast(
                "MQTT",
                e.message || "Suppression MQTT impossible.",
                "error"
            );
        }
    }

    function enforcePhysicalZones() {
        const controlWall = document.getElementById("action-wall");
        const videoWall = document.getElementById("wall");
        if (!controlWall || !videoWall) return;

        videoWall.querySelectorAll(".action-tile, .info-tile")
            .forEach(node => controlWall.appendChild(node));
        controlWall.querySelectorAll(".tile-shell")
            .forEach(node => videoWall.appendChild(node));
    }

    /* ======================== EVENTS ======================== */

    addInfoButton.addEventListener("click", () => openInfoModal(null));
    mqttButton.addEventListener("click", openMqttConfiguration);

    document.getElementById("info-cancel")
        .addEventListener("click", closeInfoModal);
    document.getElementById("info-save")
        .addEventListener("click", saveInfoFromModal);
    document.getElementById("info-modal-backdrop")
        .addEventListener("pointerdown", event => {
            if (event.target === event.currentTarget) closeInfoModal();
        });

    document.getElementById("mqtt-close")
        .addEventListener("click", closeMqttConfiguration);
    document.getElementById("mqtt-add")
        .addEventListener("click", () => openMqttEditor(null));
    document.getElementById("mqtt-back")
        .addEventListener("click", () => {
            showMqttList();
            renderMqttList();
        });
    document.getElementById("mqtt-save")
        .addEventListener("click", saveMqttServer);
    document.getElementById("mqtt-config-backdrop")
        .addEventListener("pointerdown", event => {
            if (event.target === event.currentTarget) closeMqttConfiguration();
        });

    /*
     * Important : chargé avant le refreshCameras(true) du code existant.
     */
    loadInfoTiles();
    loadControlOrder();

    setInterval(
        refreshAllInfoTiles,
        INFO_POLL_MS
    );
})();
