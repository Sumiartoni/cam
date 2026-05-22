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
  renderDeviceList();
  showLoginPanel();
}

function showLoginPanel() {
  elements.loginPanel.classList.remove("hidden");
  elements.viewerPanel.classList.add("hidden");
  elements.logoutButton.classList.add("hidden");
  updateRegisterAvailability();
  updateStatus("error", "Ant Vrs sedang scan.", "Masuk dulu agar ant vrs sedang bekerja bisa mengakses monitor.");
}

function showViewerPanel() {
  elements.loginPanel.classList.add("hidden");
  elements.viewerPanel.classList.remove("hidden");
  elements.logoutButton.classList.remove("hidden");
  syncTokenView();
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
      } else if (state.selectedDeviceId && !state.activeFeedPending) {
        requestSelectedDeviceFeed();
      }
      renderDeviceList();
      break;
    case "peer-ready":
      state.selectedDeviceId = message.device_id || state.selectedDeviceId;
      state.activeFeedPending = true;
      updateLiveSelection();
      updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan menunggu video.");
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
  destroyPeerConnection();
  ensurePeerConnection();
  renderDeviceList();
  sendMessage({
    type: "select-camera",
    token: state.token,
    target_device_id: deviceId,
  });
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
  updateStatus("busy", "Ant Vrs sedang bekerja.", "Ant vrs sedang scan dan membangun ulang live feed.");
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
