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
    rooms.set(token, { monitor: null, camera: null, updatedAt: Date.now() });
  }
  return rooms.get(token);
}

function cleanupRoom(token) {
  const room = rooms.get(token);
  if (!room) {
    return;
  }

  if (!room.monitor && !room.camera) {
    rooms.delete(token);
  } else {
    room.updatedAt = Date.now();
  }
}

function notifyPairReady(room) {
  if (room.monitor && room.camera) {
    send(room.monitor, { type: "peer-ready" });
    send(room.camera, { type: "peer-ready" });
  }
}

function forwardToPeer(source, payload) {
  const { token, role } = source.meta ?? {};
  if (!token || !role) {
    send(source, { type: "error", reason: "Perangkat belum register." });
    return;
  }

  const room = rooms.get(token);
  if (!room) {
    send(source, { type: "error", reason: "Room token tidak ditemukan." });
    return;
  }

  const target = role === "monitor" ? room.camera : room.monitor;
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

      if (!token || !["monitor", "camera"].includes(role)) {
        send(socket, { type: "error", reason: "Token atau role tidak valid." });
        return;
      }

      const room = getRoom(token);

      if (role === "monitor") {
        if (room.monitor && room.monitor !== socket) {
          send(socket, { type: "error", reason: "Token ini sudah dipakai monitor lain." });
          return;
        }
        room.monitor = socket;
      }

      if (role === "camera") {
        if (!room.monitor) {
          send(socket, { type: "error", reason: "Monitor untuk token ini belum aktif." });
          return;
        }
        if (room.camera && room.camera !== socket) {
          send(socket, { type: "error", reason: "Token ini sudah dipakai satu kamera lain." });
          return;
        }
        room.camera = socket;
      }

      socket.meta = { token, role };
      room.updatedAt = Date.now();

      send(socket, { type: "registered", token, role });
      notifyPairReady(room);
      return;
    }

    if (["offer", "answer", "ice"].includes(message.type)) {
      forwardToPeer(socket, message);
      return;
    }

    send(socket, { type: "error", reason: `Tipe pesan tidak dikenali: ${message.type}` });
  });

  socket.on("close", () => {
    const { token, role } = socket.meta ?? {};
    if (!token || !role) {
      return;
    }

    const room = rooms.get(token);
    if (!room) {
      return;
    }

    if (role === "monitor" && room.monitor === socket) {
      room.monitor = null;
      if (room.camera) {
        send(room.camera, { type: "peer-left" });
        room.camera = null;
      }
    }

    if (role === "camera" && room.camera === socket) {
      room.camera = null;
      if (room.monitor) {
        send(room.monitor, { type: "peer-left" });
      }
    }

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
      room.camera?.close(1000, "room-timeout");
      rooms.delete(token);
    }
  }
}, 60 * 1000);

server.listen(port, host, () => {
  console.log(`LegacyCam signaling server berjalan di ws://${host}:${port}/ws`);
});
