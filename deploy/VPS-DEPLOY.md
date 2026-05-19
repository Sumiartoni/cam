# Deploy LegacyCam Signaling di VPS Existing

Dokumen ini untuk kasus Anda: satu VPS sudah dipakai app lain, satu IP public yang sama, domain dikelola Cloudflare. Contoh aktif di sini memakai `cam.zienix.me`.

## Pola yang dipakai

- App lama tetap jalan di domain/subdomain lama.
- LegacyCam signaling pakai subdomain `cam.zienix.me`.
- Nginx di VPS membedakan traffic berdasarkan `server_name`.
- Node signaling server listen di `127.0.0.1:8080`.

## 1. Upload project ke VPS

Contoh target:

```bash
/opt/legacycam-webrtc
```

Yang dibutuhkan untuk online hanya folder:

- `signaling-server/`
- `deploy/`

## 2. Install dependency server

```bash
cd /opt/legacycam-webrtc/signaling-server
npm install --omit=dev
```

## 3. Jalankan server dengan PM2

Edit dulu `ecosystem.config.cjs` bila `cwd` berbeda dari `/opt/legacycam-webrtc/signaling-server`.

```bash
cd /opt/legacycam-webrtc/signaling-server
pm2 start ecosystem.config.cjs
pm2 save
pm2 startup
```

## 4. Cloudflare DNS

Tambahkan record:

- Type: `A`
- Name: `cam`
- Content: `IP public VPS Anda`
- Proxy status: `Proxied` atau `DNS only`

Untuk `wss://` lewat browser/app, mode `Proxied` Cloudflare biasanya paling praktis.

## 5. Nginx reverse proxy

Salin config contoh:

```bash
sudo cp /opt/legacycam-webrtc/deploy/nginx/legacycam-signaling.conf /etc/nginx/sites-available/legacycam-signaling
```

Aktifkan site:

```bash
sudo ln -s /etc/nginx/sites-available/legacycam-signaling /etc/nginx/sites-enabled/legacycam-signaling
sudo nginx -t
sudo systemctl reload nginx
```

## 6. SSL

Jika SSL masih ditangani origin:

```bash
sudo certbot --nginx -d cam.zienix.me
```

Jika SSL full diproksikan Cloudflare, pastikan mode SSL Cloudflare `Full` atau `Full (strict)`.

## 7. Health check

Setelah online, cek:

```bash
curl http://127.0.0.1:8080/healthz
curl https://cam.zienix.me/healthz
```

Harus mengembalikan JSON `ok: true`.

## 8. URL yang dipakai di aplikasi

Build `release`:

```text
wss://cam.zienix.me/ws
```

Build `debug` lokal/LAN:

```text
ws://IP-LAN:8081/ws
```

## 9. Kalau app lama sudah pakai Nginx

Tidak masalah. Tambahkan satu `server` block baru saja untuk subdomain `cam.zienix.me`.

Yang penting:

- jangan pakai `server_name` yang sama dengan app lama
- jangan rebut port internal app lama
- signaling Node tetap di port lokal lain, misalnya `8080`
