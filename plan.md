# Rencana: Saku Gamepad Standalone

## Tujuan produk

Tambahkan aplikasi Android gamepad virtual baru pada repo `kerenpandi71-collab/Saku-Simple-`, branch `feature/virtual-display-touch-control`. APK harus dapat dipasang berdampingan sebagai aplikasi/package tersendiri, dengan layar bergaya referensi pengguna: dock kaca gelap berikon di atas, wallpaper navy/biru/ungu bercahaya, preview layar game 16:9 di tengah, analog kiri dan tombol XYBA di sisi kanan. Jangan menggabungkan UI atau entry point ini dengan Virtual Display Touch/gamepad lama.

## Keputusan implementasi

- Pertahankan dua flavor lama (`sakuSimple`, `virtualDisplayTouch`) tanpa mengganti launcher atau manifestnya. Tambahkan flavor `standaloneGamepad` dengan application ID mandiri `com.keren.sakugamepad`, label `Saku Gamepad`, manifest dan activity launcher sendiri.
- Activity baru berada pada `app/src/standaloneGamepad/java/.../StandaloneGamepadActivity.kt`; manifest dan resource launcher berada pada `app/src/standaloneGamepad/`. Activity mengatur preview SurfaceView 16:9 di tengah, toolbar kapsul ikonik, wallpaper abstrak bercahaya, dan kontrol analog/tombol yang diletakkan di sisi layar seperti referensi. Status, pemilih game, menu setelan, koneksi Shizuku, pembuatan/penutupan display, peluncuran aplikasi, pemetaan tombol dan jeda tersedia dari UI baru.
- Jangan start atau menautkan `GamepadActivity` maupun `VirtualDisplayTouchActivity`. Implementasikan pengelolaan display pada activity baru. Gunakan hanya API Shizuku/`ShizukuUserService` yang ada untuk izin, virtual-display surface, peluncuran game dan injeksi privileged; gunakan mesin `GamepadInputController` serta profil koordinat untuk meniru jalur input multi-touch sebelumnya.
- Analog kiri menahan dan menggerakkan pointer; A/X/Y/B mengirim sentuhan tekan/lepas ke titik profil; gesture tahan-geser pada skill mengirim ACTION_MOVE untuk membidik. Direct touch pada preview diteruskan sebagai sentuh biasa. Kalibrasi memilih kontrol lalu merekam tap pada posisi target preview. Profile disimpan terpisah dalam data aplikasi baru.
- Workflow GitHub Actions membangun serta mengunggah artifact `standalone-gamepad-debug-apk` selain kedua artifact lama.

## Batasan

Jalur touch Shizuku perlu izin eksplisit pengguna. Tidak semua ROM/perangkat/game menerima peluncuran pada secondary display. UI baru berdiri sendiri, tetapi tidak menjanjikan integrasi Steam Input atau dukungan tombol gamepad USB/Bluetooth, dan tidak menjanjikan bebas frame drop.

## Struktur utama

- `app/build.gradle.kts`: flavor dan package ID APK mandiri.
- `app/src/standaloneGamepad/AndroidManifest.xml` dan `res/values/strings.xml`: launcher/name mandiri.
- `app/src/standaloneGamepad/java/com/keren/sakusimple/StandaloneGamepadActivity.kt`: seluruh UI baru, wallpaper, preview dan kontrol.
- `GamepadInputController.kt`, `GamepadProfile.kt`, `ShizukuBridge.kt`, `ShizukuUserService.kt`, AIDL: mesin input/akses Shizuku tingkat rendah yang dipakai ulang, bukan layar lama.
- `.github/workflows/android.yml`: build dan upload artifact APK standalone.

## Validasi dan serah terima

Periksa XML, diff, build APK flavor mandiri di GitHub Actions, dan status branch. Uji nyata tampilan/injeksi memerlukan perangkat Android dengan Shizuku aktif.