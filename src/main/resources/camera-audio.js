/* Une seule caméra audible, sélectionnée par un clic sur son image. */
(() => {
    "use strict";
    let audio, selectedId = null, volume = 50, muted = false, controls, save;
    const speaker = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/></svg>`;
    const silent = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="m16 9 6 6m0-6-6 6"/></svg>`;
    function preferences() { return { audioVolume: String(volume), audioMuted: String(muted) }; }
    function markSelection() {
        let player;
        document.querySelectorAll("#wall .tile-shell").forEach(shell => {
            const image = shell.querySelector(".player img");
            const selected = image?.dataset.audioCamera === selectedId;
            shell.classList.toggle("audio-active", selected);
            image?.setAttribute("aria-pressed", String(selected));
            if (selected) player = shell.querySelector(".player");
        });
        if (controls) {
            controls.hidden = !player;
            if (player && controls.parentNode !== player) player.appendChild(controls);
        }
    }
    function updateControls() {
        if (!controls) return;
        controls.querySelector("#camera-audio-volume").value = String(volume);
        controls.querySelector("#camera-audio-percent").textContent = volume + " %";
        const mute = controls.querySelector("#camera-audio-mute");
        const label = muted ? "Activer le son" : "Couper le son";
        mute.innerHTML = muted || volume === 0 ? silent : speaker;
        mute.setAttribute("aria-label", label); mute.title = label;
        mute.setAttribute("aria-pressed", String(muted));
        controls.classList.toggle("audio-muted", muted || volume === 0);
        controls.style.setProperty("--audio-volume", volume + "%");
        controls.querySelector("#camera-audio-stop").disabled = selectedId === null;
        if (audio) { audio.volume = volume / 100; audio.muted = muted; }
    }
    function status(message, state = "waiting") {
        if (!controls) return;
        controls.querySelector("#camera-audio-status").textContent = message;
        const indicator = controls.querySelector(".camera-audio-indicator");
        indicator.title = message; indicator.dataset.state = state;
    }
    function stop() {
        selectedId = null;
        if (audio) { audio.pause(); audio.removeAttribute("src"); audio.load(); }
        status("Écoute arrêtée.", "idle");
        markSelection(); updateControls();
    }
    function select(camera) {
        if (!audio) return;
        if (selectedId !== camera.id) {
            audio.pause(); audio.removeAttribute("src"); audio.load();
            selectedId = camera.id;
            audio.src = "/api/cameras/" + encodeURIComponent(camera.id) + "/audio.mp3";
        }
        muted = false;
        controls.setAttribute("aria-label", "Son de la caméra " + camera.id);
        status("Connexion au son…");
        updateControls(); markSelection(); save();
        audio.play().then(() => {
            if (selectedId === camera.id) status("Écoute en cours.", "playing");
        }).catch(error => {
            if (error.name === "AbortError" || selectedId !== camera.id) return;
            status(error.name === "NotAllowedError"
                ? "Clique à nouveau sur la caméra pour autoriser le son."
                : "Son indisponible : vérifier le microphone de la caméra.", "error");
        });
    }
    function start(layout, savePreferences) {
        if (audio) return;
        save = savePreferences;
        const savedVolume = Number(layout.audioVolume ?? 50);
        volume = Number.isFinite(savedVolume) ? Math.max(0, Math.min(100, savedVolume)) : 50;
        muted = layout.audioMuted === "true";
        controls = document.createElement("section");
        controls.className = "camera-audio-controls"; controls.hidden = true;
        controls.setAttribute("aria-label", "Son de la caméra active");
        controls.innerHTML = `<span class="camera-audio-indicator" data-state="idle" aria-hidden="true"></span>
            <button id="camera-audio-mute" type="button" aria-label="Couper le son" aria-pressed="false">${speaker}</button>
            <input id="camera-audio-volume" type="range" aria-label="Volume" title="Volume" min="0" max="100" value="50" step="1">
            <output id="camera-audio-percent" for="camera-audio-volume">50 %</output>
            <span class="camera-audio-divider" aria-hidden="true"></span>
            <button id="camera-audio-stop" type="button" aria-label="Arrêter l'écoute" title="Arrêter l'écoute">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6"/></svg></button>
            <span id="camera-audio-status" class="camera-audio-status-text" role="status" aria-live="polite"></span>`;
        document.body.appendChild(controls);
        audio = document.createElement("audio"); audio.id = "camera-audio-player"; audio.preload = "none";
        // Le lecteur reste attaché au document quand la barre passe d'une tuile à une autre.
        audio.hidden = true; document.body.appendChild(audio);
        controls.querySelector("#camera-audio-volume").addEventListener("input", event => {
            volume = Number(event.target.value); if (volume > 0) muted = false;
            updateControls(); save();
        });
        controls.querySelector("#camera-audio-mute").onclick = () => {
            muted = !muted; updateControls(); save();
            if (!muted && selectedId) audio.play().catch(() => status("Clique sur la caméra pour relancer le son.", "error"));
        };
        controls.querySelector("#camera-audio-stop").onclick = stop;
        audio.addEventListener("playing", () => { if (selectedId) status("Écoute en cours.", "playing"); });
        audio.addEventListener("waiting", () => { if (selectedId) status("Le flux audio attend des données…"); });
        audio.addEventListener("error", () => { if (selectedId) status("Son indisponible : vérifier le microphone de la caméra et FFmpeg.", "error"); });
        const originalCreateTile = createTile;
        createTile = camera => {
            const shell = originalCreateTile(camera);
            const image = shell.querySelector(".player img");
            if (image) {
                image.dataset.audioCamera = camera.id; image.tabIndex = 0;
                image.setAttribute("role", "button");
                image.setAttribute("aria-label", "Écouter la caméra " + camera.id);
                image.setAttribute("aria-pressed", String(camera.id === selectedId));
                image.title = "Cliquer pour sélectionner cette caméra et écouter son microphone";
                image.addEventListener("click", () => select(camera));
                image.addEventListener("keydown", event => {
                    if (["Enter", " "].includes(event.key)) { event.preventDefault(); select(camera); }
                });
            }
            shell.classList.toggle("audio-active", camera.id === selectedId);
            return shell;
        };
        new MutationObserver(() => {
            if (selectedId && ![...document.querySelectorAll("#wall .player img")].some(image => image.dataset.audioCamera === selectedId)) stop();
            else markSelection();
        }).observe(document.getElementById("wall"), { childList: true, subtree: true });
        window.addEventListener("pagehide", stop);
        updateControls();
    }
    window.xiaovvAudio = { start, preferences, stop };
})();
