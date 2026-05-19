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
```

Keterangan:

- `VIEWER_ADMIN_USERNAME` dan `VIEWER_ADMIN_PASSWORD` adalah akun bootstrap pertama.
- `ALLOW_PUBLIC_SIGNUP='true'` mengizinkan user lain membuat akun viewer sendiri.
- setiap akun viewer akan mendapat 1 token sendiri yang disimpan server-side.
- data akun viewer disimpan di `signaling-server/data/viewer-users.json`

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
