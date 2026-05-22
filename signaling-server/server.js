import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocket, WebSocketServer } from "ws";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const publicDir = path.join(__dirname, "public");
const dataDir = path.join(__dirname, "data");
const usersFilePath = path.join(dataDir, "viewer-users.json");

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "0.0.0.0";
const bootstrapUsername = String(process.env.VIEWER_ADMIN_USERNAME || "admin").trim();
const bootstrapPassword = String(process.env.VIEWER_ADMIN_PASSWORD || "change-this-password").trim();
const sessionSecret = String(process.env.VIEWER_SESSION_SECRET || crypto.randomBytes(32).toString("hex")).trim();
const allowPublicSignup = String(process.env.ALLOW_PUBLIC_SIGNUP || "true").trim().toLowerCase() !== "false";
const rtcIceServers = buildRtcIceServers(process.env);
const sessionCookieName = "ant_vrs_session";
const viewerAuthTtlMs = 5 * 60 * 1000;
const sessionTtlMs = 12 * 60 * 60 * 1000;
const loginWindowMs = 10 * 60 * 1000;
const loginLimit = 5;
const usernamePattern = /^[a-zA-Z0-9._-]{4,32}$/;
const passwordMinLength = 8;

const wss = new WebSocketServer({ noServer: true });
const rooms = new Map();
const sessions = new Map();
const loginAttempts = new Map();
const users = loadUsers();

bootstrapPrimaryViewerAccount();

