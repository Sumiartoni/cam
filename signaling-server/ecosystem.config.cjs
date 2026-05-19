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
        RTC_STUN_URLS: "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302",
        RTC_TURN_URLS: "turn:cam.zienix.me:3478?transport=udp,turn:cam.zienix.me:3478?transport=tcp",
        RTC_TURN_USERNAME: "antvrs-turn",
        RTC_TURN_PASSWORD: "ganti-password-turn-anda",
      },
      max_restarts: 10,
      restart_delay: 3000,
    },
  ],
};
