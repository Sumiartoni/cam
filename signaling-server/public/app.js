const state = {
  token: loadStoredToken(),
  selectedDeviceId: null,
  devices: [],
  socket: null,
  socketId: 0,
  peerConnection: null,
  reconnectTimer: null,
  manualDisconnect: false,
  authChecked: false,
  activeFeedPending: false,
};

const elements = {
  loginPanel: document.getElementById("loginPanel"),
  viewerPanel: document.getElementById("viewerPanel"),
  loginForm: document.getElementById("loginForm"),
  usernameInput: document.getElementById("usernameInput"),
  passwordInput: document.getElementById("passwordInput"),
  logoutButton: document.getElementById("logoutButton"),
  tokenInput: document.getElementById("tokenInput"),
  saveTokenButton: document.getElementById("saveTokenButton"),
  generateTokenButton: document.getElementById("generateTokenButton"),
  copyTokenButton: document.getElementById("copyTokenButton"),
  reloadButton: document.getElementById("reloadButton"),
  deviceList: document.getElementById("deviceList"),
  deviceEmpty: document.getElementById("deviceEmpty"),
  selectedDeviceName: document.getElementById("selectedDeviceName"),
  liveHint: document.getElementById("liveHint"),
  switchCameraButton: document.getElementById("switchCameraButton"),
  remoteVideo: document.getElementById("remoteVideo"),
  videoPlaceholder: document.getElementById("videoPlaceholder"),
  toast: document.getElementById("toast"),
  statusDot: document.getElementById("statusDot"),
  statusText: document.getElementById("statusText"),
  statusSubtext: document.getElementById("statusSubtext"),
};

const rtcConfig = {
  iceServers: [
    { urls: "stun:stun.l.google.com:19302" },
    { urls: "stun:stun1.l.google.com:19302" },
  ],
};

bootstrap();

function bootstrap() {
  elements.tokenInput.value = state.token;
  elements.loginForm.addEventListener("submit", handleLoginSubmit);
  elements.logoutButton.addEventListener("click", handleLogout);
  elements.saveTokenButton.addEventListener("click", saveTokenFromInput);
  elements.generateTokenButton.addEventListener("click", regenerateToken);
  elements.copyTokenButton.addEventListener("click", copyToken);
  elements.reloadButton.addEventListener("click", reconnectViewer);
  elements.switchCameraButton.addEventListener("click", sendSwitchCamera);
  elements.remoteVideo.addEventListener("loadeddata", () => {
    elements.videoPlaceholder.classList.add("hidden");
  });
  checkSession();
}

async function checkSession() {
  try {
    const response = await fetch("/api/session", { credentials: "include" });
    const payload = await response.json();
    state.authChecked = true;
    if (payload.authenticated) {
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
    showViewerPanel();
    connectViewer();
  } catch {
    showToast("Server login viewer tidak dapat dijangkau.");
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

  showLoginPanel();
}

function showLoginPanel() {
  elements.loginPanel.classList.remove("hidden");
  elements.viewerPanel.classList.add("hidden");
  elements.logoutButton.classList.add("hidden");
  updateStatus("error", "Viewer terkunci.", "Masuk dulu untuk mengakses daftar device dan live feed.");
}

function showViewerPanel() {
  elements.loginPanel.classList.add("hidden");
  elements.viewerPanel.classList.remove("hidden");
  elements.logoutButton.classList.remove("hidden");
  updateStatus("busy", "Viewer siap.", "Menghubungkan ke signaling server.");
}

function loadStoredToken() {
  const saved = localStorage.getItem("ant_vrs_viewer_token");
  if (saved && /^[A-Z0-9]{6,12}$/.test(saved)) {
    return saved;
  }
  const generated = generateToken();
  localStorage.setItem("ant_vrs_viewer_token", generated);
  return generated;
}

function saveTokenFromInput() {
  const token = sanitizeToken(elements.tokenInput.value);
  if (!token) {
    showToast("Token monitor harus 6 sampai 12 karakter huruf atau angka.");
    return;
  }
  state.token = token;
  localStorage.setItem("ant_vrs_viewer_token", token);
  elements.tokenInput.value = token;
  state.selectedDeviceId = null;
  state.devices = [];
  renderDeviceList();
  reconnectViewer();
  showToast("Token viewer disimpan.");
}

function regenerateToken() {
  state.token = generateToken();
  localStorage.setItem("ant_vrs_viewer_token", state.token);
  elements.tokenInput.value = state.token;
  state.selectedDeviceId = null;
  state.devices = [];
  renderDeviceList();
  reconnectViewer();
  showToast("Token baru dibuat.");
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(state.token);
    showToast("Token berhasil disalin.");
  } catch {
    showToast("Browser tidak mengizinkan salin token otomatis.");
  }
}

function sanitizeToken(rawValue) {
  const token = String(rawValue || "")
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "");
  if (token.length < 6 || token.length > 12) {
    return "";
  }
  return token;
}

