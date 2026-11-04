# Rencana: Saku Gamepad Virtual

## Tujuan produk

Tambahkan mode gamepad virtual Android ke repo `kerenpandi71-collab/Saku-Simple-`, branch `feature/virtual-display-touch-control`. Mode ini menampilkan game pada virtual display di tengah layar, analog virtual di sisi kiri, dan tombol aksi MOBA di sisi kanan. Pengiriman input memakai Shizuku dan jalur direct-touch yang telah ada. Referensi Dextop digunakan untuk konsep virtual display/mirror, tanpa menyalin kode GPL-nya.

## Implementasi

- Pertahankan aplikasi Android native Kotlin, Compose dan Activity yang sudah digunakan repo; jangan mengganti proyek dengan Expo atau menghapus mode launcher/touchpad yang ada.
- Tambahkan satu `GamepadActivity` ke varian `virtualDisplayTouch`: gunakan landscape immersive, `SurfaceView` langsung sebagai output display, lalu gambar kontrol transparan di atas preview layar yang berada di tengah.
- Tambahkan pemilih game yang mengambil aplikasi launchable, serta jalur meluncurkan game ke ID virtual display melalui Shizuku. Tampilkan status yang jelas bila perangkat/ROM/game menolak secondary display.
- Buat kontrol MOBA awal: analog kiri dengan pointer sentuh yang ditahan dan diperbarui saat digeser; tombol kanan untuk serang, skill 1–3, dan recall. Gunakan pointer ID berbeda agar analog dan beberapa tombol dapat aktif bersamaan. Tombol dipetakan ke titik sentuh virtual-display yang dapat dikalibrasi dan disimpan per game, bukan mengasumsikan tata letak Mobile Legends sama di semua perangkat.
- Gunakan `injectTouchPoints` yang sudah ada dan pemrosesan input serial ringan di thread khusus. Pertahankan output SurfaceView langsung dan hindari salinan frame bitmap/ImageReader. Sertakan tombol edit/tata-letak dan tombol jeda untuk melepas semua sentuhan aktif dengan aman.
- Tambahkan pemanggilan AIDL/bridge seperlunya untuk meluncurkan paket yang dipilih pada display. Validasi nama paket/komponen terhadap daftar aplikasi terpasang sebelum membentuk perintah; jangan menjalankan perintah bebas dari teks UI.
- Pertahankan tema gelap biru/kaca dan gaya kontrol aplikasi Saku yang telah ada. Susunan layar: pratinjau game di tengah, analog kiri, tombol MOBA kanan, bar status Shizuku/display dan kontrol edit/jeda yang ringkas.

## Batasan dan struktur

Shizuku harus aktif dan diizinkan pengguna. Dukungan injeksi dan menjalankan game pada secondary display dapat berbeda menurut versi Android, ROM, serta kebijakan game. Jalur native diarahkan untuk latensi rendah, tetapi aplikasi tidak dapat menjamin nol frame drop; virtual display, compositor, GPU, suhu, dan beban game tetap berpengaruh.

Struktur utama yang akan ditambah/diubah:

- `app/src/main/java/com/keren/sakusimple/GamepadActivity.kt`: layar virtual display dan overlay controller.
- `app/src/main/java/com/keren/sakusimple/GamepadInputController.kt`: state multi-pointer, analog, tombol, dan pelepasan input.
- `app/src/main/java/com/keren/sakusimple/GamepadProfile.kt`: titik koordinat yang dapat dikalibrasi dan disimpan per paket game.
- `app/src/main/java/com/keren/sakusimple/ShizukuBridge.kt`, `ShizukuUserService.kt`, dan `IVirtualDisplayService.aidl`: jalur launch pada display jika dibutuhkan oleh API yang tersedia.
- `app/src/virtualDisplayTouch/AndroidManifest.xml`: pendaftaran activity gamepad dan entry point mode baru tanpa menghilangkan fungsi yang ada.

Validasi akan memakai build varian Gradle yang sudah didefinisikan CI (`assembleVirtualDisplayTouchDebug` dan `assembleSakuSimpleDebug`) bila Android SDK tersedia. Bila build native tidak tersedia di lingkungan ini, hasil itu akan dilaporkan apa adanya; fungsi Shizuku/secondary-display tetap memerlukan uji pada perangkat Android nyata.