if (bootstrapPassword === "change-this-password") {
  console.warn(
    "[security] VIEWER_ADMIN_PASSWORD belum diatur. Ubah password viewer bootstrap di environment production.",
  );
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  setCommonHeaders(res, isSecureRequest(req));

  if (req.method === "GET" && url.pathname === "/healthz") {
    sendJson(res, 200, { ok: true, service: "legacycam-signaling" });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/session") {
    const session = getAuthenticatedSession(req);
    const user = session ? getUserByUsername(session.username) : null;
    sendJson(res, 200, {
      authenticated: Boolean(session && user),
      username: user?.username ?? null,
      token: user?.token ?? null,
      allow_public_signup: allowPublicSignup,
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/rtc-config") {
    sendJson(res, 200, {
      ok: true,
      ice_servers: rtcIceServers,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/register") {
    if (!allowPublicSignup) {
      sendJson(res, 403, { ok: false, error: "Pendaftaran akun baru sedang ditutup." });
      return;
    }

    const ip = getRequestIp(req);
    if (isRateLimited(ip)) {
      sendJson(res, 429, {
        ok: false,
        error: "Terlalu banyak percobaan. Coba lagi beberapa menit lagi.",
      });
      return;
    }

    const body = await readJsonBody(req);
    const username = String(body?.username || "").trim();
    const password = String(body?.password || "");

    const validationError = validateCredentials(username, password);
    if (validationError) {
      registerFailedLogin(ip);
      sendJson(res, 400, { ok: false, error: validationError });
      return;
    }

    const normalizedUsername = username.toLowerCase();
    if (users.has(normalizedUsername)) {
      registerFailedLogin(ip);
      sendJson(res, 409, { ok: false, error: "Username sudah dipakai. Pilih username lain." });
      return;
    }

    const token = generateUniqueToken();
    const user = {
      username,
      password_hash: hashPassword(password),
      token,
      created_at: Date.now(),
      updated_at: Date.now(),
    };
    users.set(normalizedUsername, user);
    saveUsers();
    clearFailedLogins(ip);

    const session = createSession(user.username);
    setSessionCookie(req, res, session.id);
    sendJson(res, 201, {
      ok: true,
      username: user.username,
      token: user.token,
      allow_public_signup: allowPublicSignup,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/login") {
    const ip = getRequestIp(req);
    if (isRateLimited(ip)) {
      sendJson(res, 429, {
        ok: false,
        error: "Terlalu banyak percobaan login. Coba lagi beberapa menit lagi.",
      });
      return;
    }

    const body = await readJsonBody(req);
    const username = String(body?.username || "").trim();
    const password = String(body?.password || "");
    const user = getUserByUsername(username);

    if (!user || !verifyPassword(password, user.password_hash)) {
      registerFailedLogin(ip);
      sendJson(res, 401, { ok: false, error: "Username atau password tidak valid." });
      return;
    }

    clearFailedLogins(ip);
    const session = createSession(user.username);
    setSessionCookie(req, res, session.id);
    sendJson(res, 200, {
      ok: true,
      username: user.username,
      token: user.token,
      allow_public_signup: allowPublicSignup,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/logout") {
    const session = getAuthenticatedSession(req);
    if (session) {
      sessions.delete(session.id);
    }
    clearSessionCookie(req, res);
    sendJson(res, 200, { ok: true });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/token/reset") {
    const session = getAuthenticatedSession(req);
    const user = session ? getUserByUsername(session.username) : null;
    if (!user) {
      sendJson(res, 401, { ok: false, error: "Sesi login viewer tidak ditemukan." });
      return;
    }

    const previousToken = user.token;
    user.token = generateUniqueToken();
    user.updated_at = Date.now();
    saveUsers();
    closeRoomForToken(previousToken);
    sendJson(res, 200, {
      ok: true,
      token: user.token,
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/viewer-auth") {
    const session = getAuthenticatedSession(req);
    const user = session ? getUserByUsername(session.username) : null;
    if (!session || !user) {
      sendJson(res, 401, { ok: false, error: "Sesi login viewer tidak ditemukan." });
      return;
    }

    sendJson(res, 200, {
      ok: true,
      viewer_auth: createViewerAuthToken(session, user),
      expires_in_ms: viewerAuthTtlMs,
      token: user.token,
    });
    return;
  }

  if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
    serveFile(res, path.join(publicDir, "index.html"));
    return;
  }

  if (req.method === "GET" && url.pathname.startsWith("/assets/")) {
    const assetPath = path.join(publicDir, url.pathname.replace(/^\/assets\//, ""));
    if (!assetPath.startsWith(publicDir)) {
      sendJson(res, 403, { ok: false, error: "forbidden" });
      return;
    }
    serveFile(res, assetPath);
    return;
  }

  sendJson(res, 404, { ok: false, error: "not-found" });
});

function send(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function sendJson(res, statusCode, payload) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  res.end(JSON.stringify(payload));
}

function setCommonHeaders(res, secure) {
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "same-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.setHeader(
    "Content-Security-Policy",
    "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self' ws: wss:;",
  );
  if (secure) {
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
}

function serveFile(res, filePath) {
  if (!fs.existsSync(filePath)) {
    sendJson(res, 404, { ok: false, error: "file-not-found" });
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentTypes = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
  };

  res.writeHead(200, {
    "Content-Type": contentTypes[ext] || "application/octet-stream",
    "Cache-Control": ext === ".html" ? "no-store" : "public, max-age=300",
  });
  fs.createReadStream(filePath).pipe(res);
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }

      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        resolve({});
      }
    });
    req.on("error", () => resolve({}));
  });
}

function isSecureRequest(req) {
  return req.headers["x-forwarded-proto"] === "https";
}

function safeCompare(left, right) {
  const leftBuffer = Buffer.from(String(left));
  const rightBuffer = Buffer.from(String(right));
  if (leftBuffer.length !== rightBuffer.length) {
    return false;
  }
  return crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function parseCookies(req) {
  const rawCookie = req.headers.cookie || "";
  return rawCookie.split(";").reduce((accumulator, pair) => {
    const [rawKey, ...rawValue] = pair.trim().split("=");
    if (!rawKey) {
      return accumulator;
    }
    accumulator[rawKey] = decodeURIComponent(rawValue.join("="));
    return accumulator;
  }, {});
}

function serializeCookie(name, value, options = {}) {
  const parts = [`${name}=${encodeURIComponent(value)}`];
  if (options.httpOnly) parts.push("HttpOnly");
  if (options.secure) parts.push("Secure");
  if (options.sameSite) parts.push(`SameSite=${options.sameSite}`);
  if (options.path) parts.push(`Path=${options.path}`);
  if (typeof options.maxAge === "number") parts.push(`Max-Age=${options.maxAge}`);
  return parts.join("; ");
}

function setSessionCookie(req, res, sessionId) {
  res.setHeader(
    "Set-Cookie",
    serializeCookie(sessionCookieName, sessionId, {
      httpOnly: true,
      secure: isSecureRequest(req),
      sameSite: "Strict",
      path: "/",
      maxAge: sessionTtlMs / 1000,
    }),
  );
}

function clearSessionCookie(req, res) {
  res.setHeader(
    "Set-Cookie",
    serializeCookie(sessionCookieName, "", {
      httpOnly: true,
      secure: isSecureRequest(req),
      sameSite: "Strict",
      path: "/",
      maxAge: 0,
    }),
  );
}

function createSession(username) {
  const session = {
    id: crypto.randomUUID(),
    username,
    createdAt: Date.now(),
    lastSeenAt: Date.now(),
  };
  sessions.set(session.id, session);
  return session;
}

function getAuthenticatedSession(req) {
  const cookies = parseCookies(req);
  const sessionId = cookies[sessionCookieName];
  if (!sessionId) {
    return null;
  }

  const session = sessions.get(sessionId);
  if (!session) {
    return null;
  }

  if (Date.now() - session.lastSeenAt > sessionTtlMs) {
    sessions.delete(sessionId);
    return null;
  }

  session.lastSeenAt = Date.now();
  return session;
}

function createViewerAuthToken(session, user) {
  const payload = Buffer.from(
    JSON.stringify({
      sid: session.id,
      sub: user.username,
      token: user.token,
      exp: Date.now() + viewerAuthTtlMs,
      purpose: "viewer-ws",
    }),
  ).toString("base64url");
  const signature = crypto.createHmac("sha256", sessionSecret).update(payload).digest("base64url");
  return `${payload}.${signature}`;
}

function verifyViewerAuthToken(viewerAuth) {
  const [payload, signature] = String(viewerAuth || "").split(".");
  if (!payload || !signature) {
    return null;
  }

  const expected = crypto.createHmac("sha256", sessionSecret).update(payload).digest("base64url");
  if (!safeCompare(signature, expected)) {
    return null;
  }

  let decoded;
  try {
    decoded = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    return null;
  }

  if (decoded?.purpose !== "viewer-ws" || typeof decoded.exp !== "number" || decoded.exp < Date.now()) {
    return null;
  }

  const session = sessions.get(decoded.sid);
  if (!session || session.username !== decoded.sub) {
    return null;
  }

  const user = getUserByUsername(decoded.sub);
  if (!user || user.token !== decoded.token) {
    return null;
  }

  session.lastSeenAt = Date.now();
  return { session, user };
}

function getRequestIp(req) {
  const forwarded = String(req.headers["x-forwarded-for"] || "")
    .split(",")
    .map((item) => item.trim())
    .find(Boolean);
  return forwarded || req.socket.remoteAddress || "unknown";
}

function isRateLimited(ip) {
  const record = loginAttempts.get(ip);
  if (!record) {
    return false;
  }

  if (Date.now() - record.firstAttemptAt > loginWindowMs) {
    loginAttempts.delete(ip);
    return false;
  }

  return record.count >= loginLimit;
}

function registerFailedLogin(ip) {
  const now = Date.now();
  const current = loginAttempts.get(ip);
  if (!current || now - current.firstAttemptAt > loginWindowMs) {
    loginAttempts.set(ip, { count: 1, firstAttemptAt: now });
    return;
  }

  current.count += 1;
}

function clearFailedLogins(ip) {
  loginAttempts.delete(ip);
}

function buildRtcIceServers(environment) {
  const stunUrls = splitCsv(environment.RTC_STUN_URLS || "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302");
  const turnUrls = splitCsv(environment.RTC_TURN_URLS || "");
  const turnUsername = String(environment.RTC_TURN_USERNAME || "").trim();
  const turnPassword = String(environment.RTC_TURN_PASSWORD || "").trim();

  const servers = [];

  if (stunUrls.length > 0) {
    servers.push({ urls: stunUrls });
  }

  if (turnUrls.length > 0) {
    servers.push({
      urls: turnUrls,
      username: turnUsername,
      credential: turnPassword,
    });
  }

  return servers.length > 0
    ? servers
    : [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }];
}

function splitCsv(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function ensureUserStore() {
  fs.mkdirSync(dataDir, { recursive: true });
  if (!fs.existsSync(usersFilePath)) {
    fs.writeFileSync(usersFilePath, JSON.stringify({ users: [] }, null, 2));
  }
}

function loadUsers() {
  ensureUserStore();
  try {
    const raw = fs.readFileSync(usersFilePath, "utf8");
    const parsed = JSON.parse(raw);
    const map = new Map();
    for (const user of Array.isArray(parsed?.users) ? parsed.users : []) {
      if (!user?.username || !user?.password_hash || !user?.token) {
        continue;
      }
      map.set(String(user.username).toLowerCase(), {
        username: String(user.username),
        password_hash: String(user.password_hash),
        token: String(user.token).toUpperCase(),
        created_at: Number(user.created_at || Date.now()),
        updated_at: Number(user.updated_at || Date.now()),
      });
    }
    return map;
  } catch {
    return new Map();
  }
}

function saveUsers() {
  ensureUserStore();
  const serializedUsers = Array.from(users.values())
    .sort((left, right) => left.username.localeCompare(right.username))
    .map((user) => ({
      username: user.username,
      password_hash: user.password_hash,
      token: user.token,
      created_at: user.created_at,
      updated_at: user.updated_at,
    }));

  const temporaryPath = `${usersFilePath}.tmp`;
  fs.writeFileSync(temporaryPath, JSON.stringify({ users: serializedUsers }, null, 2));
  fs.renameSync(temporaryPath, usersFilePath);
}

function getUserByUsername(username) {
  return users.get(String(username || "").trim().toLowerCase()) ?? null;
}

function validateCredentials(username, password) {
  if (!usernamePattern.test(username)) {
    return "Username harus 4 sampai 32 karakter dan hanya boleh huruf, angka, titik, garis bawah, atau strip.";
  }
  if (String(password || "").length < passwordMinLength) {
    return "Password minimal 8 karakter.";
  }
  return "";
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString("hex");
  const hash = crypto.scryptSync(password, salt, 64).toString("hex");
  return `scrypt$${salt}$${hash}`;
}

function verifyPassword(password, passwordHash) {
  const [algorithm, salt, expectedHash] = String(passwordHash || "").split("$");
  if (algorithm !== "scrypt" || !salt || !expectedHash) {
    return false;
  }

  const actualHash = crypto.scryptSync(password, salt, 64).toString("hex");
  return safeCompare(actualHash, expectedHash);
}

function generateToken(length = 8) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let token = "";
  for (let index = 0; index < length; index += 1) {
    const randomIndex = crypto.randomInt(0, alphabet.length);
    token += alphabet[randomIndex];
  }
  return token;
}

function generateUniqueToken() {
  let token = generateToken();
  const usedTokens = new Set(Array.from(users.values(), (user) => user.token));
  while (usedTokens.has(token)) {
    token = generateToken();
  }
  return token;
}

function bootstrapPrimaryViewerAccount() {
  const existing = getUserByUsername(bootstrapUsername);
  if (existing) {
    existing.password_hash = hashPassword(bootstrapPassword);
    existing.updated_at = Date.now();
    if (!existing.token) {
      existing.token = generateUniqueToken();
    }
    saveUsers();
    return;
  }

  const user = {
    username: bootstrapUsername,
    password_hash: hashPassword(bootstrapPassword),
    token: generateUniqueToken(),
    created_at: Date.now(),
    updated_at: Date.now(),
  };
  users.set(user.username.toLowerCase(), user);
  saveUsers();
}

function closeRoomForToken(token) {
  const room = rooms.get(token);
  if (!room) {
    return;
  }

  room.monitor?.close(1000, "token-reset");
  for (const camera of room.cameras.values()) {
    camera.socket.close(1000, "token-reset");
  }
  rooms.delete(token);
}

function getRoom(token) {
  if (!rooms.has(token)) {
    rooms.set(token, { monitor: null, cameras: new Map(), selectedCameraId: null, updatedAt: Date.now() });
  }
  return rooms.get(token);
}

function cleanupRoom(token) {
  const room = rooms.get(token);
  if (!room) {
    return;
  }

  if (!room.monitor && room.cameras.size === 0) {
    rooms.delete(token);
  } else {
    room.updatedAt = Date.now();
  }
}

function normalizeDeviceLabel(deviceLabel, deviceId) {
  const safeId = String(deviceId || "").replace(/[^A-Z0-9]/gi, "").toUpperCase();
  if (deviceLabel) {
    return String(deviceLabel).trim();
  }
  return `Device ${safeId.slice(-4) || "CAM"}`;
}

function serializeDevices(room) {
  return Array.from(room.cameras.entries())
    .map(([deviceId, camera]) => ({
      device_id: deviceId,
      device_label: camera.label,
    }))
    .sort((left, right) => left.device_label.localeCompare(right.device_label));
}

function sendDeviceList(room) {
  if (!room.monitor) return;

  send(room.monitor, {
    type: "device-list",
    devices: serializeDevices(room),
    target_device_id: room.selectedCameraId,
  });
}

function notifyPairReady(room) {
  if (!room.monitor || !room.selectedCameraId) {
    return;
  }

  const camera = room.cameras.get(room.selectedCameraId);
  if (!camera) {
    room.selectedCameraId = null;
    sendDeviceList(room);
    return;
  }

  send(room.monitor, {
    type: "peer-ready",
    device_id: room.selectedCameraId,
    device_label: camera.label,
  });
  send(camera.socket, {
    type: "peer-ready",
    device_id: room.selectedCameraId,
    device_label: camera.label,
  });
}

function forwardToPeer(source, payload) {
  const { token, role, deviceId } = source.meta ?? {};
  if (!token || !role) {
    send(source, { type: "error", reason: "Perangkat belum register." });
    return;
  }

  const room = rooms.get(token);
  if (!room) {
    send(source, { type: "error", reason: "Room token tidak ditemukan." });
    return;
  }

  const target =
    role === "monitor"
      ? room.selectedCameraId
        ? room.cameras.get(room.selectedCameraId)?.socket
        : null
      : room.selectedCameraId === deviceId
        ? room.monitor
        : null;

  if (!target) {
    send(source, { type: "error", reason: "Peer belum tersedia." });
    return;
  }

  send(target, payload);
}

wss.on("connection", (socket) => {
  socket.on("message", (buffer) => {
    let message;
    try {
      message = JSON.parse(buffer.toString());
    } catch {
      send(socket, { type: "error", reason: "Payload JSON tidak valid." });
      return;
    }

    if (message.type === "register") {
      const token = String(message.token || "").trim().toUpperCase();
      const role = String(message.role || "").trim().toLowerCase();
      const rawDeviceId = String(message.device_id || "").trim();
      const deviceId = rawDeviceId.toUpperCase();
      const deviceLabel = normalizeDeviceLabel(message.device_label, deviceId);

      if (!token || !["monitor", "camera"].includes(role)) {
        send(socket, { type: "error", reason: "Token atau role tidak valid." });
        return;
      }

      const room = getRoom(token);

      if (role === "monitor") {
        const verified = verifyViewerAuthToken(message.viewer_auth);
        if (!verified) {
          send(socket, { type: "error", reason: "Login viewer tidak valid atau sudah kedaluwarsa." });
          return;
        }

        if (verified.user.token !== token) {
          send(socket, { type: "error", reason: "Token ini bukan milik akun viewer yang sedang login." });
          return;
        }

        if (room.monitor && room.monitor !== socket) {
          send(room.monitor, { type: "error", reason: "Monitor lama digantikan oleh sesi baru." });
          room.monitor.close(1000, "monitor-replaced");
        }

        room.monitor = socket;
        socket.meta = {
          token,
          role,
          deviceId: null,
          viewerSessionId: verified.session.id,
          username: verified.user.username,
        };
      }

      if (role === "camera") {
        if (!deviceId) {
          send(socket, { type: "error", reason: "Device ID camera tidak valid." });
          return;
        }

        const previousCamera = room.cameras.get(deviceId);
        if (previousCamera && previousCamera.socket !== socket) {
          previousCamera.socket.close(1000, "camera-replaced");
        }

        room.cameras.set(deviceId, { socket, label: deviceLabel });
        socket.meta = { token, role, deviceId };
      }

      room.updatedAt = Date.now();

      send(socket, {
        type: "registered",
        token,
        role,
        device_id: role === "camera" ? deviceId : null,
      });
      sendDeviceList(room);
      if (role === "monitor" && room.selectedCameraId) {
        notifyPairReady(room);
      }
      return;
    }

    if (message.type === "select-camera") {
      const { token, role } = socket.meta ?? {};
      const targetDeviceId = String(message.target_device_id || "").trim().toUpperCase();
      if (!token || role !== "monitor") {
        send(socket, { type: "error", reason: "Hanya viewer yang boleh memilih device." });
        return;
      }

      const room = rooms.get(token);
      if (!room) {
        send(socket, { type: "error", reason: "Room token tidak ditemukan." });
        return;
      }

      if (!targetDeviceId || !room.cameras.has(targetDeviceId)) {
        send(socket, { type: "error", reason: "Device camera yang dipilih tidak tersedia." });
        return;
      }

      if (room.selectedCameraId && room.selectedCameraId !== targetDeviceId) {
        const previousCamera = room.cameras.get(room.selectedCameraId);
        if (previousCamera) {
          send(previousCamera.socket, {
            type: "peer-left",
            device_id: room.selectedCameraId,
          });
        }
      }

      room.selectedCameraId = targetDeviceId;
      room.updatedAt = Date.now();
      sendDeviceList(room);
      notifyPairReady(room);
      return;
    }

    if (
      [
        "offer",
        "answer",
        "ice",
        "switch-camera",
        "gallery-list-request",
        "gallery-list",
        "gallery-list-complete",
        "gallery-item-request",
        "gallery-item-meta",
        "gallery-item-chunk",
        "gallery-item-complete",
      ].includes(message.type)
    ) {
      forwardToPeer(socket, message);
      return;
    }

    send(socket, { type: "error", reason: `Tipe pesan tidak dikenali: ${message.type}` });
  });

  socket.on("close", () => {
    const { token, role, deviceId } = socket.meta ?? {};
    if (!token || !role) {
      return;
    }

    const room = rooms.get(token);
    if (!room) {
      return;
    }

    if (role === "monitor" && room.monitor === socket) {
      room.monitor = null;
      if (room.selectedCameraId) {
        const selectedCamera = room.cameras.get(room.selectedCameraId);
        if (selectedCamera) {
          send(selectedCamera.socket, { type: "peer-left", device_id: room.selectedCameraId });
        }
      }
      room.selectedCameraId = null;
    }

    if (role === "camera" && deviceId) {
      const activeCamera = room.cameras.get(deviceId);
      if (activeCamera?.socket === socket) {
        room.cameras.delete(deviceId);
        if (room.selectedCameraId === deviceId) {
          room.selectedCameraId = null;
          if (room.monitor) {
            send(room.monitor, { type: "peer-left", device_id: deviceId });
          }
        }
      }
    }

    sendDeviceList(room);
    cleanupRoom(token);
  });
});

server.on("upgrade", (request, socket, head) => {
  if (request.url !== "/ws") {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }

  wss.handleUpgrade(request, socket, head, (webSocket) => {
    wss.emit("connection", webSocket, request);
  });
});

setInterval(() => {
  const now = Date.now();

  for (const [sessionId, session] of sessions.entries()) {
    if (now - session.lastSeenAt > sessionTtlMs) {
      sessions.delete(sessionId);
    }
  }

  for (const [ip, record] of loginAttempts.entries()) {
    if (now - record.firstAttemptAt > loginWindowMs) {
      loginAttempts.delete(ip);
    }
  }

  for (const [token, room] of rooms.entries()) {
    if (now - room.updatedAt > 30 * 60 * 1000) {
      room.monitor?.close(1000, "room-timeout");
      for (const camera of room.cameras.values()) {
        camera.socket.close(1000, "room-timeout");
      }
      rooms.delete(token);
    }
  }
}, 60 * 1000);

server.listen(port, host, () => {
  console.log(`LegacyCam signaling server berjalan di ws://${host}:${port}/ws`);
});
