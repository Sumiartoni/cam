import http from "node:http";
import { WebSocket, WebSocketServer } from "ws";

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "0.0.0.0";
const wss = new WebSocketServer({ noServer: true });
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/healthz") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "legacycam-signaling" }));
    return;
  }

  res.writeHead(404, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: false, error: "not-found" }));
});

function send(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
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
        if (room.monitor && room.monitor !== socket) {
          send(room.monitor, { type: "error", reason: "Monitor lama digantikan oleh sesi baru." });
          room.monitor.close(1000, "monitor-replaced");
        }
        room.monitor = socket;
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
      }

      socket.meta = { token, role, deviceId: role === "camera" ? deviceId : null };
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
