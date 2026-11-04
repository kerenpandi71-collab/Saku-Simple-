# TODO — Saku Gamepad Standalone

## [ ] APK gamepad mandiri bergaya referensi pengguna

**Status:** Rencana diperbarui untuk arahan terbaru; implementasi flavor baru, APK, CI, dan uji perangkat masih berjalan.

**Kriteria:** “kamu bust Dari nol jangsn di gabung sama virtual display yang lama , kamu buat aplikasi baru dengan desain kayak gini,inputt nya tiru yang sbelumnya tetep menggunakan shizuku”. Buat aplikasi gamepad Android baru yang dapat dipasang sebagai app/package sendiri; jangan buka, gabung, atau mengganti layar/launcher Virtual Display Touch lama. Ikuti referensi: dock/status bergaya kapsul di bagian atas, background navy dengan gelombang cahaya biru/ungu, layar game hitam di tengah dengan rasio 16:9, analog di kiri, empat tombol XYBA di kanan. Input multi-touch direct-touch dan pemetaan koordinat tetap menggunakan izin serta injeksi Shizuku seperti implementasi sebelumnya. Sediakan pemilih dan peluncur aplikasi, kontrol Shizuku/display, kalibrasi titik per game, drag skill untuk membidik dan pelepasan input aman. Pertahankan dua flavor lama dan unggah APK baru secara terpisah melalui CI.
