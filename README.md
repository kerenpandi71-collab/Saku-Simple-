# Saku Gamepad

Mode gamepad virtual Android untuk proyek Saku Simple. Layar game dipreview di tengah, analog kiri dan tombol MOBA di kanan, dengan injeksi direct-touch melalui Shizuku.

## Cara pakai

1. Instal Shizuku dan jalankan servicenya. Pada Android 11 ke atas, pengguna dapat mengaktifkannya melalui Wireless debugging; pada perangkat lain ikuti metode resmi Shizuku.
2. Buka Saku Gamepad, pilih game, lalu tekan **Shizuku** dan izinkan akses saat diminta.
3. Tekan **Display** untuk membuat virtual display 1280 × 720, kemudian **Mainkan** untuk mencoba membuka game di display tersebut.
4. Jika posisi tombol tidak cocok dengan tata letak game, pilih **Atur kontrol**, pilih analog/serang/skill/recall, lalu tap titik kontrol yang sesuai pada preview game. Posisi disimpan per aplikasi.
5. Analog kiri mengirim sentuhan tahan dan gerak; tombol kanan mengirim sentuhan tekan/lepas. Tap pada area preview di luar kontrol gamepad diteruskan sebagai direct touch. **Jeda** melepas input yang sedang tertahan.

Touchpad virtual-display lama tetap tersedia melalui activity yang sama. Varian launcher Saku Simple tidak diganti.

## Catatan kompatibilitas

Fitur Shizuku memerlukan Shizuku aktif dan izin dari pengguna. Tidak semua perangkat, ROM, versi Android, atau game mengizinkan game berjalan dan menerima input pada secondary display. Jika game gagal dibuka di virtual display atau preview hitam, jalur display tersebut kemungkinan tidak didukung oleh perangkat/game itu.

Output SurfaceView langsung dipakai untuk menghindari salinan frame tambahan. Hal ini mengurangi overhead aplikasi, tetapi tidak dapat menjamin bebas frame drop; hasilnya tetap bergantung pada GPU, compositor Android, suhu perangkat, dan beban game. Kontrol yang disertakan adalah overlay sentuh virtual; integrasi Steam Input atau pembacaan gamepad Bluetooth/USB fisik belum ditambahkan.
