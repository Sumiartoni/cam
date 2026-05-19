# LegacyCam CCTV

Prototype Android native untuk mengubah HP lama menjadi kamera CCTV berbasis WebRTC. Sekarang proyek ini dibagi menjadi dua aplikasi Android terpisah dengan UI yang berbeda:

- `viewer-app`: aplikasi monitor untuk membuat token pairing dan menampilkan live feed.
- `camera-app`: aplikasi kamera untuk HP lama, memakai token dari viewer, lalu mengirim stream video lewat foreground service.

## Arsitektur singkat

1. HP monitor membuka mode `Monitor`.
2. Aplikasi membuat token pairing acak, misalnya `K7M2QW`.
3. HP kamera membuka mode `Camera`, lalu memasukkan token tersebut.
4. Keduanya terhubung ke signaling server melalui WebSocket.
5. Signaling server hanya mengizinkan kamera bergabung ke token yang sudah dibuat oleh monitor.
6. Setelah pairing, SDP offer/answer dan ICE candidate diteruskan lewat signaling server.
7. Stream video berjalan peer-to-peer lewat WebRTC.

## Struktur proyek

- `core/`: shared logic untuk token, signaling, dan WebRTC.
- `viewer-app/`: aplikasi viewer dengan UI control-room.
- `camera-app/`: aplikasi camera dengan UI device-console.
- `signaling-server/`: server WebSocket tipis untuk routing pairing token dan pesan WebRTC.

## Cara menjalankan signaling server

```bash
cd signaling-server
npm install
npm start
```

Server default berjalan di port `8080`. Jika port itu bentrok, jalankan dengan port lain, misalnya:

```powershell
$env:PORT='8081'
node server.js
```

Jika dua HP berada di jaringan Wi-Fi yang sama, isi URL signaling di aplikasi dengan IP laptop/PC yang menjalankan server, misalnya:

```text
ws://192.168.1.20:8081/ws
```

`/ws` dipakai sebagai path konseptual. Server contoh ini menerima koneksi di port yang sama walau path tidak diproses secara ketat.

## Cara membuka aplikasi Android

1. Buka folder `legacycam-webrtc` di Android Studio.
2. Pastikan Android SDK untuk `compileSdk 35` tersedia.
3. Sinkronkan Gradle.
4. Jika ingin build dua aplikasi lewat terminal Windows, gunakan:

```powershell
.\gradlew.bat :viewer-app:assembleDebug :camera-app:assembleDebug
```

5. APK hasil build:

```text
viewer-app\build\outputs\apk\debug\viewer-app-debug.apk
camera-app\build\outputs\apk\debug\camera-app-debug.apk
```

6. Install `viewer-app-debug.apk` di HP monitor dan `camera-app-debug.apk` di HP kamera.

## Catatan distribusi dan Play Protect

- Untuk test lokal via APK sideload, Play Protect masih bisa menampilkan prompt scan untuk aplikasi yang belum dikenal. Itu normal untuk APK dari luar Play Store.
- Berdasarkan panduan resmi Google, pemblokiran otomatis untuk sideload terutama menyasar aplikasi yang meminta permission sensitif berisiko tinggi seperti `READ_SMS`, `RECEIVE_SMS`, `NOTIFICATION_LISTENER`, atau `ACCESSIBILITY`. Proyek ini tidak memakai permission tersebut.
- Build aktif sekarang dibedakan seperti ini:
  - `debug`: mengizinkan `ws://` agar testing LAN tetap jalan.
  - `release`: mematikan cleartext traffic, jadi sebaiknya pakai `wss://`.
- Untuk meminimalkan warning saat distribusi nyata, gunakan release build yang ditandatangani dan sebarkan lewat Google Play Internal Testing atau Closed Testing, bukan APK mentah dari chat/browser.

## Alur pemakaian

### Di HP monitor

1. Buka aplikasi `LegacyCam Viewer`.
2. Isi URL signaling server.
3. Tekan `Generate Token Viewer` bila ingin rotate token.
4. Tekan `Aktifkan Viewer`.
5. Bagikan token ke HP kamera.

### Di HP kamera

1. Buka aplikasi `LegacyCam Camera`.
2. Isi URL signaling server yang sama.
3. Masukkan token dari viewer.
4. Izinkan akses kamera.
5. Tekan `Nyalakan Cam`.

## Catatan penting

- Prototype ini saat ini memakai `STUN` publik Google. Untuk akses internet lintas NAT secara stabil, tambahkan `TURN server`.
- `android:usesCleartextTraffic="true"` diaktifkan agar `ws://` lokal mudah dipakai saat development. Untuk produksi, pindahkan ke `wss://`.
- Token pairing saat ini bersifat room secret. Jika ingin lebih aman, langkah berikutnya adalah menambah:
  - expiry token,
  - daftar perangkat terpercaya,
  - autentikasi admin sebelum monitor boleh membuat token,
  - push notification saat kamera online/offline.
- Implementasi ini fokus pada `video only`. Audio sengaja tidak diaktifkan agar permission dan kompleksitas awal tetap rendah.
- Mode kamera sekarang dijalankan lewat foreground service dengan notification persisten dan `PARTIAL_WAKE_LOCK` supaya peluang tetap hidup saat layar mati lebih tinggi.
- Di beberapa vendor Android yang agresif, Anda tetap perlu mematikan battery optimization untuk aplikasi ini di HP kamera.
- Folder `app/` lama tidak lagi dipakai dalam build aktif. Build sekarang memakai `core`, `viewer-app`, dan `camera-app`.

## Langkah lanjutan yang masuk akal

1. Tambahkan expiry token dan rotasi token otomatis.
2. Tambahkan `TURN` dan TLS.
3. Tambahkan penyimpanan token perangkat tepercaya di backend.
4. Tambahkan motion detection dan notifikasi.
5. Tambahkan auto-reconnect saat koneksi Wi-Fi berpindah atau drop.
