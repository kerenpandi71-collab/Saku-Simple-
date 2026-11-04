# Saku Gamepad Standalone

APK gamepad baru yang berdiri sendiri, dengan launcher dan package ID terpisah (`com.keren.sakugamepad`). Aplikasi lama Saku Simple dan Virtual Display Touch tetap menjadi varian terpisah.

## Desain dan kontrol

Antarmuka mengikuti referensi pengguna: dock berbentuk kapsul di bagian atas, latar navy dengan gelombang cahaya biru/ungu, preview 16:9 di tengah, analog virtual di kiri, serta tombol Y/X/B/A di kanan. Layar dan UI ini dibuat pada activity mandiri—tidak membuka `GamepadActivity` atau `VirtualDisplayTouchActivity` lama.

Jalur izin Shizuku, virtual-display surface, multi-touch injection dan profil mapping memakai komponen Shizuku/input tingkat rendah yang sudah ada. Analog kiri mengirim touch tahan/gerak. Y, X, B, dan A dikaitkan ke Skill 1, Skill 2, Skill 3, dan Serang; **tahan lalu geser** tombol skill untuk membidik. Tombol Recall tersedia di dock. Pilih **Setelan → Atur mapping kontrol**, pilih kontrol, lalu tap posisi ikon target pada preview tengah. Mapping tersimpan per game. Tap/drag lain di dalam preview diteruskan sebagai direct touch.

## Cara pakai

1. Instal dan jalankan Shizuku, lalu buka **Saku Gamepad**.
2. Pilih game melalui ikon grid; Mobile Legends global dicari melalui package ID `com.mobile.legends`.
3. Ketuk ikon Shizuku dan berikan izin, lalu ketuk ikon monitor untuk membuat display virtual 1280 × 720.
4. Ketuk ikon rumah untuk mencoba membuka game pada display. Jika game menolak secondary display, jalankan game yang kompatibel dan ikuti status yang muncul.
5. Buka Setelan untuk mapping kontrol, Recall, jeda, dan pilihan display.

## Build

GitHub Actions membangun artifact **Standalone Gamepad APK** (`standalone-gamepad-debug-apk`) selain APK Saku Simple dan Virtual Display Touch. Flavor `standaloneGamepad` memiliki application ID tersendiri, sehingga dapat diinstal berdampingan.

## Batasan

Shizuku harus aktif dan disetujui pengguna. Tidak semua ROM/perangkat/game menerima aplikasi pada secondary display atau input sintetis. Kecepatan rendering dan frame drop bergantung pada GPU, compositor, suhu dan beban game; desain ini tidak menjamin bebas frame drop. Kontrol mengirim sentuhan virtual, bukan Steam Input dan bukan dukungan gamepad Bluetooth/USB fisik.