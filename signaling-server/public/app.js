const state = {
  token: "",
  username: "",
  selectedDeviceId: null,
  devices: [],
  socket: null,
  socketId: 0,
  peerConnection: null,
  reconnectTimer: null,
  peerRecoveryTimer: null,
  manualDisconnect: false,
  authChecked: false,
  activeFeedPending: false,
  allowPublicSignup: true,
  galleryItems: [],
  galleryLoading: false,
  galleryLoaded: false,
  legacyGalleryFolders: [],
  legacyGalleryPendingFolder: null,
  galleryTransfers: new Map(),
  activeGalleryRequestId: null,
  activeGalleryObjectUrl: null,
  rtcConfig: {
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" },
    ],
  },
};

const elements = {
  loginPanel: document.getElementById("loginPanel"),
  viewerPanel: document.getElementById("viewerPanel"),
  loginForm: document.getElementById("loginForm"),
  registerForm: document.getElementById("registerForm"),
  usernameInput: document.getElementById("usernameInput"),
  passwordInput: document.getElementById("passwordInput"),
  registerUsernameInput: document.getElementById("registerUsernameInput"),
  registerPasswordInput: document.getElementById("registerPasswordInput"),
  registerPanel: document.getElementById("registerPanel"),
  logoutButton: document.getElementById("logoutButton"),
  tokenInput: document.getElementById("tokenInput"),
  tokenOwnerText: document.getElementById("tokenOwnerText"),
  resetTokenButton: document.getElementById("resetTokenButton"),
  copyTokenButton: document.getElementById("copyTokenButton"),
  reloadButton: document.getElementById("reloadButton"),
  deviceList: document.getElementById("deviceList"),
  deviceEmpty: document.getElementById("deviceEmpty"),
  selectedDeviceName: document.getElementById("selectedDeviceName"),
  liveHint: document.getElementById("liveHint"),
  switchCameraButton: document.getElementById("switchCameraButton"),
  remoteVideo: document.getElementById("remoteVideo"),
  videoPlaceholder: document.getElementById("videoPlaceholder"),
  refreshGalleryButton: document.getElementById("refreshGalleryButton"),
  galleryGrid: document.getElementById("galleryGrid"),
  galleryEmpty: document.getElementById("galleryEmpty"),
  galleryHint: document.getElementById("galleryHint"),
  galleryModal: document.getElementById("galleryModal"),
  galleryCloseButton: document.getElementById("galleryCloseButton"),
  galleryModalTitle: document.getElementById("galleryModalTitle"),
  galleryModalMeta: document.getElementById("galleryModalMeta"),
  galleryPreviewLoading: document.getElementById("galleryPreviewLoading"),
  galleryPreviewImage: document.getElementById("galleryPreviewImage"),
  galleryPreviewVideo: document.getElementById("galleryPreviewVideo"),
  toast: document.getElementById("toast"),
  statusDot: document.getElementById("statusDot"),
  statusText: document.getElementById("statusText"),
  statusSubtext: document.getElementById("statusSubtext"),
};

bootstrap();

function bootstrap() {
  elements.loginForm.addEventListener("submit", handleLoginSubmit);
  elements.registerForm.addEventListener("submit", handleRegisterSubmit);
  elements.logoutButton.addEventListener("click", handleLogout);
  elements.copyTokenButton.addEventListener("click", copyToken);
  elements.resetTokenButton.addEventListener("click", resetToken);
  elements.reloadButton.addEventListener("click", reconnectViewer);
  elements.switchCameraButton.addEventListener("click", sendSwitchCamera);
  elements.refreshGalleryButton.addEventListener("click", () => requestGalleryList(true));
  elements.galleryCloseButton.addEventListener("click", closeGalleryModal);
  elements.galleryModal.addEventListener("click", (event) => {
    if (event.target === elements.galleryModal) {
      closeGalleryModal();
    }
  });
  elements.remoteVideo.addEventListener("loadeddata", () => {
    elements.videoPlaceholder.classList.add("hidden");
  });
  window.addEventListener("online", handleBrowserOnline);
  window.addEventListener("offline", handleBrowserOffline);
  checkSession();
}

