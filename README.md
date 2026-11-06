# Saku Gamepad Standalone

Proyek ini difokuskan pada APK Saku Gamepad standalone (`com.keren.sakugamepad`). Kode launcher Saku Simple telah dikeluarkan dari modul aplikasi dan tidak ikut dikompilasi ke APK gamepad. Varian Virtual Display Touch tetap dipertahankan. Tersedia dua mode layar: Landscape untuk kontrol gamepad seperti sebelumnya dan Portrait workspace dengan virtual display, keyboard Cyberdeck, serta trackpad.

## Desain dan kontrol

Antarmuka standalone memiliki dock kapsul berikon dan label jelas (**Game, Izin, Layar, Mainkan, Menu**), latar navy dengan gelombang cahaya biru/ungu, preview virtual 16:10 (1152 × 720), analog virtual di kiri, dan kelompok tombol MOBA di sisi kanan. Bola Asisten bergaya kaca dapat digeser dan posisinya diingat. Layar dan UI ini dibuat pada activity mandiri—tidak membuka `GamepadActivity` atau `VirtualDisplayTouchActivity` lama.

Mode Portrait menyusun dock di atas, pratinjau virtual display, keyboard virtual Cyberdeck, lalu trackpad mouse di bawah. Keyboard mendukung input karakter, tombol navigasi/fungsi, Shift, Caps Lock, dan modifier Ctrl/Alt/Meta. Pilih **Menu → Pilih mode Landscape / Portrait** untuk berganti layout; pilihan mode disimpan.

Jalur izin Shizuku, virtual-display surface, multi-touch injection dan profil mapping memakai komponen Shizuku/input tingkat rendah yang sudah ada. Analog kiri mengirim sentuhan tahan/gerak. Di kanan tersedia **Serang** utama, **Skill 1**, **Skill 2**, **Ultimate**, **Battle Spell**, **Regen**, dan **Recall**; tahan lalu geser tombol skill atau Battle Spell untuk membidik. Pilih **Menu → Atur mapping kontrol** untuk masuk mode edit: seret indikator **1, 2, 3** tepat ke tombol skill yang sesuai di layar game, lalu seret **ATK, REC, SP, HP, MOVE** ke tombol lain. Posisi disimpan otomatis per game saat indikator dilepas. Pilih **Selesai atur mapping** untuk kembali bermain. Di luar mode edit, tap/drag pada preview diteruskan sebagai direct touch.

## Cara pakai

1. Instal dan jalankan Shizuku, lalu buka **Saku Gamepad**.
2. Ketuk **Game** untuk memilih game; Mobile Legends global dicari melalui package ID `com.mobile.legends`.
3. Ketuk **Izin** untuk menyambungkan Shizuku dan memberikan izin, lalu ketuk **Layar** untuk membuat display virtual 1152 × 720.
4. Ketuk **Mainkan** untuk membuka game pada display. Jika game menolak secondary display, ikuti status yang muncul dan gunakan game yang kompatibel.
5. Ketuk **Menu** untuk mapping per game, melepas input, jeda, dan kontrol tambahan.
6. Untuk mengubah layar virtual, buka **Menu → Resolusi & DPI display**, isi lebar/tinggi (320–2560 px) dan DPI (120–640), lalu ketuk **Terapkan**. Pilihan disimpan; bila display sedang aktif, display akan dibuat ulang dengan pengaturan baru.

## Build

GitHub Actions membangun artifact **Standalone Gamepad APK** (`standalone-gamepad-debug-apk`). Flavor `standaloneGamepad` memiliki application ID `com.keren.sakugamepad`.

## Batasan

Shizuku harus aktif dan disetujui pengguna. Tidak semua ROM/perangkat/game menerima aplikasi pada secondary display atau input sintetis. Kecepatan rendering dan frame drop bergantung pada GPU, compositor, suhu dan beban game; desain ini tidak menjamin bebas frame drop. Kontrol mengirim sentuhan virtual, bukan Steam Input dan bukan dukungan gamepad Bluetooth/USB fisik.