function generateToken() {
  return Math.random().toString(36).slice(2, 8).toUpperCase();
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

  const token = sanitizeToken(elements.tokenInput.value || state.token);
  if (!token) {
    updateStatus("error", "Token tidak valid.", "Masukkan token monitor yang benar.");
    return;
  }

  state.token = token;
  localStorage.setItem("ant_vrs_viewer_token", token);
  elements.tokenInput.value = token;

  let viewerAuth;
  try {
    const response = await fetch("/api/viewer-auth", {
      credentials: "include",
      cache: "no-store",
    });
    const payload = await response.json();
    if (!response.ok || !payload.viewer_auth) {
      showLoginPanel();
      showToast(payload.error || "Sesi login viewer sudah habis.");
      return;
    }
    viewerAuth = payload.viewer_auth;
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
    updateStatus("busy", "Viewer terhubung.", "Mengambil daftar device pada token ini.");
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
    updateStatus("error", "Viewer terputus.", "Mencoba menghubungkan ulang ke server.");
    scheduleReconnect();
  });

  socket.addEventListener("error", () => {
    if (!isActiveSocket(socket, socketId)) {
      return;
    }
    updateStatus("error", "Viewer gagal terhubung.", "Periksa koneksi internet dan login Anda.");
  });
}

function reconnectViewer() {
  updateStatus("busy", "Viewer memuat ulang koneksi.", "Menyambungkan ulang WebSocket dan WebRTC.");
  connectViewer();
}

function buildWebSocketUrl() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/ws`;
}

function ensurePeerConnection() {
  destroyPeerConnection();
  const peerConnection = new RTCPeerConnection(rtcConfig);
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
      state.activeFeedPending = false;
      updateStatus("online", "Live feed aktif.", "Frame video sedang diterima dari device camera.");
    } else if (nextState === "connecting") {
      updateStatus("busy", "Viewer membangun live feed.", "Menunggu video dari device camera.");
    } else if (nextState === "failed" || nextState === "disconnected") {
      updateStatus("error", "Live feed terputus.", "Koneksi WebRTC perlu dibangun ulang.");
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
      if (!state.selectedDeviceId && !state.activeFeedPending) {
        updateStatus("busy", "Viewer standby.", "Pilih salah satu device camera yang tersedia.");
      }
      break;
    case "device-list":
      state.devices = Array.isArray(message.devices) ? message.devices : [];
      state.selectedDeviceId = message.target_device_id || state.selectedDeviceId;
      if (state.selectedDeviceId && !state.devices.some((device) => device.device_id === state.selectedDeviceId)) {
        state.selectedDeviceId = null;
        state.activeFeedPending = false;
      }
      renderDeviceList();
      break;
    case "peer-ready":
      state.selectedDeviceId = message.device_id || state.selectedDeviceId;
      state.activeFeedPending = true;
      updateLiveSelection();
      updateStatus("busy", "Device siap.", "Viewer menunggu offer video dari camera.");
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
      updateLiveSelection();
      updateStatus("error", "Device keluar.", "Pilih ulang device lain saat sudah tersedia.");
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
  elements.selectedDeviceName.textContent = selectedDevice?.device_label || "Belum ada device dipilih";
  elements.liveHint.textContent = selectedDevice
    ? `Live feed untuk ${selectedDevice.device_label} akan muncul di sini.`
    : "Pilih device camera dari daftar untuk mulai melihat video.";
  elements.switchCameraButton.disabled = !selectedDevice;
}

function selectDevice(deviceId) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    showToast("Viewer belum terhubung ke server.");
    return;
  }

  if (state.selectedDeviceId === deviceId && state.activeFeedPending) {
    return;
  }

  state.selectedDeviceId = deviceId;
  state.activeFeedPending = true;
  destroyPeerConnection();
  ensurePeerConnection();
  renderDeviceList();
  sendMessage({
    type: "select-camera",
    token: state.token,
    target_device_id: deviceId,
  });
  updateStatus("busy", "Meminta live feed.", "Viewer sedang memilih device camera.");
}

function sendSwitchCamera() {
  if (!state.selectedDeviceId) {
    showToast("Pilih device camera dulu.");
    return;
  }

  sendMessage({
    type: "switch-camera",
    token: state.token,
  });
  showToast("Perintah pindah kamera dikirim ke device camera.");
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

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
