# ant Vrs CCTV

Prototype CCTV berbasis WebRTC untuk mengubah HP lama menjadi kamera dan memantaunya dari browser yang aman.

## Fokus aktif proyek

- `camera-app/`: aplikasi Android untuk HP camera.
- `signaling-server/`: WebSocket signaling server sekaligus web viewer responsif.
- `core/`: logic shared untuk signaling dan WebRTC.
- `viewer-app/`: viewer Android lama. Masih ada di repo, tetapi viewer utama sekarang dipindah ke website karena lebih stabil.

## Alur sistem

1. Viewer membuka website monitor lalu login atau daftar akun.
2. Server memberi setiap akun viewer tepat 1 token pairing.
3. HP camera membuka `camera-app`, lalu memasukkan token akun itu sekali saja.
4. Website viewer dan HP camera terhubung ke signaling server pada `/ws`.
5. Server hanya mengizinkan role `monitor` jika viewer sudah login, punya `viewer_auth` jangka pendek, dan token yang dipakai memang milik akun itu.
6. WebRTC offer, answer, dan ICE diteruskan lewat signaling server.
7. Video berjalan peer-to-peer dari HP camera ke browser viewer.

## Struktur proyek

- `camera-app/`
- `core/`
- `signaling-server/`
- `viewer-app/` legacy
- `legacycam-camera-release/`
- `legacycam-viewer-release/`
- `scripts/build-release-apks.ps1`

## Menjalankan signaling server dan web viewer

```bash
cd signaling-server
npm install
```

Set env bootstrap viewer:

```bash
export VIEWER_ADMIN_USERNAME=admin
export VIEWER_ADMIN_PASSWORD='ganti-password-kuat'
export VIEWER_SESSION_SECRET='random-rahasia-panjang'
export ALLOW_PUBLIC_SIGNUP='true'
```

Jalankan server:

```bash
npm start
```

Server ini menyediakan:

- `GET /` web viewer
- `GET /healthz` health check
- `WS /ws` signaling WebRTC
- `POST /api/login` login viewer
- `POST /api/register` daftar viewer baru
- `POST /api/logout` logout viewer
- `GET /api/session` cek sesi login
- `GET /api/viewer-auth` auth token pendek untuk role monitor
- `POST /api/token/reset` ganti token milik akun yang sedang login

## Build Android camera app

```powershell
.\gradlew.bat :camera-app:assembleDebug
```

Release:

```powershell
.\gradlew.bat :camera-app:assembleRelease
```

APK camera hasil export lokal:

```text
legacycam-camera-release\camera-app-release.apk
```

## Viewer website

Website viewer langsung disajikan dari `signaling-server/public/`.

Fitur utama:

- login viewer
- daftar akun viewer publik
- 1 akun = 1 token server-side
- daftar device camera
- live feed video
- tombol pindah kamera depan/belakang
- responsif untuk HP dan PC

## URL produksi

Jika server online di domain:

```text
https://cam.zienix.me
```

Maka:

- web viewer dibuka di `https://cam.zienix.me`
- signaling WebSocket ada di `wss://cam.zienix.me/ws`

## Penggunaan

### Di browser viewer

1. Buka `https://cam.zienix.me`
2. Login dengan akun viewer
3. Lihat token milik akun
4. Bagikan token ke HP camera
5. Klik device dari daftar saat camera sudah online
6. Video live tampil di browser

### Di HP camera

1. Install `camera-app`
2. Buka aplikasi
3. Masukkan token dari viewer website
4. Izinkan akses camera
5. Foreground service akan menjaga camera tetap aktif

## Deploy VPS

Panduan ringkas ada di:

- [deploy/VPS-DEPLOY.md](/D:/legacycam-webrtc/deploy/VPS-DEPLOY.md)

Poin penting:

- reverse proxy harus mengarah ke seluruh root `/`, bukan hanya `/ws`
- `cam.zienix.me` sekarang melayani website viewer sekaligus signaling
- `VIEWER_ADMIN_PASSWORD` wajib diganti
- `VIEWER_SESSION_SECRET` wajib diisi nilai acak yang panjang
- akun viewer disimpan di `signaling-server/data/viewer-users.json`
- file data akun tidak boleh ikut Git

## Catatan keamanan

- login viewer memakai session cookie `HttpOnly` dan `SameSite=Strict`
- role `monitor` di WebSocket memerlukan `viewer_auth` jangka pendek dari server
- token viewer sekarang dimiliki akun, bukan disimpan lokal di browser
- password bootstrap default contoh tidak aman untuk produksi
- token pairing camera tetap bersifat secret room

## Langkah lanjutan yang layak

1. Tambahkan TURN server untuk NAT sulit.
2. Tambahkan expiry token monitor.
3. Tambahkan daftar perangkat camera tepercaya.
4. Tambahkan notifikasi online/offline.
5. Tambahkan audit log login viewer.
