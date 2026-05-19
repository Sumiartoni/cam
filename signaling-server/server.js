import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocket, WebSocketServer } from "ws";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const publicDir = path.join(__dirname, "public");

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "0.0.0.0";
const viewerAdminUsername = String(process.env.VIEWER_ADMIN_USERNAME || "admin").trim();
const viewerAdminPassword = String(process.env.VIEWER_ADMIN_PASSWORD || "change-this-password").trim();
const sessionSecret = String(process.env.VIEWER_SESSION_SECRET || crypto.randomBytes(32).toString("hex")).trim();
const sessionCookieName = "ant_vrs_session";
const viewerAuthTtlMs = 5 * 60 * 1000;
const sessionTtlMs = 12 * 60 * 60 * 1000;
const loginWindowMs = 10 * 60 * 1000;
const loginLimit = 5;

const wss = new WebSocketServer({ noServer: true });
const rooms = new Map();
const sessions = new Map();
const loginAttempts = new Map();

if (viewerAdminPassword === "change-this-password") {
  console.warn(
    "[security] VIEWER_ADMIN_PASSWORD belum diatur. Ubah password viewer di environment production.",
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
    sendJson(res, 200, {
      authenticated: Boolean(session),
      username: session?.username ?? null,
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

    if (!safeCompare(username, viewerAdminUsername) || !safeCompare(password, viewerAdminPassword)) {
      registerFailedLogin(ip);
      sendJson(res, 401, { ok: false, error: "Username atau password tidak valid." });
      return;
    }

    clearFailedLogins(ip);
    const session = createSession(viewerAdminUsername);
    setSessionCookie(req, res, session.id);
    sendJson(res, 200, { ok: true, username: session.username });
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

  if (req.method === "GET" && url.pathname === "/api/viewer-auth") {
    const session = getAuthenticatedSession(req);
    if (!session) {
      sendJson(res, 401, { ok: false, error: "Sesi login viewer tidak ditemukan." });
      return;
    }

    sendJson(res, 200, {
      ok: true,
      viewer_auth: createViewerAuthToken(session),
      expires_in_ms: viewerAuthTtlMs,
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
    "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self' ws: wss:;",
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

function createViewerAuthToken(session) {
  const payload = Buffer.from(
    JSON.stringify({
      sid: session.id,
      sub: session.username,
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
  if (!session) {
    return null;
  }

  session.lastSeenAt = Date.now();
  return session;
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
        const session = verifyViewerAuthToken(message.viewer_auth);
        if (!session) {
          send(socket, { type: "error", reason: "Login viewer tidak valid atau sudah kedaluwarsa." });
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
          viewerSessionId: session.id,
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

    if (["offer", "answer", "ice", "switch-camera"].includes(message.type)) {
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
