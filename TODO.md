# TODO — Saku Gamepad Virtual

## [ ] Layar tengah dan tata letak kontrol MOBA

**Status:** UI dan profil awal telah ditambahkan; hasil build dan uji perangkat masih menunggu.

**Kriteria:** Pengguna meminta “buatkan aplikaso virtual steam game pad”; “yng tampilan layar nya di tengah”; “Kiri Analog kanan Tombol tombol untuk game ml”. Aplikasi menyediakan mode gamepad virtual Android dengan game di tampilan tengah, analog di sisi kiri, dan tombol aksi Mobile Legends/MOBA di sisi kanan. Mode ini memiliki pemilih game dan mencoba membuka game pada virtual display aktif jika Android, ROM, dan game mengizinkan secondary display. Pengguna dapat mengkalibrasi posisi analog/serang/skill/recall dan menyimpan posisi per game. Tombol jeda melepas semua input yang sedang tertahan. Launcher Saku Simple dan alur virtual display/touchpad yang sudah ada tetap dipertahankan.

## [ ] Shizuku, direct touch, dan overhead input

**Status:** Jalur multi-pointer dan injeksi Shizuku telah ditambahkan; verifikasi build dan uji pada perangkat Android masih menunggu.

**Kriteria:** Pengguna meminta input “pake shizuku” dan “support direct touch”; analog kiri mengirim sentuhan tahan/gerak, tombol kanan mengirim sentuhan tekan/lepas, dan beberapa aksi dapat aktif bersamaan dengan pointer ID berbeda. Tap di area preview di luar kontrol gamepad diteruskan sebagai direct touch. Peluncuran game memakai virtual display Saku yang aktif dan memvalidasi package/activity. Output SurfaceView langsung dipakai tanpa salinan frame yang tidak perlu untuk menekan overhead. Tidak boleh menjanjikan bebas frame drop karena performa bergantung pada perangkat, ROM, compositor, GPU, suhu, dan beban game. Status/galat izin Shizuku, virtual display, peluncuran game, dan injeksi input ditampilkan dengan jelas.

## [ ] Integrasi repo dan referensi

**Status:** Implementasi berada pada checkout branch yang disetujui; commit/push dan hasil CI masih menunggu.

**Kriteria:** Gunakan repo `kerenpandi71-collab/Saku-Simple-`, branch `feature/virtual-display-touch-control`, seperti diminta pengguna. Gunakan Dextop oleh NarYuki sebagai acuan konsep virtual display/mirror tanpa menyalin kode GPL. Jangan menghapus fitur atau alur yang telah ada pada branch tersebut.
