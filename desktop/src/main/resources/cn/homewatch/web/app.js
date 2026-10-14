"use strict";

(() => {
  const $ = id => document.getElementById(id);
  const state = { authenticated: false, csrf: "", username: "", administrator: true, cameras: [], cameraId: "", mode: "live", layout: 1,
    page: 0, expandedLayout: 0, recordings: [], cursor: Date.now(), span: 3600000, queryFrom: 0, queryTo: 0,
    explicitRange: false, serverOffset: 0, timezone: "Asia/Shanghai", pollTimer: null, clockTimer: null,
    searchSequence: 0, mediaSequence: 0, activeClip: null, mediaBase: 0, mediaEnd: 0, dragging: false,
    playing: false, timelineRaf: null, requests: new Set(), noticeTimer: null, liveKey: "", listKey: "", retryAt: 0, liveRefreshAt: 0, retryTimer: null,
    polling: false, pollSequence: 0, connectionNotice: "", archiveBusy: false, lastArchiveRefresh: 0, waitingForRecording: false,
    seekSequence: 0, playbackRate: 1, prefetchTimer: null, continuationBusy: false, livePaused: false, archiveFilter: "all", listLimit: 100, archiveListKey: "", archiveListDate: "",
    calendarMonth: "", calendarDays: [], calendarSequence: 0, calendarUpdatedAt: 0, calendarCameraId: "", latestRecording: 0,
    zoom: 1, zoomX: 0, zoomY: 0, zoomDragging: false, zoomTarget: null, zoomActive: false, sidePane: "channels",
    liveSequence: 0, liveFrames: [], liveFrameActive: 0, liveFrameTimer: null };
  class ApiError extends Error { constructor(message, status) { super(message); this.status = status; } }
  const video = $("replay-video");
  const timeline = $("timeline");
  const narrowLayout = matchMedia("(max-width:760px)");
  const cleanUrl = (candidate, fallback) => {
    try { const url = new URL(candidate || fallback, location.origin); return url.origin === location.origin && url.pathname.startsWith("/api/v1/") ? url.pathname + url.search : fallback; }
    catch { return fallback; }
  };
  function abortArchiveRequests() { for (const controller of state.requests) if (controller.group === "archive") controller.abort(); }
  function abortCalendarRequests() { for (const controller of state.requests) if (controller.group === "calendar") controller.abort(); }
  function beginNavigation() {
    state.seekSequence++; state.searchSequence++; abortArchiveRequests(); clearTimeout(state.prefetchTimer);
    state.archiveBusy = false; $("search-submit").disabled = false; return state.seekSequence;
  }
  const serverNow = () => Date.now() + state.serverOffset;
  const dayKey = ms => inputTime(ms).slice(0, 10);
  const monthKey = ms => inputTime(ms).slice(0, 7);
  const validTime = time => Number.isFinite(time) && time >= 0 && time <= 253402214400000;
  async function request(path, options = {}) {
    const controller = new AbortController(); state.requests.add(controller);
    controller.group = options.group;
    let timedOut = false; const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 12000);
    try {
      const response = await fetch(path, { credentials: "same-origin", cache: "no-store", ...options, signal: controller.signal,
        headers: { "Accept": "application/json", ...(options.body ? { "Content-Type": "application/json" } : {}), ...(state.csrf ? { "X-CSRF-Token": state.csrf } : {}), ...options.headers } });
      let data = {}; try { data = await response.json(); } catch { /* Friendly fallback below for a stopped recorder. */ }
      if (!response.ok) {
        if (response.status === 401 && state.authenticated) showLogin("登录已过期，请重新登录");
        throw new ApiError(data.message || (typeof data.error === "string" ? data.error : "") || (response.status === 401 ? "用户名或密码不正确" : response.status === 429 ? "请求过于频繁，请稍后重试" : "录像机暂时无法响应，请稍后重试"), response.status);
      }
      return data;
    } catch (error) {
      if (timedOut) throw new ApiError("连接超时，请确认电脑端录像机仍在运行", 0);
      if (error instanceof TypeError) throw new ApiError("无法连接录像机，请确认网络后重试", 0);
      throw error;
    } finally { clearTimeout(timeout); state.requests.delete(controller); }
  }
  function report(error) { if (error.name !== "AbortError" && state.authenticated) notice(error.message || "连接中断，请确认电脑端录像机仍在运行", true); }
  function notice(message, persist = false) {
    clearTimeout(state.noticeTimer); $("notice-text").textContent = message; $("notice").hidden = !message;
    if (!persist) state.noticeTimer = setTimeout(() => { $("notice").hidden = true; }, 6500);
  }
  function stopLive() {
    clearTimeout(state.retryTimer); clearTimeout(state.liveFrameTimer); state.liveSequence++;
    for (const controller of state.requests) if (controller.group === "live") controller.abort();
    for (const frame of state.liveFrames) {
      if (frame.url) URL.revokeObjectURL(frame.url);
      if (frame.pendingUrl) URL.revokeObjectURL(frame.pendingUrl);
    }
    state.liveFrames = []; state.liveFrameActive = 0;
    for (const img of $("live-grid").querySelectorAll("img")) img.removeAttribute("src");
    $("live-grid").replaceChildren(); state.liveKey = ""; state.liveRefreshAt = 0;
  }
  function stopPlayback() {
    state.mediaSequence++; video.pause(); video.removeAttribute("src"); video.load();
    state.activeClip = null; state.playing = false; state.mediaBase = 0; state.mediaEnd = 0; state.waitingForRecording = false;
    state.dragging = false; timeline.classList.remove("dragging");
    $("play-pause").textContent = "播放";
    updateDownload();
  }
  function clearSession() {
    state.authenticated = false; state.searchSequence++; state.pollSequence++; state.polling = false; state.archiveBusy = false; state.lastArchiveRefresh = 0; state.connectionNotice = "";
    clearInterval(state.pollTimer); clearInterval(state.clockTimer); clearTimeout(state.noticeTimer); clearTimeout(state.prefetchTimer);
    state.seekSequence++; state.calendarSequence++; state.calendarDays = []; state.calendarMonth = ""; state.calendarCameraId = "";
    state.continuationBusy = false; state.livePaused = false; resetZoom();
    for (const controller of state.requests) controller.abort(); state.requests.clear();
    stopLive(); stopPlayback(); state.cameras = []; state.recordings = []; state.cameraId = ""; state.csrf = ""; state.listKey = ""; state.administrator = true;
    renderArchiveList(); renderCalendar();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  }
  function showLogin(message = "") {
    clearSession(); $("console").hidden = true; $("login-screen").hidden = false;
    $("login-error").textContent = message; $("password").value = ""; $("login-submit").disabled = false;
    $("login-submit").textContent = "登录";
  }
  async function enterConsole(session) {
    clearInterval(state.pollTimer); clearInterval(state.clockTimer);
    state.authenticated = true; state.csrf = session.csrfToken || ""; state.username = session.username || "";
    state.administrator = session.user?.administrator !== false;
    $("remember").checked = session.remember !== false;
    $("account-name").textContent = state.username; $("console").hidden = false; $("login-screen").hidden = true;
    $("password").value = ""; $("notice").hidden = true;
    state.mode = "live"; applyMode();
    if (narrowLayout.matches) setSidebar(false);
    await refreshStatus();
    if (!state.authenticated) return;
    state.pollTimer = setInterval(refreshStatus, 2000); state.clockTimer = setInterval(updateClock, 1000);
    updateClock();
  }
  function parts(ms, options = {}) {
    return new Intl.DateTimeFormat("sv-SE", { timeZone: state.timezone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23", ...options }).formatToParts(new Date(ms));
  }
  function dateTime(ms) { return parts(ms).map(p => p.type === "literal" && p.value === " " ? " " : p.value).join(""); }
  function clockLabel(ms, withDate = false) {
    const map = Object.fromEntries(parts(ms).map(p => [p.type, p.value]));
    return (withDate ? `${map.month}-${map.day} ` : "") + `${map.hour}:${map.minute}:${map.second}`;
  }
  function inputTime(ms) {
    const map = Object.fromEntries(parts(ms).map(p => [p.type, p.value]));
    return `${map.year}-${map.month}-${map.day}T${map.hour}:${map.minute}:${map.second}`;
  }
  function inputEpoch(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
    if (!match) return NaN;
    const year = +match[1], month = +match[2], day = +match[3], hour = +match[4], minute = +match[5], second = +(match[6] || 0);
    if (year < 1970 || month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return NaN;
    const raw = Date.UTC(year, month - 1, day, hour, minute, second);
    if (new Date(raw).getUTCMonth() !== month - 1 || new Date(raw).getUTCDate() !== day) return NaN;
    let guessed = raw;
    for (let i = 0; i < 3; i++) {
      const p = Object.fromEntries(parts(guessed).map(part => [part.type, part.value]));
      const formatted = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
      guessed += raw - formatted;
    }
    return inputTime(guessed) === `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${String(second).padStart(2, "0")}` ? guessed : NaN;
  }
  function updateClock() { if (state.authenticated) $("recorder-clock").textContent = dateTime(Date.now() + state.serverOffset); }
  function bytes(value) {
    value = Math.max(0, Number(value) || 0);
    if (value >= 1024 ** 4) return (value / 1024 ** 4).toFixed(1) + " TB";
    if (value >= 1024 ** 3) return (value / 1024 ** 3).toFixed(1) + " GB";
    if (value >= 1024 ** 2) return (value / 1024 ** 2).toFixed(1) + " MB";
    return (value / 1024).toFixed(0) + " KB";
  }
  async function refreshStatus() {
    if (!state.authenticated || document.hidden || state.polling) return;
    state.polling = true; const pollSequence = ++state.pollSequence;
    try {
      const [server, result] = await Promise.all([request("/api/v1/server", { group: "status" }), request("/api/v1/cameras", { group: "status" })]);
      if (!state.authenticated || pollSequence !== state.pollSequence || document.hidden) return;
      if (state.connectionNotice && $("notice-text").textContent === state.connectionNotice) $("notice").hidden = true;
      state.connectionNotice = "";
      if (server.timezone) { try { new Intl.DateTimeFormat("zh-CN", { timeZone: server.timezone }); state.timezone = server.timezone; } catch { /* Retain the valid recorder timezone. */ } }
      if (Number.isFinite(Date.parse(server.serverTime))) state.serverOffset = Date.parse(server.serverTime) - Date.now();
      $("storage-usage").textContent = bytes(server.usedBytes); $("storage-limit").textContent = Number(server.maxBytes) > 0 ? bytes(server.maxBytes) : "不限容量";
      $("storage-fill").style.width = (Number(server.maxBytes) > 0 ? Math.min(100, Math.max(0, Number(server.usedBytes) / Number(server.maxBytes) * 100)) : 0) + "%";
      $("storage-retention").textContent = Number(server.keepDays) > 0 ? server.keepDays + " 天" : "不限天数";
      $("storage-segment").textContent = server.segmentMinutes ? server.segmentMinutes + " 分钟" : "—";
      const oldId = state.cameraId; state.cameras = Array.isArray(result.cameras) ? result.cameras : [];
      if (!state.cameras.some(c => c.id === state.cameraId)) state.cameraId = state.cameras[0]?.id || "";
      renderCameraList(); if (state.mode === "live" && !document.hidden) renderLive();
      const online = state.cameras.filter(c => c.online).length;
      $("connection-summary").textContent = state.cameras.length ? `录像机已连接 · ${online} / ${state.cameras.length} 个通道在线` : emptyCamerasMessage();
      updateSelectedStatus(); updateClock();
      if (oldId !== state.cameraId) {
        state.seekSequence++; state.searchSequence++; abortArchiveRequests(); abortCalendarRequests(); state.calendarSequence++;
        stopPlayback(); state.recordings = []; state.queryFrom = 0; state.queryTo = 0; state.calendarDays = []; resetZoom(); renderArchiveList(); renderCalendar();
        if (state.mode === "replay") { if (state.cameraId) await loadToday(); else { drawTimeline(); showReplayMessage(emptyCamerasMessage()); } }
      }
      else if (state.mode === "replay" && !state.archiveBusy && state.queryTo > state.queryFrom && Date.now() - state.lastArchiveRefresh >= archiveRefreshDelay()) {
        const range = state.explicitRange ? [state.queryFrom, state.queryTo] : archiveWindow(state.cursor);
        await queryArchives(range[0], range[1], false, true);
      }
      if (state.mode === "replay" && Date.now() - state.calendarUpdatedAt > 15000 && state.calendarMonth) loadCalendar(state.calendarMonth, true).catch(report);
    } catch (error) {
      if (error.name !== "AbortError" && state.authenticated) { state.connectionNotice = error.message || "连接中断，请确认电脑端录像机仍在运行"; notice(state.connectionNotice, true); $("connection-summary").textContent = "连接中断，正在重试"; }
    } finally { if (pollSequence === state.pollSequence) state.polling = false; }
  }
  function personsPending(clip) { return !["READY", "COMPLETE", "COMPLETED", "DONE", "DISABLED", "FAILED", "ERROR"].includes(String(clip.personStatus || "").toUpperCase()); }
  function archiveRefreshDelay() {
    return selectedCamera()?.recording || state.waitingForRecording || state.recordings.some(clip => clip.active || personsPending(clip)) ? 1800 : 15000;
  }
  function channelName(camera) { return `CH${String(camera?.number || state.cameras.indexOf(camera) + 1).padStart(2, "0")}`; }
  function emptyCamerasMessage(multiline = false) {
    const separator = multiline ? "\n" : "，";
    return state.administrator ? "暂无摄像头" + separator + "请在电脑端添加设备" : "暂无可查看的摄像头" + separator + "请联系管理员分配摄像头";
  }
  function selectedCamera() { return state.cameras.find(camera => camera.id === state.cameraId); }
  function updateSelectedStatus() {
    const camera = selectedCamera();
    $("selected-status").textContent = camera ? `${camera.name || "摄像头"} · ${camera.online ? "在线" : camera.recording ? "等待摄像头画面" : "离线"}${camera.width && camera.height ? ` · ${camera.width} × ${camera.height}` : ""}${camera.recording ? " · 录制中" : ""}` : "";
    $("replay-name").textContent = camera ? `${channelName(camera)}  ${camera.name || "摄像头"}` : "";
    updateViewActions();
  }
  function renderCameraList() {
    const key = state.administrator + ":" + state.cameraId + ":" + state.cameras.map(c => `${c.id}:${c.name}:${c.number}:${Boolean(c.online)}:${Boolean(c.recording)}`).join(",");
    if (key === state.listKey) return;
    state.listKey = key;
    const select = $("camera-select"); select.replaceChildren();
    const list = $("camera-list"); list.replaceChildren();
    $("device-count").textContent = state.cameras.length ? state.cameras.length + " 个通道" : "";
    if (!state.cameras.length) {
      const option = new Option(state.administrator ? "暂无设备" : "暂无授权设备", ""); select.add(option); select.disabled = true;
      const empty = document.createElement("div"); empty.className = "empty-list"; empty.textContent = emptyCamerasMessage(true); list.append(empty);
    } else select.disabled = false;
    state.cameras.forEach(camera => {
      select.add(new Option(`${channelName(camera)}  ${camera.name || "摄像头"}`, camera.id));
      const row = document.createElement("button"); row.className = "camera-row" + (camera.id === state.cameraId ? " selected" : "");
      row.setAttribute("aria-pressed", String(camera.id === state.cameraId)); row.title = `${camera.name || "摄像头"} · ${camera.online ? "在线" : "离线"}`;
      const dot = document.createElement("i"); dot.className = "status-dot" + (camera.online ? " online" : ""); dot.setAttribute("aria-hidden", "true");
      const number = document.createElement("span"); number.className = "device-number"; number.textContent = channelName(camera);
      const name = document.createElement("span"); name.className = "device-name"; name.textContent = camera.name || "摄像头";
      row.append(dot, number, name);
      if (camera.recording) { const rec = document.createElement("span"); rec.className = "mini-rec"; rec.textContent = "REC"; row.append(rec); }
      row.addEventListener("click", () => chooseCamera(camera.id)); list.append(row);
    });
    select.value = state.cameraId;
    filterCameras();
  }
  function chooseCamera(id) {
    if (id === state.cameraId) return;
    if (!state.cameras.some(camera => camera.id === id)) return;
    state.cameraId = id; state.searchSequence++; state.seekSequence++; state.calendarSequence++; abortArchiveRequests(); abortCalendarRequests(); stopPlayback(); state.recordings = [];
    state.calendarCameraId = ""; state.calendarDays = []; state.listLimit = 100; resetZoom(); renderArchiveList();
    state.page = Math.floor(Math.max(0, state.cameras.findIndex(camera => camera.id === id)) / state.layout);
    renderCameraList(); updateSelectedStatus();
    if (state.mode === "live") renderLive(); else {
      loadCalendar(monthKey(state.cursor)).catch(report);
      if (state.queryTo > state.queryFrom) {
        const range = state.explicitRange ? [state.queryFrom, state.queryTo] : archiveWindow(state.cursor);
        const intent = state.seekSequence, cameraId = state.cameraId, target = state.cursor;
        queryArchives(range[0], range[1], false).then(ok => {
          if (!ok || intent !== state.seekSequence || cameraId !== state.cameraId || state.mode !== "replay") return;
          const dayFrom = inputEpoch(dayKey(target) + "T00:00:00"), dayTo = inputEpoch(shiftDay(dayKey(target), 1) + "T00:00:00");
          const sameDay = playableClips().find(item => item.endMs > dayFrom && item.startMs < dayTo);
          const clip = clipAt(target) || sameDay || playableClips()[0];
          if (clip) seekTo(clipAt(target) ? target : clip === sameDay ? Math.max(dayFrom, clip.startMs) : clip.startMs, false);
          else { state.cursor = target; drawTimeline(); showReplayMessage("当前通道在这个时间范围没有录像"); }
        }).catch(report);
      } else loadToday().catch(report);
    }
  }
  function setLayout(layout) {
    resetZoom();
    state.layout = layout; state.page = Math.floor(Math.max(0, state.cameras.findIndex(camera => camera.id === state.cameraId)) / layout);
    document.querySelectorAll("[data-layout]").forEach(button => { const active = Number(button.dataset.layout) === layout; button.classList.toggle("active", active); button.setAttribute("aria-pressed", String(active)); });
    renderLive();
  }
  function renderLive() {
    if (state.mode !== "live" || document.hidden) return;
    if (state.livePaused) { if ($("live-grid").childElementCount) stopLive(); showLivePaused(); return; }
    const grid = $("live-grid");
    const pages = Math.max(1, Math.ceil(state.cameras.length / state.layout)); state.page = Math.max(0, Math.min(state.page, pages - 1));
    const cameras = state.layout === 1 ? (selectedCamera() ? [selectedCamera()] : []) : state.cameras.slice(state.page * state.layout, (state.page + 1) * state.layout);
    const key = state.administrator + ":" + state.layout + ":" + cameras.map(c => `${c.id}:${c.name}:${c.number}:${Boolean(c.online)}`).join(",");
    if (key === state.liveKey && Date.now() < state.liveRefreshAt && (state.retryAt === 0 || Date.now() < state.retryAt)) {
      for (const tile of grid.querySelectorAll(".tile")) {
        const camera = state.cameras.find(c => c.id === tile.dataset.id);
        tile.classList.toggle("selected", camera?.id === state.cameraId); tile.setAttribute("aria-pressed", String(camera?.id === state.cameraId));
        const rec = tile.querySelector(".rec-overlay"); if (rec) rec.hidden = !camera?.recording;
      }
      return;
    }
    stopLive(); state.liveKey = key; state.retryAt = 0; state.liveRefreshAt = Date.now() + 25 * 60000; grid.className = "live-grid layout-" + state.layout;
    for (let index = 0; index < state.layout; index++) {
      const camera = cameras[index];
      if (!camera) {
        const empty = document.createElement("div"); empty.className = "empty-tile"; empty.textContent = state.cameras.length ? "空通道" : emptyCamerasMessage(); grid.append(empty); continue;
      }
      const tile = document.createElement("button"); tile.className = "tile" + (camera.id === state.cameraId ? " selected" : ""); tile.dataset.id = camera.id;
      tile.setAttribute("aria-pressed", String(camera.id === state.cameraId)); tile.setAttribute("aria-label", `${channelName(camera)} ${camera.name || "摄像头"}，${camera.online ? "在线" : camera.recording ? "等待画面，录制中" : "离线"}，双击放大或恢复分屏`);
      const message = document.createElement("span"); message.className = "surface-message"; message.textContent = camera.online ? "" : camera.recording ? "等待摄像头画面 · 录像机仍在录制" : "正在连接摄像头…";
      {
        const img = document.createElement("img"); img.alt = ""; img.draggable = false;
        if (state.layout > 1) state.liveFrames.push({ img, cameraId: camera.id, message, nextAt: 0, busy: false, url: "", pendingUrl: "", failures: 0 });
        else {
          img.src = cleanUrl(camera.liveUrl, `/api/v1/cameras/${encodeURIComponent(camera.id)}/live.mjpg`);
          img.addEventListener("load", () => { message.textContent = ""; });
          img.addEventListener("error", () => {
            message.textContent = state.cameras.find(item => item.id === camera.id)?.recording ? "等待摄像头画面 · 录像机仍在录制" : "画面暂时不可用，正在重试"; state.retryAt = Date.now() + 1500;
            clearTimeout(state.retryTimer); state.retryTimer = setTimeout(() => { if (state.authenticated && state.mode === "live" && !state.livePaused && !document.hidden) renderLive(); }, 1550);
          });
        }
        tile.append(img);
      }
      const overlay = document.createElement("span"); overlay.className = "channel-overlay";
      const number = document.createElement("span"); number.className = "channel-number"; number.textContent = channelName(camera);
      overlay.append(number, document.createTextNode(camera.name || "摄像头"));
      const rec = document.createElement("span"); rec.className = "rec-overlay"; rec.textContent = "REC"; rec.hidden = !camera.recording;
      tile.append(message, overlay, rec); tile.addEventListener("click", () => chooseCamera(camera.id));
      tile.addEventListener("dblclick", () => {
        chooseCamera(camera.id);
        if (state.layout > 1) { state.expandedLayout = state.layout; setLayout(1); }
        else if (state.expandedLayout) { const previous = state.expandedLayout; state.expandedLayout = 0; setLayout(previous); }
      }); grid.append(tile);
    }
    $("grid-pagination").hidden = pages <= 1 || state.layout === 1;
    $("page-label").textContent = `${state.page + 1} / ${pages}`; $("page-prev").disabled = state.page <= 0; $("page-next").disabled = state.page >= pages - 1;
    applyZoom(); pumpLiveFrames();
  }
  function pumpLiveFrames() {
    clearTimeout(state.liveFrameTimer);
    if (!state.authenticated || state.mode !== "live" || state.livePaused || document.hidden || state.layout === 1) return;
    const now = Date.now();
    const ready = state.liveFrames.filter(frame => !frame.busy && frame.nextAt <= now).sort((a, b) => a.nextAt - b.nextAt);
    while (state.liveFrameActive < 2 && ready.length) fetchLiveFrame(ready.shift(), state.liveSequence);
    const waiting = state.liveFrames.filter(frame => !frame.busy);
    if (waiting.length && state.liveFrameActive < 2) {
      const next = Math.min(...waiting.map(frame => frame.nextAt));
      state.liveFrameTimer = setTimeout(pumpLiveFrames, Math.max(20, next - Date.now()));
    }
  }
  async function fetchLiveFrame(frame, sequence) {
    frame.busy = true; state.liveFrameActive++;
    const controller = new AbortController(); controller.group = "live"; state.requests.add(controller);
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(`/api/v1/cameras/${encodeURIComponent(frame.cameraId)}/live.jpg`, { credentials: "same-origin", cache: "no-store", signal: controller.signal });
      if (!response.ok) {
        if (response.status === 401 && state.authenticated) showLogin("登录已过期，请重新登录");
        throw new ApiError("画面暂时不可用", response.status);
      }
      const blob = await response.blob();
      if (sequence !== state.liveSequence || !state.authenticated || state.mode !== "live" || !state.cameras.some(camera => camera.id === frame.cameraId)) return;
      if (!blob.size || blob.size > 20 * 1024 * 1024 || !/^image\//i.test(blob.type)) throw new ApiError("摄像头没有返回有效画面", 0);
      const nextUrl = URL.createObjectURL(blob); frame.pendingUrl = nextUrl;
      const probe = new Image(); probe.src = nextUrl;
      await probe.decode();
      if (sequence !== state.liveSequence || !state.authenticated || state.mode !== "live") { URL.revokeObjectURL(nextUrl); frame.pendingUrl = ""; return; }
      const previous = frame.url; frame.img.src = nextUrl; frame.url = nextUrl; frame.pendingUrl = "";
      if (previous) URL.revokeObjectURL(previous);
      frame.message.textContent = ""; frame.failures = 0; frame.nextAt = Date.now() + 500;
    } catch (error) {
      if (sequence === state.liveSequence && state.authenticated && state.mode === "live") {
        if (frame.pendingUrl) URL.revokeObjectURL(frame.pendingUrl); frame.pendingUrl = "";
        frame.failures++;
        frame.nextAt = Date.now() + Math.min(10000, frame.failures * 3000);
        const recording = state.cameras.find(camera => camera.id === frame.cameraId)?.recording;
        frame.message.textContent = recording ? "等待摄像头画面 · 录像机仍在录制" : "画面暂时不可用，正在重试";
        if (error.status === 403 || error.status === 404) { frame.img.removeAttribute("src"); if (frame.url) URL.revokeObjectURL(frame.url); frame.url = ""; refreshStatus(); }
      }
    } finally {
      clearTimeout(timeout); state.requests.delete(controller);
      if (sequence === state.liveSequence) { frame.busy = false; state.liveFrameActive = Math.max(0, state.liveFrameActive - 1); pumpLiveFrames(); }
    }
  }
  function applyMode() {
    const live = state.mode === "live";
    $("live-grid").hidden = !live; $("replay-surface").hidden = live; $("playback-panel").hidden = live; $("layout-controls").hidden = !live;
    $("live-tab").classList.toggle("active", live); $("replay-tab").classList.toggle("active", !live);
    $("live-tab").setAttribute("aria-selected", String(live)); $("replay-tab").setAttribute("aria-selected", String(!live));
    if (!live) $("grid-pagination").hidden = true;
    $("preview-toggle").hidden = !live;
    $("preview-toggle").textContent = state.livePaused ? "继续预览" : "暂停预览";
    setSidePane(live ? "channels" : "archives"); updateViewActions();
  }
  async function changeMode(mode, date = null) {
    if (state.mode === mode) return;
    state.mode = mode; state.searchSequence++; state.seekSequence++; abortArchiveRequests(); stopLive(); stopPlayback(); resetZoom(); applyMode();
    if (mode === "live") renderLive();
    else { drawTimeline(); if (state.cameraId) { if (date) await loadDay(date); else await loadToday(); } else showReplayMessage(emptyCamerasMessage()); }
  }
  function setSidebar(visible) { $("sidebar").hidden = !visible; $("sidebar-toggle").setAttribute("aria-expanded", String(visible)); requestAnimationFrame(drawTimeline); }
  function showReplayMessage(message) { $("replay-empty").textContent = message; $("replay-empty").hidden = !message; }
  function archiveWindow(time) {
    const half = Math.min(14 * 86400000, Math.max(24 * 3600000, state.span * 1.5));
    return [Math.max(0, time - half), Math.min(253402214400000, time + half)];
  }
  function scheduleArchivePrefetch() {
    if (!state.authenticated || state.mode !== "replay" || state.explicitRange || !state.cameraId || state.archiveBusy) return;
    clearTimeout(state.prefetchTimer);
    const margin = state.span * 0.75;
    if (state.cursor - margin >= state.queryFrom && state.cursor + margin <= state.queryTo) return;
    state.prefetchTimer = setTimeout(() => {
      if (!state.authenticated || state.mode !== "replay" || state.explicitRange || document.hidden || state.archiveBusy) return;
      const range = archiveWindow(state.cursor); queryArchives(range[0], range[1], false, true).catch(report);
    }, 140);
  }
  function updateRangeControls() {
    $("range-badge").textContent = state.explicitRange ? "区间筛选" : "连续回放";
    $("range-badge").hidden = false;
    $("clear-range").hidden = !state.explicitRange;
    $("range-toggle").setAttribute("aria-expanded", String(!$("search-form").hidden));
  }
  function setSidePane(pane) {
    state.sidePane = pane;
    $("channels-pane").hidden = pane !== "channels"; $("archives-pane").hidden = pane !== "archives";
    for (const name of ["channels", "archives"]) {
      const button = $("side-" + name + "-tab"), active = pane === name;
      button.classList.toggle("active", active); button.setAttribute("aria-selected", String(active));
    }
    if (pane === "archives" && state.authenticated && state.cameraId) loadCalendar(state.calendarMonth || monthKey(state.cursor)).catch(report);
  }
  function filterCameras() {
    const term = $("camera-filter").value.trim().toLocaleLowerCase();
    for (const row of $("camera-list").querySelectorAll(".camera-row")) row.hidden = !!term && !row.textContent.toLocaleLowerCase().includes(term);
  }
  function shiftDay(date, direction) {
    const value = new Date(date + "T12:00:00Z");
    if (!Number.isFinite(value.getTime())) return "";
    value.setUTCDate(value.getUTCDate() + direction); return value.toISOString().slice(0, 10);
  }
  async function loadDay(date, latest = false) {
    const from = inputEpoch(date + "T00:00:00"), next = shiftDay(date, 1), to = inputEpoch(next + "T00:00:00");
    if (!validTime(from) || !validTime(to) || from >= to) { notice("请选择有效的录像日期"); return; }
    const intent = beginNavigation(); state.explicitRange = false; state.listLimit = 100; updateRangeControls();
    stopPlayback(); state.cursor = from; $("recording-date").value = date; drawTimeline();
    loadCalendar(date.slice(0, 7)).catch(report);
    const range = archiveWindow(latest ? Math.min(to - 1, serverNow()) : from + (to - from) / 2);
    const ok = await queryArchives(range[0], range[1], false);
    if (!ok || intent !== state.seekSequence || state.mode !== "replay") return;
    const clips = playableClips().filter(clip => clip.endMs > from && clip.startMs < to);
    const clip = latest ? clips.at(-1) : clips[0];
    state.cursor = clip ? latest ? Math.min(to - 1, clip.endMs - Math.min(1000, (clip.endMs - clip.startMs) / 2)) : Math.max(from, clip.startMs) : from;
    if (clip) seekTo(state.cursor, false);
    else { drawTimeline(); showReplayMessage(date === dayKey(serverNow()) && selectedCamera()?.recording ? "录像刚开始，正在准备可回放画面…" : "这一天没有录像，可选择日历中有标记的日期"); }
  }
  async function loadToday() {
    await loadDay(dayKey(serverNow()), true);
  }
  async function loadLatest() {
    if (!state.cameraId || state.mode !== "replay") return;
    const intent = beginNavigation(), cameraId = state.cameraId; stopPlayback();
    $("latest").disabled = true;
    try {
      const bounds = await request(`/api/v1/recording-bounds?channelId=${encodeURIComponent(cameraId)}`, { group: "navigation" });
      if (intent !== state.seekSequence || cameraId !== state.cameraId || state.mode !== "replay") return;
      const end = Date.parse(bounds.lastRecording);
      if (!validTime(end)) { notice("当前通道还没有可回放录像"); return; }
      state.explicitRange = false; state.listLimit = 100; updateRangeControls();
      const range = archiveWindow(end), ok = await queryArchives(range[0], range[1], false);
      if (!ok || intent !== state.seekSequence || cameraId !== state.cameraId || state.mode !== "replay") return;
      const clip = playableClips().at(-1);
      if (clip) {
        seekTo(clip.endMs - Math.min(1000, (clip.endMs - clip.startMs) / 2), false);
        loadCalendar(monthKey(state.cursor)).catch(report);
      } else notice("最新录像已清理或暂不可播放，请刷新后重试");
    } finally { $("latest").disabled = false; }
  }
  async function loadCalendar(month, background = false) {
    if (!state.authenticated || !state.cameraId || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) return;
    if (state.calendarCameraId === state.cameraId && state.calendarMonth === month && Date.now() - state.calendarUpdatedAt < 15000) { renderCalendar(); return; }
    abortCalendarRequests(); const sequence = ++state.calendarSequence, cameraId = state.cameraId;
    state.calendarMonth = month;
    if (!background) { state.calendarDays = []; state.calendarCameraId = ""; state.calendarUpdatedAt = 0; renderCalendar(); $("calendar-month").textContent = month.replace("-", " 年 ") + " 月 · 读取中"; }
    try {
      const result = await request(`/api/v1/recording-days?channelId=${encodeURIComponent(cameraId)}&month=${encodeURIComponent(month)}`, { group: "calendar" });
      if (sequence !== state.calendarSequence || cameraId !== state.cameraId || !state.authenticated) return;
      state.calendarDays = Array.isArray(result.days) ? result.days.filter(day => typeof day.date === "string" && day.date.startsWith(month + "-")) : [];
      state.calendarCameraId = cameraId; state.calendarUpdatedAt = Date.now(); renderCalendar();
    } catch (error) {
      if (sequence === state.calendarSequence && error.name !== "AbortError") { $("calendar-month").textContent = month.replace("-", " 年 ") + " 月 · 重试"; if (!background) report(error); }
    }
  }
  function renderCalendar() {
    const grid = $("calendar-grid"); grid.replaceChildren();
    if (!state.calendarMonth) { $("calendar-month").textContent = "录像日历"; return; }
    const month = state.calendarMonth, first = new Date(month + "-01T12:00:00Z"), count = new Date(first.getUTCFullYear(), first.getUTCMonth() + 1, 0).getDate();
    const offset = (first.getUTCDay() + 6) % 7, today = dayKey(serverNow()), selected = dayKey(state.cursor);
    $("calendar-month").textContent = month.replace("-", " 年 ") + " 月";
    for (let index = 0; index < 42; index++) {
      const day = index - offset + 1, date = shiftDay(month + "-01", day - 1), entry = state.calendarDays.find(item => item.date === date);
      const button = document.createElement("button"); button.type = "button"; button.className = "calendar-day";
      button.dataset.date = date; button.textContent = String(new Date(date + "T12:00:00Z").getUTCDate());
      const outside = day < 1 || day > count; button.classList.toggle("outside", outside); button.disabled = outside || !state.cameraId;
      button.classList.toggle("has-recording", Boolean(entry)); button.classList.toggle("has-persons", Boolean(entry?.hasPersons));
      button.classList.toggle("is-today", date === today); button.classList.toggle("selected", date === selected);
      button.setAttribute("aria-pressed", String(date === selected));
      button.setAttribute("aria-label", `${date}${entry ? `，${entry.recordingCount || 0} 段录像${entry.hasPersons ? "，有人员事件" : ""}${entry.active ? "，录制中" : ""}` : "，没有录像"}`);
      button.title = button.getAttribute("aria-label");
      button.addEventListener("click", () => { (state.mode !== "replay" ? changeMode("replay", date) : loadDay(date)).catch(report); });
      grid.append(button);
    }
    $("calendar-next").disabled = month >= monthKey(serverNow());
  }
  function changeCalendarMonth(direction) {
    const date = new Date((state.calendarMonth || monthKey(state.cursor)) + "-01T12:00:00Z"); date.setUTCMonth(date.getUTCMonth() + direction);
    const month = date.toISOString().slice(0, 7); if (month > monthKey(serverNow())) return;
    loadCalendar(month).catch(report);
  }
  async function queryArchives(from, to, chooseLatest = false, background = false) {
    if (!state.cameraId || state.mode !== "replay" || !state.authenticated) return false;
    if (!validTime(from) || !validTime(to) || from >= to) { notice("结束时间必须晚于开始时间"); return false; }
    if (to - from > 31 * 86400000) { notice("每次最多查询 31 天，可拖动时间轴继续查看其他日期"); return false; }
    abortArchiveRequests(); const sequence = ++state.searchSequence, cameraId = state.cameraId; let applied = false;
    state.archiveBusy = true;
    if (!background) { $("search-submit").disabled = true; $("archive-summary").textContent = "正在查询…"; }
    try {
      const data = await request(`/api/v1/recordings?channelId=${encodeURIComponent(cameraId)}&from=${encodeURIComponent(new Date(from).toISOString())}&to=${encodeURIComponent(new Date(to).toISOString())}`, { group: "archive" });
      if (sequence !== state.searchSequence || state.cameraId !== cameraId || state.mode !== "replay" || !state.authenticated || document.hidden) return false;
      state.recordings = (Array.isArray(data.recordings) ? data.recordings : []).map(clip => {
        const rawStartMs = Date.parse(clip.start), rawEndMs = Date.parse(clip.end) || rawStartMs + Number(clip.durationSeconds || 0) * 1000;
        return { ...clip, rawStartMs, rawEndMs, startMs: Math.max(from, rawStartMs), endMs: Math.min(to, rawEndMs) };
      })
        .filter(clip => Number.isFinite(clip.startMs) && Number.isFinite(clip.endMs) && clip.endMs > clip.startMs).sort((a, b) => a.startMs - b.startMs);
      state.queryFrom = from; state.queryTo = to; state.lastArchiveRefresh = Date.now(); applied = true;
      if (!background) { $("search-from").value = inputTime(from); $("search-to").value = inputTime(to); }
      const clips = playableClips(), processing = state.recordings.filter(personsPending).length;
      $("archive-summary").textContent = `${clips.length} 段录像${state.recordings.some(clip => clip.active) ? " · 正在录制" : ""}${processing ? ` · ${processing} 段人员标记待完成` : ""}`;
      if (chooseLatest) {
        stopPlayback();
        state.cursor = clips.length ? state.explicitRange ? clips[0].startMs : clips.at(-1).endMs - Math.min(1000, (clips.at(-1).endMs - clips.at(-1).startMs) / 2) : Math.min(to, serverNow());
        if (clips.length) seekTo(state.cursor, false);
        else showReplayMessage(!state.explicitRange && selectedCamera()?.recording && to >= Date.now() + state.serverOffset - 15000 ? "录像刚开始，正在准备可回放画面…" : "这个时间区间没有录像");
      } else if (state.activeClip) {
        const latest = state.recordings.find(clip => clip.id === state.activeClip.id);
        if (latest) state.activeClip = latest;
        else if (background && !state.dragging && state.activeClip.endMs > from && state.activeClip.startMs < to) { stopPlayback(); showReplayMessage("当前录像已清理，请重新选择时间"); }
      } else if (clips.length) showReplayMessage("已有录像，点击播放或拖动时间轴选择时间");
      $("play-pause").disabled = !clips.length; renderArchiveList(); updateRangeControls(); updateDownload(); drawTimeline();
      if (state.waitingForRecording && state.playing && !state.dragging) continuePlayback().catch(report);
      return true;
    } catch (error) {
      if (sequence === state.searchSequence && error.name !== "AbortError") $("archive-summary").textContent = "查询未完成，请重试";
      if (error.name === "AbortError") return false;
      throw error;
    } finally { if (sequence === state.searchSequence) { state.archiveBusy = false; $("search-submit").disabled = false; if (applied) renderArchiveList(); } }
  }
  function clipAt(time) { return state.recordings.find(clip => time >= clip.startMs && time < clip.endMs); }
  function playableClips() { return state.recordings.filter(clip => clip.endMs > clip.startMs); }
  function seekTo(time, autoplay = true) {
    if (!state.authenticated || state.mode !== "replay" || !state.cameraId) return;
    state.cursor = time; drawTimeline(); const clip = clipAt(time);
    if (!clip) { stopPlayback(); showReplayMessage("此时没有录像，拖动时间轴或点击下一段"); return; }
    const offset = Math.max(0, (time - clip.rawStartMs) / 1000);
    const duration = Math.min(300, Math.max(0.1, (clip.endMs - time) / 1000));
    const sequence = ++state.mediaSequence; video.pause(); video.removeAttribute("src"); video.load();
    state.activeClip = clip; state.mediaBase = time; state.mediaEnd = Math.min(clip.endMs, time + duration * 1000); state.playing = autoplay; state.waitingForRecording = false;
    showReplayMessage("正在准备录像…"); $("play-pause").textContent = autoplay ? "暂停" : "播放";
    const fallback = `/api/v1/recordings/${encodeURIComponent(clip.id)}/media`;
    const url = new URL(cleanUrl(clip.mediaUrl, fallback), location.origin); url.searchParams.set("offsetSeconds", offset.toFixed(3)); url.searchParams.set("durationSeconds", duration.toFixed(3));
    video.src = url.pathname + url.search; video.defaultPlaybackRate = state.playbackRate; video.playbackRate = state.playbackRate; video.load();
    updateDownload(); renderArchiveSelection(); applyZoom();
    video.onloadeddata = () => {
      if (sequence !== state.mediaSequence) return;
      video.playbackRate = state.playbackRate;
      showReplayMessage(""); if (state.playing && !document.hidden && !state.dragging) video.play().catch(error => { if (sequence === state.mediaSequence) { state.playing = false; $("play-pause").textContent = "播放"; if (error.name !== "AbortError") notice("录像已准备好，点击播放继续"); } });
    };
  }
  async function adjacentClip(direction) {
    const clips = playableClips();
    const clip = direction > 0 ? clips.find(c => c.startMs > state.cursor + 50) : clips.findLast(c => c.startMs < state.cursor - 50);
    if (clip) { await locateTime(clip.startMs, state.playing); return; }
    if (!state.explicitRange) {
      const autoplay = state.playing, intent = beginNavigation(), cursor = state.cursor, range = direction > 0 ? [cursor, cursor + 31 * 86400000] : [Math.max(0, cursor - 31 * 86400000), cursor];
      stopPlayback();
      const ok = await queryArchives(range[0], range[1], false);
      if (!ok || intent !== state.seekSequence) return;
      const found = direction > 0 ? playableClips().find(c => c.startMs > cursor + 50) : playableClips().findLast(c => c.startMs < cursor - 50);
      if (found) { seekTo(found.startMs, autoplay); return; }
    }
    notice(direction > 0 ? "当前范围没有下一段录像，可从日历选择日期" : "当前范围没有上一段录像，可从日历选择日期");
  }
  async function continuePlayback() {
    if (!state.authenticated || !state.activeClip || state.mode !== "replay" || !state.playing || document.hidden || state.dragging || state.continuationBusy) return;
    state.continuationBusy = true;
    try {
    const clip = state.activeClip, sequence = state.mediaSequence, cameraId = state.cameraId, reached = state.mediaEnd;
    if (reached < clip.endMs - 50) { seekTo(reached, true); return; }
    state.cursor = reached;
    if (state.explicitRange && reached >= state.queryTo - 50) {
      state.cursor = state.queryTo; state.playing = false; $("play-pause").textContent = "播放"; drawTimeline(); notice("已播放到查询区间的结束时间"); return;
    }
    let next = playableClips().find(c => c.endMs > reached + 50 && c.startMs >= reached - 100);
    if (!next && (clip.active || (selectedCamera()?.recording && Date.now() + state.serverOffset - reached < 15000))) {
      state.waitingForRecording = true; $("play-pause").textContent = "暂停"; showReplayMessage("已到当前录像末尾，等待新画面…"); drawTimeline(); return;
    }
    if (!next && !state.explicitRange) {
      const range = archiveWindow(reached + 6 * 3600000);
      try { await queryArchives(range[0], range[1], false, true); } catch (error) { report(error); }
      if (!state.authenticated || state.mode !== "replay" || state.cameraId !== cameraId || state.mediaSequence !== sequence || !state.playing || document.hidden) return;
      next = playableClips().find(c => c.endMs > reached + 50);
    }
    if (next) { if (next.startMs - reached > 2000) notice("已跳过没有录像的时间段"); seekTo(Math.max(next.startMs, reached), true); }
    else { state.playing = false; state.waitingForRecording = false; $("play-pause").textContent = "播放"; drawTimeline(); notice("已播放到当前可用录像的末尾"); }
    } finally { state.continuationBusy = false; }
  }
  function personEvents() {
    const events = [];
    for (const clip of state.recordings) for (const [index, event] of (Array.isArray(clip.persons) ? clip.persons : []).entries()) {
      const startMs = Math.max(clip.startMs, clip.rawStartMs + Math.max(0, Number(event.startSeconds) || 0) * 1000);
      const endMs = Math.min(clip.endMs, clip.rawStartMs + Math.max(0, Number(event.endSeconds) || 0) * 1000);
      if (Number.isFinite(startMs) && Number.isFinite(endMs) && endMs > startMs) events.push({ id: `person-${clip.id}-${index}`, startMs, endMs, clip, person: true });
    }
    events.sort((a, b) => a.startMs - b.startMs);
    const merged = [];
    for (const event of events) {
      const previous = merged.at(-1);
      if (previous && event.startMs <= previous.endMs + 500) previous.endMs = Math.max(previous.endMs, event.endMs);
      else merged.push(event);
    }
    return merged;
  }
  function durationLabel(milliseconds) {
    const seconds = Math.max(1, Math.round(milliseconds / 1000));
    if (seconds < 60) return seconds + " 秒";
    return Math.floor(seconds / 60) + " 分 " + (seconds % 60 ? seconds % 60 + " 秒" : "");
  }
  function renderArchiveList() {
    const list = $("archive-list");
    const persons = personEvents(), date = dayKey(state.cursor), from = state.explicitRange ? state.queryFrom : inputEpoch(date + "T00:00:00"), to = state.explicitRange ? state.queryTo : inputEpoch(shiftDay(date, 1) + "T00:00:00");
    const recordings = playableClips().filter(clip => clip.endMs > from && clip.startMs < to);
    const candidates = state.archiveFilter === "persons" ? persons : recordings.map(clip => ({ id: clip.id, startMs: clip.startMs, endMs: clip.endMs, clip }));
    const items = candidates.filter(item => item.endMs > from && item.startMs < to).map(item => ({ ...item, startMs: Math.max(from, item.startMs), endMs: Math.min(to, item.endMs) }));
    state.archiveListDate = date;
    const pending = recordings.some(personsPending), failed = recordings.some(clip => String(clip.personStatus).toUpperCase() === "FAILED");
    $("play-pause").disabled = !recordings.length || state.mode !== "replay" || !state.cameraId;
    const processing = recordings.filter(personsPending).length;
    if (state.authenticated && state.mode === "replay" && !state.archiveBusy) $("archive-summary").textContent = `${recordings.length} 段录像${recordings.some(clip => clip.active) ? " · 正在录制" : ""}${processing ? ` · ${processing} 段人员标记待完成` : ""}`;
    $("archive-list-summary").textContent = `${state.explicitRange ? "筛选区间" : date} · ${items.length} ${state.archiveFilter === "persons" ? "个事件" : "段录像"}${pending ? " · 人员标记仍在更新" : ""}${failed ? " · 部分人员识别失败" : ""}`;
    $("archive-more").hidden = items.length <= state.listLimit;
    $("previous-person").disabled = !persons.length; $("next-person").disabled = !persons.length;
    const key = `${state.authenticated}:${state.administrator}:${state.cameraId}:${state.mode}:${state.archiveFilter}:${state.listLimit}:${date}:${state.explicitRange}:${pending}:${failed}:` + items.slice(0, state.listLimit).map(item => `${item.id}:${item.startMs}:${item.endMs}:${item.clip.active}:${item.clip.bytes}`).join("|");
    if (key === state.archiveListKey) { renderArchiveSelection(); return; }
    state.archiveListKey = key; list.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("div"); empty.className = "empty-list";
      empty.textContent = !state.authenticated ? "" : !state.cameraId ? emptyCamerasMessage(true) : state.mode !== "replay" ? "切换到录像回放查看录像" : state.archiveFilter === "persons" ? pending ? "人员标记仍在更新\n可先查看全部录像" : "当前范围没有已标记的人员事件\n未标记的时间不能保证没有人" : "当前范围没有录像\n选择日历日期或拖动时间轴";
      list.append(empty);
    }
    for (const item of items.slice(0, state.listLimit)) {
      const row = document.createElement("button"); row.type = "button"; row.className = "archive-row"; row.dataset.start = String(item.startMs); row.dataset.end = String(item.endMs);
      const time = document.createElement("span"); time.className = "archive-row-time"; time.textContent = `${clockLabel(item.startMs, true)} — ${clockLabel(item.endMs, dayKey(item.startMs) !== dayKey(item.endMs))}`;
      const detail = document.createElement("span"); detail.className = "archive-row-detail"; detail.textContent = `${durationLabel(item.endMs - item.startMs)}${item.person ? " · 人员事件" : ` · ${bytes(item.clip.bytes)}`}`;
      row.append(time, detail);
      if (item.person || item.clip.active) {
        const tag = document.createElement("span"); tag.className = "archive-row-tag " + (item.person ? "person" : "active"); tag.textContent = item.person ? "有人" : "录制中"; row.append(tag);
      }
      row.title = `${dateTime(item.startMs)} 至 ${dateTime(item.endMs)}，点击定位`;
      row.addEventListener("click", () => locateTime(item.startMs, true).catch(report)); list.append(row);
    }
    renderArchiveSelection();
  }
  function renderArchiveSelection() {
    for (const row of $("archive-list").querySelectorAll(".archive-row")) {
      const selected = state.cursor >= Number(row.dataset.start) && state.cursor < Number(row.dataset.end);
      row.classList.toggle("selected", selected); row.setAttribute("aria-pressed", String(selected));
    }
  }
  async function adjacentPerson(direction) {
    const events = personEvents(), event = direction > 0 ? events.find(item => item.startMs > state.cursor + 50) : events.findLast(item => item.startMs < state.cursor - 50);
    if (event) await locateTime(event.startMs, true);
    else notice(direction > 0 ? "当前范围没有下一个人员事件，可选择其他日期" : "当前范围没有上一个人员事件，可选择其他日期");
  }
  function updateDownload() { $("download-current").disabled = !state.authenticated || state.mode !== "replay" || !state.activeClip || !state.cameras.some(camera => camera.id === state.cameraId); }
  function downloadCurrent() {
    const clip = state.activeClip; if (!clip || !state.authenticated || state.mode !== "replay") return;
    const fallback = `/api/v1/recordings/${encodeURIComponent(clip.id)}/download`, href = cleanUrl(clip.downloadUrl, fallback);
    const link = document.createElement("a"); link.href = href; link.download = ""; link.rel = "noopener"; document.body.append(link); link.click(); link.remove();
    if (clip.active) notice("正在下载这段录像目前已保存的部分，录制会继续");
  }
  async function locateTime(time, autoplay = state.playing) {
    if (!validTime(time)) { notice("请选择有效的时间"); return; }
    beginNavigation(); stopPlayback(); state.cursor = time; await finishTimelineSeek(autoplay);
  }
  function mediaTarget() {
    return state.mode === "replay" ? video : $("live-grid").querySelector(`.tile.selected img`);
  }
  function updateViewActions() {
    const available = state.authenticated && Boolean(state.cameraId);
    $("snapshot").disabled = !available || (state.mode === "live" && state.livePaused);
    $("zoom-toggle").disabled = !available || (state.mode === "live" && state.livePaused);
    $("preview-toggle").disabled = !available;
  }
  function showLivePaused() {
    $("live-grid").className = "live-grid layout-1"; $("grid-pagination").hidden = true;
    const empty = document.createElement("div"); empty.className = "empty-tile"; empty.textContent = "预览已暂停 · 后台录像继续\n点击“继续预览”恢复画面"; $("live-grid").replaceChildren(empty);
  }
  function toggleLivePreview() {
    state.livePaused = !state.livePaused; resetZoom(); stopLive(); renderLive();
    $("preview-toggle").textContent = state.livePaused ? "继续预览" : "暂停预览";
    $("preview-toggle").setAttribute("aria-pressed", String(state.livePaused)); updateViewActions();
  }
  function resetZoom() {
    if (state.zoomTarget) state.zoomTarget.style.transform = "";
    state.zoom = 1; state.zoomX = 0; state.zoomY = 0; state.zoomActive = false; state.zoomDragging = false; state.zoomTarget = null;
    $("zoom-controls").hidden = true; $("zoom-toggle").classList.remove("active"); $("zoom-toggle").setAttribute("aria-pressed", "false");
    $("view-area").classList.remove("zooming"); $("zoom-level").textContent = "1×";
  }
  function applyZoom() {
    const target = mediaTarget();
    if (state.zoomTarget && state.zoomTarget !== target) state.zoomTarget.style.transform = "";
    state.zoomTarget = target;
    if (target) {
      const maxX = target.clientWidth * (state.zoom - 1) / 2, maxY = target.clientHeight * (state.zoom - 1) / 2;
      state.zoomX = Math.max(-maxX, Math.min(maxX, state.zoomX)); state.zoomY = Math.max(-maxY, Math.min(maxY, state.zoomY));
      target.style.transform = state.zoomActive ? `translate(${state.zoomX}px, ${state.zoomY}px) scale(${state.zoom})` : "";
    }
    $("zoom-controls").hidden = !state.zoomActive; $("zoom-level").textContent = state.zoom + "×";
    $("zoom-toggle").classList.toggle("active", state.zoomActive); $("zoom-toggle").setAttribute("aria-pressed", String(state.zoomActive));
    $("zoom-out").disabled = state.zoom <= 1; $("zoom-in").disabled = state.zoom >= 4; $("view-area").classList.toggle("zooming", state.zoomActive && state.zoom > 1);
  }
  function changeZoom(amount) { state.zoom = Math.max(1, Math.min(4, state.zoom + amount)); applyZoom(); }
  async function snapshot() {
    const target = mediaTarget(), camera = selectedCamera(); if (!state.authenticated || !target || !camera) { notice("当前没有可抓取的画面"); return; }
    const width = target instanceof HTMLVideoElement ? target.videoWidth : target.naturalWidth, height = target instanceof HTMLVideoElement ? target.videoHeight : target.naturalHeight;
    if (!width || !height || target instanceof HTMLVideoElement && target.readyState < 2) { notice("画面还未准备好，请稍后抓图"); return; }
    const canvas = document.createElement("canvas"); canvas.width = width; canvas.height = height;
    const cameraId = state.cameraId, mode = state.mode, captureTime = mode === "replay" ? state.cursor : serverNow();
    try {
      canvas.getContext("2d").drawImage(target, 0, 0, width, height);
      const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/png"));
      if (!blob) { notice("当前浏览器无法抓图，请稍后重试"); return; }
      if (!state.authenticated || cameraId !== state.cameraId || mode !== state.mode) return;
      const url = URL.createObjectURL(blob), link = document.createElement("a"); link.href = url;
      const name = (camera.name || "摄像头").replace(/[\\/:*?"<>|\x00-\x1f]/g, "_").slice(0, 64);
      link.download = `${name}_${inputTime(captureTime).replace(/[:T]/g, "-")}.png`;
      document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 30000);
      notice("抓图已交给浏览器下载");
    } catch { notice("当前画面无法抓图，请等待画面恢复后重试"); }
    finally { canvas.width = 0; canvas.height = 0; }
  }
  function drawTimeline() {
    if (state.mode !== "replay" || $("console").hidden) return;
    const rect = timeline.getBoundingClientRect(); if (!rect.width || !rect.height) return;
    const scale = devicePixelRatio || 1; timeline.width = Math.round(rect.width * scale); timeline.height = Math.round(rect.height * scale);
    const context = timeline.getContext("2d"); context.scale(scale, scale); const width = rect.width, height = rect.height;
    context.clearRect(0, 0, width, height); context.fillStyle = "#fff"; context.fillRect(0, 0, width, height);
    const left = state.cursor - state.span / 2, right = state.cursor + state.span / 2;
    const x = time => (time - left) / state.span * width;
    context.fillStyle = "#edf0f4"; context.fillRect(0, 31, width, 18);
    for (const clip of state.recordings) {
      if (clip.endMs < left || clip.startMs > right) continue;
      const first = Math.max(0, x(clip.startMs)), last = Math.min(width, x(clip.endMs));
      context.fillStyle = clip.active ? "#bec8d4" : "#518abc"; context.fillRect(first, 32, Math.max(1, last - first), 16);
    }
    // Draw short events after every recording block so the next clip cannot cover their visible minimum width.
    for (const clip of state.recordings) {
      if (clip.endMs < left || clip.startMs > right) continue;
      for (const event of Array.isArray(clip.persons) ? clip.persons : []) {
        const start = Math.max(clip.startMs, clip.rawStartMs + Number(event.startSeconds || 0) * 1000), end = Math.min(clip.endMs, clip.rawStartMs + Number(event.endSeconds || 0) * 1000);
        if (end <= start || end < left || start > right) continue;
        const p1 = Math.max(0, x(start)), p2 = Math.min(width, x(end)); context.fillStyle = "#ef9b3d"; context.fillRect(p1, 32, Math.max(2, p2 - p1), 16);
      }
    }
    const intervals = [1000, 5000, 10000, 30000, 60000, 300000, 600000, 1800000, 3600000, 7200000, 21600000, 43200000];
    const step = intervals.find(v => v / state.span * width >= 86) || 86400000;
    context.font = '10px "Microsoft YaHei UI", sans-serif'; context.textAlign = "center";
    for (let tick = Math.ceil(left / step) * step; tick <= right; tick += step) {
      const position = Math.round(x(tick)) + 0.5; context.strokeStyle = "#dde2e8"; context.beginPath(); context.moveTo(position, 23); context.lineTo(position, 31); context.moveTo(position, 49); context.lineTo(position, height - 9); context.stroke();
      context.fillStyle = "#7e8690"; context.fillText(clockLabel(tick, state.span >= 21600000).slice(0, state.span >= 21600000 ? 11 : step >= 60000 ? 5 : 8), position, 17);
    }
    $("playback-time").textContent = dateTime(state.cursor); timeline.setAttribute("aria-valuetext", dateTime(state.cursor));
    const date = dayKey(state.cursor);
    $("timeline-current-date").textContent = date; $("recording-date").value = date;
    timeline.setAttribute("aria-valuenow", String(Math.round(state.cursor / 1000)));
    timeline.setAttribute("aria-valuemin", String(Math.floor((state.explicitRange ? state.queryFrom : 0) / 1000)));
    timeline.setAttribute("aria-valuemax", String(Math.ceil((state.explicitRange ? state.queryTo : serverNow()) / 1000)));
    if (state.archiveListDate !== date) { state.listLimit = 100; renderArchiveList(); }
    else renderArchiveSelection();
  }
  function scheduleTimeline() { if (!state.timelineRaf) state.timelineRaf = requestAnimationFrame(() => { state.timelineRaf = null; drawTimeline(); }); }
  async function finishTimelineSeek(autoplay = state.playing) {
    if (!state.authenticated || state.mode !== "replay") return;
    const sequence = state.seekSequence, cameraId = state.cameraId, time = state.explicitRange ? Math.max(state.queryFrom, Math.min(state.queryTo - 1, state.cursor)) : Math.max(0, Math.min(253402214399999, state.cursor));
    state.cursor = time; clearTimeout(state.prefetchTimer);
    if (!state.explicitRange && (time < state.queryFrom || time >= state.queryTo)) {
      stopPlayback(); state.cursor = time; drawTimeline(); showReplayMessage("正在查询这个时间的录像…");
      const range = archiveWindow(time);
      try { if (!await queryArchives(range[0], range[1], false, true)) return; } catch (error) { report(error); return; }
      if (!state.authenticated || state.mode !== "replay" || state.cameraId !== cameraId || state.seekSequence !== sequence || document.hidden) return;
    }
    if (sequence !== state.seekSequence || cameraId !== state.cameraId) return;
    seekTo(time, autoplay); scheduleArchivePrefetch();
    if (state.calendarMonth !== monthKey(time)) loadCalendar(monthKey(time)).catch(report); else renderCalendar();
  }
  let dragX = 0, dragTime = 0, dragWasPlaying = false, dragMediaSequence = 0;
  timeline.addEventListener("pointerdown", event => {
    if (event.button !== 0 || !state.cameraId) return;
    beginNavigation(); state.dragging = true; dragWasPlaying = state.playing; dragMediaSequence = state.mediaSequence; dragX = event.clientX; dragTime = state.cursor; video.pause(); state.playing = false; $("play-pause").textContent = "播放"; timeline.setPointerCapture(event.pointerId); timeline.classList.add("dragging"); timeline.focus(); $("timeline-hover").hidden = true;
  });
  timeline.addEventListener("pointermove", event => {
    if (!state.dragging) {
      const rect = timeline.getBoundingClientRect(), position = Math.max(0, Math.min(rect.width, event.clientX - rect.left)), time = state.cursor + (position / rect.width - 0.5) * state.span;
      $("timeline-hover").textContent = validTime(time) ? dateTime(time) : ""; $("timeline-hover").hidden = !state.cameraId || !validTime(time);
      $("timeline-hover").style.left = Math.max(80, Math.min(rect.width - 80, position)) + "px"; return;
    }
    state.cursor = Math.max(0, Math.min(253402214399999, dragTime - (event.clientX - dragX) / timeline.getBoundingClientRect().width * state.span)); scheduleTimeline(); scheduleArchivePrefetch();
  });
  timeline.addEventListener("pointerup", event => {
    if (!state.dragging) return;
    if (Math.abs(event.clientX - dragX) < 4) { const rect = timeline.getBoundingClientRect(); state.cursor = dragTime + ((event.clientX - rect.left) / rect.width - 0.5) * state.span; }
    state.dragging = false; timeline.classList.remove("dragging"); if (timeline.hasPointerCapture(event.pointerId)) timeline.releasePointerCapture(event.pointerId); finishTimelineSeek(dragWasPlaying).catch(report);
  });
  timeline.addEventListener("pointerleave", () => { $("timeline-hover").hidden = true; });
  timeline.addEventListener("pointercancel", () => {
    if (!state.dragging) return;
    state.dragging = false; timeline.classList.remove("dragging");
    clearTimeout(state.prefetchTimer); if (dragMediaSequence !== state.mediaSequence || !state.authenticated || state.mode !== "replay") return;
    state.cursor = dragTime; state.playing = dragWasPlaying; $("play-pause").textContent = dragWasPlaying ? "暂停" : "播放"; drawTimeline();
    if (dragWasPlaying && state.activeClip && !state.waitingForRecording && !document.hidden) video.play().catch(() => { state.playing = false; $("play-pause").textContent = "播放"; });
  });
  timeline.addEventListener("wheel", event => {
    event.preventDefault(); const scales = [300000, 1800000, 3600000, 21600000, 86400000];
    const index = Math.max(0, scales.indexOf(state.span)); state.span = scales[Math.max(0, Math.min(scales.length - 1, index + (event.deltaY > 0 ? 1 : -1)))]; $("timeline-scale").value = String(state.span); drawTimeline(); scheduleArchivePrefetch();
  }, { passive: false });
  timeline.addEventListener("keydown", event => {
    const delta = (event.shiftKey ? 300 : event.ctrlKey ? 60 : 15) * 1000;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") { event.preventDefault(); locateTime(state.cursor + (event.key === "ArrowLeft" ? -delta : delta)).catch(report); }
    if (event.key === "Home" || event.key === "End") { const clips = playableClips(); if (!clips.length) return; event.preventDefault(); locateTime(event.key === "Home" ? clips[0].startMs : clips.at(-1).endMs - Math.min(500, (clips.at(-1).endMs - clips.at(-1).startMs) / 2)).catch(report); }
    if (event.key === " " || event.key === "Enter") { event.preventDefault(); $("play-pause").click(); }
  });
  video.addEventListener("timeupdate", () => {
    if (!state.activeClip || state.dragging) return;
    state.cursor = Math.min(state.activeClip.endMs, state.mediaEnd, state.mediaBase + video.currentTime * 1000); scheduleTimeline(); scheduleArchivePrefetch();
  });
  video.addEventListener("ended", () => continuePlayback().catch(report));
  video.addEventListener("error", () => { if (state.activeClip && video.getAttribute("src")) { state.playing = false; $("play-pause").textContent = "重试"; showReplayMessage("录像暂时无法播放，点击重试"); } });
  video.addEventListener("playing", () => { if (state.authenticated && state.mode === "replay" && state.activeClip && state.playing && !state.dragging && !document.hidden) { $("play-pause").textContent = "暂停"; showReplayMessage(""); } else video.pause(); });
  video.addEventListener("waiting", () => { if (state.activeClip) showReplayMessage("正在加载录像…"); });
  $("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    if (location.protocol !== "https:") { $("login-error").textContent = "请使用电脑端显示的 HTTPS 安全地址"; return; }
    $("login-error").textContent = ""; $("login-submit").disabled = true; $("login-submit").textContent = "正在登录…";
    try { const session = await request("/api/v1/session", { method: "POST", body: JSON.stringify({ username: $("username").value.trim(), password: $("password").value, remember: $("remember").checked }) }); await enterConsole(session); }
    catch (error) { if (error.name !== "AbortError") $("login-error").textContent = error.message || "无法连接录像机，请稍后重试"; }
    finally { $("login-submit").disabled = false; $("login-submit").textContent = "登录"; }
  });
  $("logout").addEventListener("click", async () => { $("logout").disabled = true; try { await request("/api/v1/session", { method: "DELETE" }); showLogin(); } catch (error) { report(error); } finally { $("logout").disabled = false; } });
  $("live-tab").addEventListener("click", () => changeMode("live").catch(report)); $("replay-tab").addEventListener("click", () => changeMode("replay").catch(report));
  $("camera-select").addEventListener("change", event => chooseCamera(event.target.value));
  document.querySelectorAll("[data-layout]").forEach(button => button.addEventListener("click", () => { state.expandedLayout = 0; setLayout(Number(button.dataset.layout)); }));
  $("sidebar-toggle").addEventListener("click", () => setSidebar($("sidebar").hidden));
  $("sidebar-close").addEventListener("click", () => setSidebar(false));
  $("notice-close").addEventListener("click", () => { clearTimeout(state.noticeTimer); $("notice").hidden = true; });
  $("side-channels-tab").addEventListener("click", () => setSidePane("channels"));
  $("side-archives-tab").addEventListener("click", () => setSidePane("archives"));
  $("camera-filter").addEventListener("input", filterCameras);
  $("fullscreen").addEventListener("click", () => {
    const fallback = () => notice("当前浏览器不支持全屏，可收起右侧通道面板放大画面");
    if (document.fullscreenElement) document.exitFullscreen().catch(report);
    else if (typeof $("console").requestFullscreen === "function") $("console").requestFullscreen().catch(fallback);
    else fallback();
  });
  $("page-prev").addEventListener("click", () => { state.page--; state.liveKey = ""; renderLive(); }); $("page-next").addEventListener("click", () => { state.page++; state.liveKey = ""; renderLive(); });
  $("search-form").addEventListener("submit", event => {
    event.preventDefault(); const from = inputEpoch($("search-from").value), to = inputEpoch($("search-to").value);
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) { notice("结束时间必须晚于开始时间"); return; }
    if (to - from > 31 * 86400000) { notice("每次最多查询 31 天，可拖动时间轴继续查看其他日期"); return; }
    beginNavigation(); stopPlayback(); state.explicitRange = true; state.listLimit = 100; state.recordings = []; state.queryFrom = from; state.queryTo = to;
    state.cursor = from; renderArchiveList(); drawTimeline(); updateRangeControls();
    queryArchives(from, to, true).then(ok => { if (ok) { $("search-form").hidden = true; updateRangeControls(); loadCalendar(monthKey(state.cursor)).catch(report); } }).catch(report);
  });
  $("today").addEventListener("click", () => loadToday().catch(report));
  $("latest").addEventListener("click", () => loadLatest().catch(report));
  $("date-prev").addEventListener("click", () => loadDay(shiftDay($("recording-date").value || dayKey(state.cursor), -1)).catch(report));
  $("date-next").addEventListener("click", () => loadDay(shiftDay($("recording-date").value || dayKey(state.cursor), 1)).catch(report));
  $("recording-date").addEventListener("change", event => loadDay(event.target.value).catch(report));
  $("calendar-prev").addEventListener("click", () => changeCalendarMonth(-1)); $("calendar-next").addEventListener("click", () => changeCalendarMonth(1));
  $("range-toggle").addEventListener("click", () => {
    $("search-form").hidden = !$("search-form").hidden; $("jump-form").hidden = true; $("jump-toggle").setAttribute("aria-expanded", "false");
    if (!$("search-form").hidden) { const date = dayKey(state.cursor); $("search-from").value = inputTime(state.explicitRange ? state.queryFrom : inputEpoch(date + "T00:00:00")); $("search-to").value = inputTime(state.explicitRange ? state.queryTo : inputEpoch(shiftDay(date, 1) + "T00:00:00")); }
    updateRangeControls();
  });
  $("clear-range").addEventListener("click", async () => {
    const time = state.cursor, autoplay = state.playing; beginNavigation(); stopPlayback(); state.explicitRange = false; state.listLimit = 100; updateRangeControls();
    const range = archiveWindow(time), intent = state.seekSequence;
    try { const ok = await queryArchives(range[0], range[1], false); if (ok && intent === state.seekSequence) { state.cursor = time; seekTo(time, autoplay); } } catch (error) { report(error); }
  });
  $("timeline-scale").addEventListener("change", event => { state.span = Number(event.target.value); drawTimeline(); scheduleArchivePrefetch(); });
  $("previous-clip").addEventListener("click", () => adjacentClip(-1).catch(report)); $("next-clip").addEventListener("click", () => adjacentClip(1).catch(report));
  $("skip-back").addEventListener("click", () => locateTime(state.cursor - 10000).catch(report)); $("skip-forward").addEventListener("click", () => locateTime(state.cursor + 10000).catch(report));
  $("previous-person").addEventListener("click", () => adjacentPerson(-1).catch(report)); $("next-person").addEventListener("click", () => adjacentPerson(1).catch(report));
  $("playback-speed").addEventListener("change", event => {
    const value = Number(event.target.value); if (![0.5, 1, 2, 4, 8].includes(value)) return;
    state.playbackRate = value; video.defaultPlaybackRate = value; video.playbackRate = value;
  });
  $("jump-toggle").addEventListener("click", () => {
    $("jump-form").hidden = !$("jump-form").hidden; $("search-form").hidden = true; updateRangeControls();
    $("jump-toggle").setAttribute("aria-expanded", String(!$("jump-form").hidden));
    if (!$("jump-form").hidden) { $("jump-time").value = inputTime(state.cursor); $("jump-time").focus(); }
  });
  $("jump-cancel").addEventListener("click", () => { $("jump-form").hidden = true; $("jump-toggle").setAttribute("aria-expanded", "false"); });
  $("jump-form").addEventListener("submit", event => {
    event.preventDefault(); const time = inputEpoch($("jump-time").value);
    if (!validTime(time)) { notice("请输入有效的录像时间"); return; }
    if (state.explicitRange && (time < state.queryFrom || time >= state.queryTo)) { notice("定位时间不在筛选区间内，请先取消筛选或调整区间"); return; }
    $("jump-form").hidden = true; $("jump-toggle").setAttribute("aria-expanded", "false"); locateTime(time, false).catch(report);
  });
  $("archive-filter").addEventListener("change", event => { state.archiveFilter = event.target.value === "persons" ? "persons" : "all"; state.listLimit = 100; renderArchiveList(); });
  $("archive-more").addEventListener("click", () => { state.listLimit += 100; renderArchiveList(); });
  $("download-current").addEventListener("click", downloadCurrent);
  $("preview-toggle").addEventListener("click", toggleLivePreview);
  $("snapshot").addEventListener("click", () => snapshot().catch(report));
  $("zoom-toggle").addEventListener("click", () => {
    if (state.zoomActive) resetZoom();
    else if (mediaTarget()) { state.zoomActive = true; state.zoom = 2; state.zoomX = 0; state.zoomY = 0; applyZoom(); }
    else notice("当前没有可放大的画面");
  });
  $("zoom-in").addEventListener("click", () => changeZoom(1)); $("zoom-out").addEventListener("click", () => changeZoom(-1));
  $("zoom-reset").addEventListener("click", resetZoom);
  $("play-pause").addEventListener("click", () => {
    if (state.playing) { video.pause(); state.playing = false; $("play-pause").textContent = "播放"; return; }
    if (state.waitingForRecording) { state.playing = true; $("play-pause").textContent = "暂停"; continuePlayback().catch(report); return; }
    if (state.activeClip && !video.error && !video.ended && video.getAttribute("src")) {
      state.playing = true; $("play-pause").textContent = "暂停";
      if (video.readyState >= 2) video.play().catch(() => { state.playing = false; $("play-pause").textContent = "播放"; notice("无法播放，请重新选择时间"); });
      return;
    }
    const dayFrom = inputEpoch(dayKey(state.cursor) + "T00:00:00"), dayTo = inputEpoch(shiftDay(dayKey(state.cursor), 1) + "T00:00:00");
    const clips = state.explicitRange ? playableClips() : playableClips().filter(clip => clip.endMs > dayFrom && clip.startMs < dayTo);
    const current = clipAt(state.cursor), clip = current || clips.find(item => item.startMs >= state.cursor) || clips[0];
    if (clip) { if (!current) notice("此时没有录像，已定位到" + dateTime(Math.max(clip.startMs, state.explicitRange ? state.queryFrom : dayFrom))); seekTo(current ? state.cursor : Math.max(clip.startMs, state.explicitRange ? state.queryFrom : dayFrom), true); }
    else { $("play-pause").disabled = true; showReplayMessage("这一天没有录像，可选择日历中有标记的日期"); }
  });
  $("mute").addEventListener("click", () => { video.muted = !video.muted; $("mute").textContent = video.muted ? "静音" : "声音开启"; $("mute").setAttribute("aria-pressed", String(video.muted)); });
  let panStartX = 0, panStartY = 0, panOffsetX = 0, panOffsetY = 0;
  $("view-area").addEventListener("pointerdown", event => {
    if (!state.zoomActive || state.zoom <= 1 || event.button !== 0 || event.target !== mediaTarget()) return;
    event.preventDefault(); state.zoomDragging = true; panStartX = event.clientX; panStartY = event.clientY; panOffsetX = state.zoomX; panOffsetY = state.zoomY;
    $("view-area").setPointerCapture(event.pointerId);
  });
  $("view-area").addEventListener("pointermove", event => {
    if (!state.zoomDragging) return; event.preventDefault(); state.zoomX = panOffsetX + event.clientX - panStartX; state.zoomY = panOffsetY + event.clientY - panStartY; applyZoom();
  });
  const endPan = event => { if (!state.zoomDragging) return; state.zoomDragging = false; if ($("view-area").hasPointerCapture(event.pointerId)) $("view-area").releasePointerCapture(event.pointerId); };
  $("view-area").addEventListener("pointerup", endPan); $("view-area").addEventListener("pointercancel", endPan);
  $("view-area").addEventListener("dblclick", event => { if (state.zoomActive) { event.preventDefault(); event.stopPropagation(); } }, true);
  document.addEventListener("keydown", event => {
    if (!state.authenticated) return;
    if (event.key === "Escape") {
      if (state.zoomActive) { resetZoom(); return; }
      if (!$("jump-form").hidden) { $("jump-form").hidden = true; $("jump-toggle").setAttribute("aria-expanded", "false"); return; }
      if (!$("search-form").hidden) { $("search-form").hidden = true; updateRangeControls(); }
    }
    if (state.mode !== "replay" || event.key !== " " || event.altKey || event.ctrlKey || event.metaKey || event.target.closest("input,select,textarea,button,[contenteditable='true']") || event.target === timeline) return;
    event.preventDefault(); $("play-pause").click();
  });
  document.addEventListener("visibilitychange", () => { if (document.hidden) {
    stopLive(); video.pause(); state.playing = false; $("play-pause").textContent = "播放";
    state.pollSequence++; state.polling = false; beginNavigation(); state.calendarSequence++; abortCalendarRequests();
    for (const controller of state.requests) if (controller.group === "status" || controller.group === "archive") controller.abort();
  } else if (state.authenticated) { refreshStatus(); if (state.mode === "live") renderLive(); } });
  window.addEventListener("pagehide", () => { clearInterval(state.pollTimer); clearInterval(state.clockTimer); clearTimeout(state.prefetchTimer); stopLive(); stopPlayback(); for (const controller of state.requests) controller.abort(); });
  new ResizeObserver(scheduleTimeline).observe($("view-area"));
  narrowLayout.addEventListener("change", event => { if (state.authenticated) setSidebar(!event.matches); });
  window.addEventListener("pageshow", event => { if (event.persisted && location.protocol === "https:") request("/api/v1/session").then(session => session.authenticated ? enterConsole(session) : showLogin()).catch(() => showLogin("请重新连接录像机")); });
  if (location.protocol === "https:") request("/api/v1/session").then(session => session.authenticated ? enterConsole(session) : showLogin()).catch(error => showLogin(error.status === 401 ? "" : "无法连接录像机，请稍后重试"));
  else { showLogin("请使用电脑端显示的 HTTPS 安全地址"); $("login-submit").disabled = true; }
})();
