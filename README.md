# drac-Xterm

**drac-Xterm** adalah emulator terminal Android berbasis native untuk perangkat **ARM64 (`arm64-v8a`)**. Terinspirasi oleh konsep [Termux](https://github.com/termux/termux-app)—terminal Android yang dapat menyediakan lingkungan Linux—proyek ini membangun implementasinya sendiri dengan mesin ANSI/VT, PTY, BusyBox, PRoot, dan antarmuka Kotlin.

## Unduh aplikasi

[**Unduh drac-Xterm**](https://drive.google.com/drive/folders/10OZYrbXrFp_Z3BVrx2iVqSBmZzwOyRet?usp=drive_link)

Jadikan perangkat Android Anda ruang kerja Linux yang siap dibawa ke mana saja. Unduh APK drac-Xterm untuk menjalankan terminal yang cepat, mengelola proyek, menyunting kode, dan bekerja dengan tool Linux langsung dari ponsel—tanpa akses root.

## Unduh RootFS Linux ARM64

[**RootFS rekomendasi drac-Xterm (Google Drive)**](https://drive.google.com/file/d/1nBEfmLoSrO_vGfpazp8FAyOvEWTx3qng/view?usp=drive_link)

Ini adalah pilihan utama untuk pengalaman yang paling mulus: RootFS Kali Linux ARM64 yang disiapkan agar drac-Xterm dapat langsung menghadirkan shell Linux lengkap, toolchain, dan fondasi produktivitas kelas desktop di Android. Pilih ini bila Anda ingin memulai cepat dengan konfigurasi yang direkomendasikan proyek.

**Alternatif resmi:** [Kali NetHunter RootFS Nano ARM64](https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz) dari Kali Linux. Sumber resmi ini cocok untuk perangkat `arm64-v8a` dan format `.tar.xz` yang didukung proyek; gunakan ketika Anda menginginkan rilis yang mengikuti distribusi Kali terbaru.

Untuk membundel RootFS saat membuat APK, simpan arsip sebagai `kali-nethunter-rootfs-nano-arm64.tar.xz` di `app/src/main/assets/rootfs/`. Unduh hanya dari sumber tepercaya dan verifikasi checksum SHA-256 sebelum digunakan.

### Tutorial build lewat terminal

Android Studio **tidak diperlukan**. Anda hanya perlu **JDK 17** dan [Android SDK Command-line Tools](https://developer.android.com/tools/sdkmanager). Lakukan persiapan ini sekali saja:

```bash
export ANDROID_HOME="/path/ke/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1" && sdkmanager --licenses
```

Ganti `/path/ke/Android/Sdk` dengan lokasi SDK Anda. Setelah itu, build cukup dengan tiga langkah:

1. (Opsional) Salin RootFS ARM64 ke `app/src/main/assets/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz`.
2. Dari direktori utama proyek, jalankan:

   ```bash
   ./gradlew assembleDebug
   ```

3. Ambil APK dari `app/build/outputs/apk/debug/app-debug.apk`, lalu instal pada perangkat Android ARM64.

Build pertama akan mengunduh Gradle dan dependensi proyek secara otomatis. Jika RootFS tidak dibundel, aplikasi tetap berjalan dengan shell BusyBox.

## Setelah APK dibangun dan dijalankan

- Pada peluncuran pertama, aplikasi menyiapkan root filesystem Linux ARM64 yang dibundel dan menjalankannya melalui PRoot tanpa memerlukan akses root perangkat. Jika rootfs tidak tersedia, terminal tetap dapat berjalan menggunakan shell BusyBox.
- Pengguna mendapat terminal interaktif dengan dukungan ANSI/VT dan UTF-8: warna, cursor, scrollback, layar alternatif, karakter Unicode lebar, seleksi, pencarian buffer, clipboard, mouse tracking, serta bracketed paste.
- Hingga **lima workspace** terminal dapat berjalan secara independen, masing-masing memiliki shell, direktori kerja, PTY, dan riwayatnya sendiri. Sesi dijaga oleh foreground service ketika aplikasi dipindahkan ke latar belakang.
- Toolbar menyediakan tombol navigasi, `Ctrl`, `Alt`, `Esc`, `Tab`, `Home`, `End`, `PgUp/PgDn`, tempel clipboard, pencarian, dan zoom. Tampilan juga mendukung pinch-to-zoom.
- Perintah `xset` membuka dashboard pengaturan di dalam terminal untuk memilih tema, font JetBrains Mono atau font sistem, ukuran dan spasi teks, gaya cursor, kedalaman scrollback, serta ekspor/impor konfigurasi.
- Akses penyimpanan perangkat bersifat opsional. Setelah izin diberikan dari pengaturan, penyimpanan internal tersedia di `~/sdcard` dan volume eksternal yang valid dapat muncul sebagai `~/sdcard-1`, tanpa memulai ulang sesi aktif.
- Pada lingkungan rootfs, integrasi Ollama ARM64 tersedia melalui perintah `ollama`. Unduhan dilakukan hanya setelah pengguna memintanya, diverifikasi dengan SHA-256, lalu dipasang secara atomik; fitur ini memerlukan ruang kosong sekitar 1,8 GB.

## Screenshot aplikasi

### Editor kode dengan Nano

<img src="Screenshot/nano-code_editor.jpg" alt="Editor kode Python di GNU Nano" width="260" />

Tampilan editor GNU Nano yang berjalan di dalam terminal untuk menulis dan menyunting berkas kode, lengkap dengan tombol pintasan seperti `Ctrl`, navigasi, dan `Alt`.

### Informasi sistem dengan Screenfetch

<img src="Screenshot/screenfetch.jpg" alt="Informasi sistem dari Screenfetch" width="260" />

Hasil perintah `screenfetch` yang menampilkan identitas lingkungan Linux, kernel, arsitektur, penggunaan disk, CPU, RAM, dan shell aktif.

### Daftar penyimpanan internal dan eksternal

<img src="Screenshot/list-storage-internal%26%20external.jpg" alt="Daftar penyimpanan internal dan eksternal" width="260" />

Terminal memperlihatkan mount penyimpanan internal sebagai `~/sdcard` serta penyimpanan eksternal sebagai `~/sdcard-1`, termasuk isi direktori keduanya.

### Pengaturan tampilan terminal

<img src="Screenshot/display-settings.jpg" alt="Pengaturan tampilan xset" width="260" />

Menu `xset` bagian **Appearance** untuk mengubah tema, font, ukuran teks, cursor, warna latar, spasi baris, padding, dan pratinjau langsung.

### Izin akses penyimpanan

<img src="Screenshot/storage-permission.jpg" alt="Pengaturan akses penyimpanan xset" width="260" />

Menu **Storage Access** di `xset` menunjukkan status izin penyimpanan, lokasi mount, serta opsi untuk memberikan atau membangun ulang akses penyimpanan.

### Informasi perangkat

<img src="Screenshot/system-information.jpg" alt="Informasi perangkat di xset" width="260" />

Halaman **About** di `xset` merangkum pabrikan dan model perangkat, versi Android, ABI CPU, kernel, resolusi layar, memori, serta kapasitas penyimpanan.
