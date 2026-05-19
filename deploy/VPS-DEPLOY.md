# Deploy ant Vrs Viewer + Signaling di VPS

Panduan ini untuk kondisi satu VPS sudah dipakai aplikasi lain, satu IP public yang sama, dan domain dikelola lewat Cloudflare.

Contoh aktif:

- domain viewer + signaling: `cam.zienix.me`
- service Node internal: `127.0.0.1:8080`

## 1. Upload project

```bash
/opt/legacycam-webrtc
```

Yang dibutuhkan:

- `signaling-server/`
- `deploy/`

## 2. Install dependency

```bash
cd /opt/legacycam-webrtc/signaling-server
npm install --omit=dev
```

## 3. Set environment keamanan viewer

Minimal:

```bash
export VIEWER_ADMIN_USERNAME=admin
export VIEWER_ADMIN_PASSWORD='ganti-password-kuat-anda'
export VIEWER_SESSION_SECRET='isi-rahasia-random-panjang'
export ALLOW_PUBLIC_SIGNUP='true'
export RTC_STUN_URLS='stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302'
export RTC_TURN_URLS='turn:cam.zienix.me:3478?transport=udp,turn:cam.zienix.me:3478?transport=tcp'
export RTC_TURN_USERNAME='antvrs-turn'
export RTC_TURN_PASSWORD='ganti-password-turn-anda'
```

Keterangan:

- `VIEWER_ADMIN_USERNAME` dan `VIEWER_ADMIN_PASSWORD` adalah akun bootstrap pertama.
- `ALLOW_PUBLIC_SIGNUP='true'` mengizinkan user lain membuat akun viewer sendiri.
- setiap akun viewer akan mendapat 1 token sendiri yang disimpan server-side.
- data akun viewer disimpan di `signaling-server/data/viewer-users.json`
- `RTC_TURN_*` wajib diisi jika camera harus bisa dipantau dari jaringan selular atau internet yang beda NAT

Kalau pakai PM2, isi juga pada `ecosystem.config.cjs`.

## 4. Jalankan server

```bash
cd /opt/legacycam-webrtc/signaling-server
pm2 start ecosystem.config.cjs
pm2 save
pm2 startup
```

## 5. DNS Cloudflare

Tambahkan record:

- Type: `A`
- Name: `cam`
- Content: `IP public VPS`
- Proxy status: `Proxied`

## 6. Reverse proxy

Karena server sekarang melayani website viewer dan WebSocket sekaligus, root `/` juga harus diproxy ke Node.

Jika memakai Nginx:

```bash
sudo cp /opt/legacycam-webrtc/deploy/nginx/legacycam-signaling.conf /etc/nginx/sites-available/legacycam-signaling
sudo ln -s /etc/nginx/sites-available/legacycam-signaling /etc/nginx/sites-enabled/legacycam-signaling
sudo nginx -t
sudo systemctl reload nginx
```

## 7. SSL

Jika origin menangani SSL:

```bash
sudo certbot --nginx -d cam.zienix.me
```

Jika lewat Cloudflare, mode SSL minimal `Full`, lebih baik `Full (strict)`.

## 8. Health check

```bash
curl http://127.0.0.1:8080/healthz
curl https://cam.zienix.me/healthz
```

## 9. Akses akhir

- website viewer: `https://cam.zienix.me`
- signaling WebSocket: `wss://cam.zienix.me/ws`

## 9. TURN server untuk pemantauan jarak jauh

Kalau `device cam` harus bisa tampil lewat data selular atau beda jaringan, signaling saja tidak cukup. Anda perlu menjalankan `coturn`.

Install:

```bash
sudo apt update
sudo apt install -y coturn
```

Copy contoh config:

```bash
sudo cp /opt/legacycam-webrtc/deploy/coturn/turnserver.conf.example /etc/turnserver.conf
```

Edit:

```bash
sudo nano /etc/turnserver.conf
```

Ganti minimal:

- `static-auth-secret`
- `realm=cam.zienix.me`
- path `cert` dan `pkey` bila SSL origin Anda berbeda

Lalu aktifkan service:

```bash
sudo systemctl enable coturn
sudo systemctl restart coturn
sudo systemctl status coturn
```

Port yang perlu dibuka di firewall/security group:

- `3478/tcp`
- `3478/udp`
- `5349/tcp`
- rentang relay `49160-49200/tcp`
- rentang relay `49160-49200/udp`

Setelah itu pastikan env PM2 untuk signaling juga sudah diisi:

- `RTC_TURN_URLS`
- `RTC_TURN_USERNAME`
- `RTC_TURN_PASSWORD`

## 10. Update setelah push baru

```bash
cd /opt/legacycam-webrtc
git pull origin main
cd /opt/legacycam-webrtc/signaling-server
pm2 restart legacycam-signaling
pm2 save
```

## 11. Catatan migrasi ke model akun publik

- token viewer tidak lagi disimpan di browser `localStorage`
- token viewer sekarang dimiliki akun dan selalu sama saat login dari HP atau PC lain
- user lain harus membuat akun sendiri, lalu server memberi token mereka masing-masing
- jangan hapus `signaling-server/data/viewer-users.json` kalau sudah ada user aktif