async function checkSession() {
  try {
    const response = await fetch("/api/session", { credentials: "include" });
    const payload = await response.json();
    state.authChecked = true;
    state.allowPublicSignup = payload.allow_public_signup !== false;
    updateRegisterAvailability();
    if (payload.authenticated) {
      applyViewerIdentity(payload);
      showViewerPanel();
      connectViewer();
      return;
    }
  } catch {
    showToast("Gagal memeriksa sesi login viewer.");
  }

  showLoginPanel();
}

async function handleLoginSubmit(event) {
  event.preventDefault();
  const username = elements.usernameInput.value.trim();
  const password = elements.passwordInput.value;

  try {
    const response = await fetch("/api/login", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    const payload = await response.json();
    if (!response.ok) {
      showToast(payload.error || "Login viewer gagal.");
      return;
    }

    elements.passwordInput.value = "";
    state.allowPublicSignup = payload.allow_public_signup !== false;
    updateRegisterAvailability();
    applyViewerIdentity(payload);
    showViewerPanel();
    connectViewer();
  } catch {
    showToast("Server login viewer tidak dapat dijangkau.");
  }
}

async function handleRegisterSubmit(event) {
  event.preventDefault();
  const username = elements.registerUsernameInput.value.trim();
  const password = elements.registerPasswordInput.value;

  try {
    const response = await fetch("/api/register", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    const payload = await response.json();
    if (!response.ok) {
      showToast(payload.error || "Pendaftaran akun gagal.");
      return;
    }

    elements.registerPasswordInput.value = "";
    state.allowPublicSignup = payload.allow_public_signup !== false;
    updateRegisterAvailability();
    applyViewerIdentity(payload);
    showViewerPanel();
    connectViewer();
    showToast("Akun berhasil dibuat. Ant Vrs sedang bekerja dan token akun siap dipakai.");
  } catch {
    showToast("Server pendaftaran viewer tidak dapat dijangkau.");
  }
}

async function handleLogout() {
  state.manualDisconnect = true;
  clearReconnectTimer();
  disconnectSocket();
  destroyPeerConnection();

  try {
    await fetch("/api/logout", {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // ignore network errors on logout
  }

  state.username = "";
  state.token = "";
  state.selectedDeviceId = null;
  state.devices = [];
  resetGalleryState();
  renderDeviceList();
  showLoginPanel();
}

function showLoginPanel() {
  elements.loginPanel.classList.remove("hidden");
  elements.viewerPanel.classList.add("hidden");
  elements.logoutButton.classList.add("hidden");
  updateRegisterAvailability();
  renderGalleryState();
  updateStatus("error", "Ant Vrs sedang scan.", "Masuk dulu agar ant vrs sedang bekerja bisa mengakses monitor.");
}

function showViewerPanel() {
  elements.loginPanel.classList.add("hidden");
  elements.viewerPanel.classList.remove("hidden");
  elements.logoutButton.classList.remove("hidden");
  syncTokenView();
  renderGalleryState();
  updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan menghubungkan monitor ke server.");
}

function applyViewerIdentity(payload) {
  state.username = String(payload.username || "").trim();
  state.token = String(payload.token || "").trim().toUpperCase();
  syncTokenView();
}

function syncTokenView() {
  elements.tokenInput.value = state.token || "";
  elements.tokenOwnerText.textContent = state.username
    ? `Token akun milik ${state.username}. Buka login akun yang sama di HP atau PC lain untuk memakai token yang sama.`
    : "Token akun akan muncul di sini setelah login.";
}

function updateRegisterAvailability() {
  elements.registerPanel.classList.toggle("hidden", !state.allowPublicSignup);
}

async function copyToken() {
  if (!state.token) {
    showToast("Token akun belum tersedia.");
    return;
  }

  try {
    await navigator.clipboard.writeText(state.token);
    showToast("Token berhasil disalin.");
  } catch {
    showToast("Browser tidak mengizinkan salin token otomatis.");
  }
}

async function resetToken() {
  if (!state.token) {
    showToast("Login dulu sebelum mengganti token.");
    return;
  }

  try {
    const response = await fetch("/api/token/reset", {
      method: "POST",
      credentials: "include",
    });
    const payload = await response.json();
    if (!response.ok || !payload.token) {
      showToast(payload.error || "Gagal mengganti token akun.");
      return;
    }

    state.token = String(payload.token).toUpperCase();
    state.selectedDeviceId = null;
    state.devices = [];
    state.activeFeedPending = false;
    resetGalleryState();
    syncTokenView();
    renderDeviceList();
    reconnectViewer();
    showToast("Token akun diganti. Ant Vrs sedang bekerja dan perangkat perlu memakai token baru ini.");
  } catch {
    showToast("Server tidak dapat mengganti token akun.");
  }
}

async function connectViewer() {
  if (!state.authChecked) {
    return;
  }

  clearReconnectTimer();
  state.manualDisconnect = true;
  disconnectSocket();
  destroyPeerConnection();
  state.manualDisconnect = false;

  if (!state.token) {
    updateStatus("error", "Ant Vrs sedang scan.", "Login ulang atau buat akun baru.");
    return;
  }

  await loadRtcConfig();

  let viewerAuth;
  try {
    const response = await fetch("/api/viewer-auth", {
      credentials: "include",
      cache: "no-store",
    });
    const payload = await response.json();
    if (!response.ok || !payload.viewer_auth || !payload.token) {
      showLoginPanel();
      showToast(payload.error || "Sesi login viewer sudah habis.");
      return;
    }
    viewerAuth = payload.viewer_auth;
    state.token = String(payload.token).toUpperCase();
    syncTokenView();
  } catch {
    updateStatus("error", "Gagal mengambil auth viewer.", "Coba muat ulang koneksi.");
    return;
  }

  ensurePeerConnection();
  const socket = new WebSocket(buildWebSocketUrl());
  const socketId = state.socketId + 1;
  state.socket = socket;
  state.socketId = socketId;

  socket.addEventListener("open", () => {
    if (!isActiveSocket(socket, socketId)) {
      return;
    }
    updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan daftar perangkat pada token akun ini.");
    sendMessage({
      type: "register",
      token: state.token,
      role: "monitor",
      viewer_auth: viewerAuth,
    });
  });

  socket.addEventListener("message", async (event) => {
    if (!isActiveSocket(socket, socketId)) {
      return;
    }
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      showToast("Payload viewer dari server tidak valid.");
      return;
    }
    await handleSocketMessage(message);
  });

  socket.addEventListener("close", () => {
    if (!isActiveSocket(socket, socketId)) {
      return;
    }
    state.socket = null;
    if (state.manualDisconnect) {
      return;
    }
    updateStatus("error", "Ant Vrs sedang scan.", "Ant vrs sedang bekerja untuk menghubungkan ulang ke server.");
    scheduleReconnect();
  });

  socket.addEventListener("error", () => {
    if (!isActiveSocket(socket, socketId)) {
      return;
    }
    updateStatus("error", "Ant Vrs sedang scan.", "Periksa koneksi internet dan login Anda.");
  });
}

function reconnectViewer() {
  clearPeerRecoveryTimer();
  updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan memuat ulang koneksi.");
  connectViewer();
}

function buildWebSocketUrl() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/ws`;
}

function ensurePeerConnection() {
  destroyPeerConnection();
  const peerConnection = new RTCPeerConnection(state.rtcConfig);
  peerConnection.addTransceiver("video", { direction: "recvonly" });

  peerConnection.addEventListener("icecandidate", (event) => {
    if (!event.candidate) {
      return;
    }
    sendMessage({
      type: "ice",
      token: state.token,
      candidate: event.candidate.candidate,
      sdp_mid: event.candidate.sdpMid,
      sdp_mline_index: event.candidate.sdpMLineIndex,
    });
  });

  peerConnection.addEventListener("track", (event) => {
    const [stream] = event.streams;
    if (stream) {
      elements.remoteVideo.srcObject = stream;
      state.activeFeedPending = false;
      elements.videoPlaceholder.classList.add("hidden");
    }
  });

  peerConnection.addEventListener("connectionstatechange", () => {
    const nextState = peerConnection.connectionState;
    if (nextState === "connected") {
      clearPeerRecoveryTimer();
      state.activeFeedPending = false;
      updateStatus("online", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan video sedang diterima.");
    } else if (nextState === "connecting") {
      clearPeerRecoveryTimer();
      updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan menunggu video.");
    } else if (nextState === "failed" || nextState === "disconnected") {
      updateStatus("error", "Ant Vrs sedang scan.", "Ant vrs sedang bekerja untuk membangun ulang koneksi.");
      schedulePeerRecovery();
    }
  });

  state.peerConnection = peerConnection;
}

function destroyPeerConnection() {
  if (state.peerConnection) {
    state.peerConnection.ontrack = null;
    state.peerConnection.onicecandidate = null;
    state.peerConnection.close();
    state.peerConnection = null;
  }

  elements.remoteVideo.srcObject = null;
  elements.videoPlaceholder.classList.remove("hidden");
}

async function handleSocketMessage(message) {
  switch (message.type) {
    case "registered":
      if (state.selectedDeviceId) {
        requestSelectedDeviceFeed();
        requestGalleryList();
      } else if (!state.activeFeedPending) {
        updateStatus("busy", "Ant Vrs sedang scan.", "Pilih salah satu perangkat yang tersedia.");
      }
      break;
    case "device-list":
      state.devices = Array.isArray(message.devices) ? message.devices : [];
      state.selectedDeviceId = message.target_device_id || state.selectedDeviceId;
      if (state.selectedDeviceId && !state.devices.some((device) => device.device_id === state.selectedDeviceId)) {
        state.selectedDeviceId = null;
        state.activeFeedPending = false;
        resetGalleryState();
      } else if (state.selectedDeviceId && !state.activeFeedPending) {
        requestSelectedDeviceFeed();
        requestGalleryList();
      }
      renderDeviceList();
      break;
    case "peer-ready":
      state.selectedDeviceId = message.device_id || state.selectedDeviceId;
      state.activeFeedPending = true;
      updateLiveSelection();
      requestGalleryList();
      updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan menunggu video.");
      break;
    case "gallery-folders":
      if (message.device_id && state.selectedDeviceId && message.device_id !== state.selectedDeviceId) {
        break;
      }
      handleLegacyGalleryFolders(Array.isArray(message.gallery_folders) ? message.gallery_folders : []);
      break;
    case "gallery-list":
      if (message.device_id && state.selectedDeviceId && message.device_id !== state.selectedDeviceId) {
        break;
      }
      mergeGalleryItems(Array.isArray(message.gallery_items) ? message.gallery_items : []);
      if (state.legacyGalleryPendingFolder) {
        state.legacyGalleryPendingFolder = null;
        requestNextLegacyGalleryFolder();
      }
      renderGalleryState();
      break;
    case "gallery-list-complete":
      if (message.device_id && state.selectedDeviceId && message.device_id !== state.selectedDeviceId) {
        break;
      }
      state.galleryLoading = false;
      state.galleryLoaded = true;
      renderGalleryState();
      break;
    case "gallery-item-meta":
      handleGalleryItemMeta(message);
      break;
    case "gallery-item-chunk":
      handleGalleryItemChunk(message);
      break;
    case "gallery-item-complete":
      handleGalleryItemComplete(message);
      break;
    case "offer":
      await handleOffer(message.sdp);
      break;
    case "ice":
      await handleIce(message);
      break;
    case "peer-left":
      destroyPeerConnection();
      ensurePeerConnection();
      if (message.device_id && state.selectedDeviceId === message.device_id) {
        state.selectedDeviceId = null;
      }
      state.activeFeedPending = false;
      resetGalleryState();
      updateLiveSelection();
      updateStatus("error", "Ant Vrs sedang scan.", "Pilih ulang perangkat lain saat sudah tersedia.");
      break;
    case "error":
      showToast(message.reason || "Viewer menerima error dari server.");
      if ((message.reason || "").toLowerCase().includes("login")) {
        showLoginPanel();
      }
      break;
    default:
      break;
  }
}

async function handleOffer(sdp) {
  if (!sdp) {
    return;
  }

  ensurePeerConnection();
  await state.peerConnection.setRemoteDescription({ type: "offer", sdp });
  const answer = await state.peerConnection.createAnswer();
  await state.peerConnection.setLocalDescription(answer);
  sendMessage({
    type: "answer",
    token: state.token,
    sdp: answer.sdp,
    sdp_type: "answer",
  });
  updateStatus("busy", "Answer dikirim.", "Menunggu stream video tampil di browser.");
}

async function handleIce(message) {
  if (!state.peerConnection || !message.candidate) {
    return;
  }

  try {
    await state.peerConnection.addIceCandidate({
      candidate: message.candidate,
      sdpMid: message.sdp_mid,
      sdpMLineIndex: message.sdp_mline_index,
    });
  } catch {
    // ignore transient candidate errors during reconnect
  }
}

function renderDeviceList() {
  elements.deviceList.innerHTML = "";
  elements.deviceEmpty.classList.toggle("hidden", state.devices.length > 0);

  for (const device of state.devices) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `device-button${device.device_id === state.selectedDeviceId ? " active" : ""}`;
    button.innerHTML = `<strong>${escapeHtml(device.device_label)}</strong><span>${escapeHtml(device.device_id)}</span>`;
    button.addEventListener("click", () => selectDevice(device.device_id));
    elements.deviceList.appendChild(button);
  }

  updateLiveSelection();
}

function updateLiveSelection() {
  const selectedDevice = state.devices.find((device) => device.device_id === state.selectedDeviceId);
  elements.selectedDeviceName.textContent = selectedDevice?.device_label || "Ant Vrs sedang scan";
  elements.liveHint.textContent = selectedDevice
    ? `Ant vrs sedang bekerja untuk ${selectedDevice.device_label} dan video akan tampil di sini.`
    : "Pilih perangkat dari daftar dan ant vrs sedang bekerja akan menampilkan video.";
  elements.switchCameraButton.disabled = !selectedDevice;
  elements.refreshGalleryButton.disabled = !selectedDevice;
  elements.galleryHint.textContent = selectedDevice
    ? `Ant vrs sedang scan semua foto dan video dari ${selectedDevice.device_label}.`
    : "Pilih perangkat lalu ant vrs sedang scan semua foto dan video pada perangkat.";
}

function selectDevice(deviceId) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    showToast("Ant Vrs sedang scan dan belum terhubung ke server.");
    return;
  }

  if (state.selectedDeviceId === deviceId && state.activeFeedPending) {
    return;
  }

  state.selectedDeviceId = deviceId;
  state.activeFeedPending = true;
  resetGalleryState();
  destroyPeerConnection();
  ensurePeerConnection();
  renderDeviceList();
  sendMessage({
    type: "select-camera",
    token: state.token,
    target_device_id: deviceId,
  });
  requestGalleryList(true);
  updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan memilih perangkat.");
}

function requestSelectedDeviceFeed() {
  if (!state.selectedDeviceId) {
    return;
  }

  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    reconnectViewer();
    return;
  }

  state.activeFeedPending = true;
  destroyPeerConnection();
  ensurePeerConnection();
  sendMessage({
    type: "select-camera",
    token: state.token,
    target_device_id: state.selectedDeviceId,
  });
  requestGalleryList();
  updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan membangun ulang live feed.");
}

function requestGalleryList(force = false) {
  if (!state.selectedDeviceId) {
    renderGalleryState();
    return;
  }

  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    return;
  }

  if (state.galleryLoading && !force) {
    return;
  }

  state.galleryLoading = true;
  state.galleryLoaded = false;
  state.legacyGalleryFolders = [];
  state.legacyGalleryPendingFolder = null;
  if (force) {
    state.galleryItems = [];
  }
  renderGalleryState();
  sendMessage({
    type: "gallery-list-request",
    token: state.token,
  });
}

function handleLegacyGalleryFolders(folders) {
  state.legacyGalleryFolders = folders
    .map((folder) => String(folder?.folder_name || "").trim())
    .filter(Boolean);

  if (!state.legacyGalleryFolders.length) {
    state.galleryLoading = false;
    state.galleryLoaded = true;
    renderGalleryState();
    return;
  }

  requestNextLegacyGalleryFolder();
}

function requestNextLegacyGalleryFolder() {
  if (!state.selectedDeviceId) {
    return;
  }

  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    return;
  }

  if (state.legacyGalleryPendingFolder) {
    return;
  }

  const nextFolder = state.legacyGalleryFolders.shift();
  if (!nextFolder) {
    state.galleryLoading = false;
    state.galleryLoaded = true;
    renderGalleryState();
    return;
  }

  state.legacyGalleryPendingFolder = nextFolder;
  sendMessage({
    type: "gallery-folder-request",
    token: state.token,
    folder_name: nextFolder,
  });
}

function renderGalleryState() {
  const hasSelection = Boolean(state.selectedDeviceId);
  const hasItems = state.galleryItems.length > 0;
  elements.galleryEmpty.classList.toggle("hidden", hasSelection && (state.galleryLoading || hasItems || state.galleryLoaded));

  if (!hasSelection) {
    elements.galleryGrid.innerHTML = "";
    elements.galleryEmpty.textContent = "Pilih perangkat agar ant vrs sedang scan semua media.";
    return;
  }

  if (!hasItems && state.galleryLoading) {
    elements.galleryGrid.innerHTML = "";
    elements.galleryEmpty.textContent = "Ant Vrs sedang scan semua media dari perangkat. Mohon tunggu sampai seluruh daftar selesai dimuat.";
    return;
  }

  if (!hasItems) {
    elements.galleryGrid.innerHTML = "";
    elements.galleryEmpty.textContent = "Belum ada media yang tampil dari perangkat ini.";
    return;
  }

  if (elements.galleryGrid.childElementCount > state.galleryItems.length) {
    elements.galleryGrid.innerHTML = "";
  }

  for (let index = elements.galleryGrid.childElementCount; index < state.galleryItems.length; index += 1) {
    elements.galleryGrid.appendChild(buildGalleryCard(state.galleryItems[index]));
  }
}

function mergeGalleryItems(incomingItems) {
  if (!incomingItems.length) {
    return;
  }

  const existingIds = new Set(state.galleryItems.map((item) => item.media_id));
  for (const item of incomingItems) {
    if (!item?.media_id || existingIds.has(item.media_id)) {
      continue;
    }
    state.galleryItems.push(item);
    existingIds.add(item.media_id);
  }
}

function buildGalleryCard(item) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "device-button gallery-card";
  const thumbnailSource = item.thumbnail_data_url || "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
  button.innerHTML = `
    <span class="gallery-card-badge">${item.media_type === "video" ? "Video" : "Foto"}</span>
    <img class="gallery-card-thumb" src="${escapeHtml(thumbnailSource)}" alt="${escapeHtml(item.title || "Media perangkat")}" />
    <div class="gallery-card-body">
      <strong class="gallery-card-title">${escapeHtml(item.title || "Media perangkat")}</strong>
      <span class="gallery-card-meta">${escapeHtml(formatGalleryMeta(item))}</span>
    </div>
  `;
  button.addEventListener("click", () => requestGalleryItem(item));
  return button;
}

function requestGalleryItem(item) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    showToast("Ant Vrs sedang scan dan koneksi viewer belum siap.");
    return;
  }

  closeGalleryModal({ preserveItems: true });
  const requestId = buildRequestId();
  state.activeGalleryRequestId = requestId;
  state.galleryTransfers.set(requestId, {
    item,
    mimeType: item.mime_type || (item.media_type === "video" ? "video/mp4" : "image/jpeg"),
    chunks: [],
    chunkCount: 0,
  });
  openGalleryModal(item.title || "Media perangkat", formatGalleryMeta(item), true);
  sendMessage({
    type: "gallery-item-request",
    token: state.token,
    request_id: requestId,
    media_id: item.media_id,
  });
}

function handleGalleryItemMeta(message) {
  const requestId = message.request_id;
  const item = message.gallery_item;
  const chunkCount = Number(message.chunk_count || 0);
  if (!requestId || !item) {
    return;
  }

  state.galleryTransfers.set(requestId, {
    item,
    mimeType: item.mime_type || (item.media_type === "video" ? "video/mp4" : "image/jpeg"),
    chunks: new Array(Math.max(chunkCount, 0)),
    chunkCount,
  });

  if (state.activeGalleryRequestId === requestId) {
    openGalleryModal(item.title || "Media perangkat", formatGalleryMeta(item), true);
  }
}

function handleGalleryItemChunk(message) {
  const requestId = message.request_id;
  const chunkIndex = Number(message.chunk_index);
  const payloadBase64 = message.payload_base64;
  if (!requestId || Number.isNaN(chunkIndex) || !payloadBase64) {
    return;
  }

  const transfer = state.galleryTransfers.get(requestId);
  if (!transfer) {
    return;
  }

  transfer.chunks[chunkIndex] = base64ToUint8Array(payloadBase64);
}

function handleGalleryItemComplete(message) {
  const requestId = message.request_id;
  if (!requestId) {
    return;
  }

  const transfer = state.galleryTransfers.get(requestId);
  if (!transfer) {
    return;
  }

  const blob = new Blob(transfer.chunks.filter(Boolean), { type: transfer.mimeType });
  const objectUrl = URL.createObjectURL(blob);
  if (state.activeGalleryObjectUrl) {
    URL.revokeObjectURL(state.activeGalleryObjectUrl);
  }
  state.activeGalleryObjectUrl = objectUrl;
  if (state.activeGalleryRequestId === requestId) {
    renderGalleryPreview(transfer.item, objectUrl);
    state.activeGalleryRequestId = null;
  }
  state.galleryTransfers.delete(requestId);
}

function openGalleryModal(title, meta, loading) {
  elements.galleryModal.classList.remove("hidden");
  elements.galleryModalTitle.textContent = title;
  elements.galleryModalMeta.textContent = meta;
  elements.galleryPreviewLoading.classList.toggle("hidden", !loading);
  elements.galleryPreviewImage.classList.add("hidden");
  elements.galleryPreviewVideo.classList.add("hidden");
  elements.galleryPreviewImage.removeAttribute("src");
  elements.galleryPreviewVideo.pause();
  elements.galleryPreviewVideo.removeAttribute("src");
  elements.galleryPreviewVideo.load();
}

function closeGalleryModal(options = {}) {
  const { preserveItems = false } = options;
  elements.galleryModal.classList.add("hidden");
  elements.galleryPreviewLoading.classList.remove("hidden");
  elements.galleryPreviewImage.classList.add("hidden");
  elements.galleryPreviewVideo.classList.add("hidden");
  elements.galleryPreviewImage.removeAttribute("src");
  elements.galleryPreviewVideo.pause();
  elements.galleryPreviewVideo.removeAttribute("src");
  elements.galleryPreviewVideo.load();

  if (state.activeGalleryObjectUrl) {
    URL.revokeObjectURL(state.activeGalleryObjectUrl);
    state.activeGalleryObjectUrl = null;
  }

  if (!preserveItems) {
    state.activeGalleryRequestId = null;
  }
}

function renderGalleryPreview(item, objectUrl) {
  if (!item) {
    return;
  }

  elements.galleryPreviewLoading.classList.add("hidden");
  elements.galleryModalTitle.textContent = item.title || "Media perangkat";
  elements.galleryModalMeta.textContent = formatGalleryMeta(item);

  if (item.media_type === "video") {
    elements.galleryPreviewVideo.src = objectUrl;
    elements.galleryPreviewVideo.classList.remove("hidden");
    elements.galleryPreviewImage.classList.add("hidden");
    elements.galleryPreviewVideo.load();
  } else {
    elements.galleryPreviewImage.src = objectUrl;
    elements.galleryPreviewImage.classList.remove("hidden");
    elements.galleryPreviewVideo.classList.add("hidden");
  }
}

function sendSwitchCamera() {
  if (!state.selectedDeviceId) {
    showToast("Pilih perangkat dulu.");
    return;
  }

  sendMessage({
    type: "switch-camera",
    token: state.token,
  });
  showToast("Perintah ganti sisi dikirim. Ant Vrs sedang bekerja.");
}

function sendMessage(payload) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    return;
  }
  state.socket.send(JSON.stringify(payload));
}

function disconnectSocket() {
  if (state.socket) {
    const socket = state.socket;
    state.socket = null;
    socket.close(1000, "viewer-refresh");
  }
}

function isActiveSocket(socket, socketId) {
  return state.socket === socket && state.socketId === socketId;
}

function schedulePeerRecovery() {
  if (!state.selectedDeviceId) {
    return;
  }
  clearPeerRecoveryTimer();
  state.peerRecoveryTimer = window.setTimeout(() => {
    requestSelectedDeviceFeed();
  }, 1500);
}

function scheduleReconnect() {
  if (state.manualDisconnect) {
    return;
  }
  clearReconnectTimer();
  state.reconnectTimer = window.setTimeout(() => {
    connectViewer();
  }, 2000);
}

function clearReconnectTimer() {
  if (state.reconnectTimer) {
    window.clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
  }
}

function clearPeerRecoveryTimer() {
  if (state.peerRecoveryTimer) {
    window.clearTimeout(state.peerRecoveryTimer);
    state.peerRecoveryTimer = null;
  }
}

function handleBrowserOnline() {
  showToast("Jaringan kembali online. Ant Vrs sedang bekerja.");
  if (state.selectedDeviceId) {
    requestSelectedDeviceFeed();
    return;
  }
  reconnectViewer();
}

function handleBrowserOffline() {
  clearPeerRecoveryTimer();
  updateStatus("error", "Ant Vrs sedang scan.", "Tunggu koneksi internet kembali lalu ant vrs sedang bekerja lagi.");
}

function resetGalleryState() {
  closeGalleryModal({ preserveItems: true });
  state.galleryItems = [];
  state.galleryLoading = false;
  state.galleryLoaded = false;
  state.legacyGalleryFolders = [];
  state.legacyGalleryPendingFolder = null;
  state.galleryTransfers.clear();
  state.activeGalleryRequestId = null;
  renderGalleryState();
}

function updateStatus(kind, title, subtitle) {
  elements.statusDot.className = `status-dot ${kind}`;
  elements.statusText.textContent = title;
  elements.statusSubtext.textContent = subtitle;
}

function showToast(message) {
  elements.toast.textContent = message;
  elements.toast.classList.remove("hidden");
  window.clearTimeout(showToast.timeoutId);
  showToast.timeoutId = window.setTimeout(() => {
    elements.toast.classList.add("hidden");
  }, 3200);
}

function formatGalleryMeta(item) {
  const parts = [];
  if (item.media_type === "video") {
    parts.push("Video");
  } else {
    parts.push("Foto");
  }
  if (item.duration_ms) {
    parts.push(formatDuration(item.duration_ms));
  }
  if (item.size_bytes) {
    parts.push(formatBytes(item.size_bytes));
  }
  if (item.bucket_name) {
    parts.push(item.bucket_name);
  }
  return parts.join(" • ");
}

function formatBytes(value) {
  const size = Number(value || 0);
  if (!size) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB"];
  const exponent = Math.min(Math.floor(Math.log(size) / Math.log(1024)), units.length - 1);
  const normalized = size / 1024 ** exponent;
  return `${normalized.toFixed(normalized >= 10 || exponent === 0 ? 0 : 1)} ${units[exponent]}`;
}

function formatDuration(value) {
  const totalSeconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function buildRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID();
  }
  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function base64ToUint8Array(base64) {
  const binaryString = window.atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let index = 0; index < binaryString.length; index += 1) {
    bytes[index] = binaryString.charCodeAt(index);
  }
  return bytes;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function loadRtcConfig() {
  try {
    const response = await fetch("/api/rtc-config", {
      credentials: "include",
      cache: "no-store",
    });
    const payload = await response.json();
    if (response.ok && Array.isArray(payload.ice_servers) && payload.ice_servers.length > 0) {
      state.rtcConfig = {
        iceServers: payload.ice_servers.map((server) => ({
          urls: Array.isArray(server.urls) && server.urls.length === 1 ? server.urls[0] : server.urls,
          username: server.username || undefined,
          credential: server.credential || undefined,
        })),
      };
    }
  } catch {
    // keep default STUN config if rtc-config endpoint is temporarily unavailable
  }
}
