module.exports = {
  apps: [
    {
      name: "legacycam-signaling",
      script: "./server.js",
      cwd: "/opt/legacycam-webrtc/signaling-server",
      instances: 1,
      exec_mode: "fork",
      env: {
        NODE_ENV: "production",
        PORT: 8080,
      },
      max_restarts: 10,
      restart_delay: 3000,
    },
  ],
};
