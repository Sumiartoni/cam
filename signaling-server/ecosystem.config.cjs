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
        HOST: "127.0.0.1",
        PORT: 8080,
        VIEWER_ADMIN_USERNAME: "admin",
        VIEWER_ADMIN_PASSWORD: "ganti-password-kuat-anda",
        VIEWER_SESSION_SECRET: "ganti-rahasia-session-random-anda",
        ALLOW_PUBLIC_SIGNUP: "true",
      },
      max_restarts: 10,
      restart_delay: 3000,
    },
  ],
};
