# Panduan rilis drac-Xterm

Cara menggunakan `scripts/release-prep.sh` untuk membawa repositori ini dari
kondisi sekarang sampai rilis pertama terbit di GitHub Releases dan siap
diajukan ke IzzyOnDroid.

Script ini mengerjakan lima langkah yang tersisa. Urutannya **ditegakkan oleh
script**, bukan sekadar disarankan: setiap langkah menolak berjalan sampai
langkah yang ia butuhkan benar-benar sudah terjadi — dan itu diperiksa ke dunia
nyata (file di disk, API GitHub), bukan ke catatan internal script sendiri.

---

## Sebelum mulai

Yang harus tersedia di mesin Anda:

| Perkakas | Dipakai untuk | Cek |
|---|---|---|
| `keytool` | membuat kunci baru | `keytool -help` |
| `gh` (login) | branch, secret, tag | `gh auth status` |
| `git` | tag dan push | `git --version` |
| `adb` | uji di perangkat | `adb devices` |
| Android SDK + NDK 27.0.12077973 | build release | `sdkmanager --list_installed` |

Perangkat **arm64** fisik (atau emulator arm64) diperlukan hanya untuk langkah 3.
Emulator x86_64 tidak bisa dipakai — semua biner prebuilt di project ini
`arm64-v8a` saja.

---

## Langkah 0 — Lihat posisi Anda

Selalu mulai dari sini. Perintah ini tidak mengubah apa pun.

```sh
./scripts/release-prep.sh
```

Contoh keluaran:

```
drac-Xterm release readiness
repository  ExsoKamabay/dracxterm
version     1.1.0 (versionCode 3)

1. Signing key rotated
  TODO  run: ./scripts/release-prep.sh rotate-key
        missing DRACOS_STORE_FILE in /home/dracos/.gradle/gradle.properties

2. Stale branch holding the leaked keystore
  TODO  add/add-kali-nethunter-rootfs still exists — run: ... clean-refs
...
```

Baca ulang perintah ini setiap selesai satu langkah. Kalau sebuah langkah masih
berstatus `TODO` padahal Anda merasa sudah mengerjakannya, **script yang benar**
— ia memeriksa keadaan sebenarnya.

---

## Langkah 1 — Rotasi kunci penandatanganan

```sh
./scripts/release-prep.sh rotate-key
```

### Kenapa ini wajib

Keystore lama sudah publik dan **tidak bisa ditarik kembali**. Force-push sudah
mengganti `main`, tapi GitHub tetap menyajikannya lewat `refs/pull/1/head` dan
`refs/pull/2/head`, dan pemilik repositori tidak punya wewenang menghapus ref
itu. Di Android, kunci penandatanganan **adalah** identitas aplikasi: sekali
sebuah rilis terbit dengan suatu kunci, semua update berikutnya harus memakai
kunci yang sama. Menerbitkan rilis dengan kunci yang bocor tidak punya jalan
pemulihan yang bersih.

Selama belum ada rilis terbit, mengganti kunci masih gratis. Setelah v1 terbit,
tidak lagi.

### Apa yang diminta

Script akan meminta passphrase dua kali. **Input tidak terlihat di layar** —
itu memang disengaja, bukan terminal yang macet.

```
Passphrase for the new keystore (input hidden, min 12 chars):
Repeat:
```

Syarat: minimal 12 karakter, dan jangan pakai ulang passphrase dari layanan lain.

### Apa yang dikerjakan

1. Membuat `~/.android-keys/dracxterm-release.jks` (RSA 4096, berlaku 30 tahun),
   mode `700` untuk direktori dan `600` untuk file.
2. Menulis empat properti ke `~/.gradle/gradle.properties` dengan mode `600`.
   Baris `DRACOS_*` lama **diganti**, bukan ditambahkan — Gradle memakai baris
   terakhir, jadi baris usang di atas adalah jebakan diam.
3. Menjalankan `./gradlew clean assembleRelease` untuk membuktikan kunci itu
   benar-benar bisa menandatangani, lalu menampilkan sertifikatnya.
4. Menghapus APK uji tersebut supaya tidak ada yang salah kira itu rilis.

### Setelah selesai

Script akan menampilkan SHA-256 sertifikat. **Catat nilai itu.** IzzyOnDroid
mengunci sidik jari APK pertama yang mereka terima; setiap rilis berikutnya harus
cocok.

Lalu **cadangkan `~/.android-keys/dracxterm-release.jks` ke media offline.**
Kehilangan file itu berarti selamanya tidak bisa mengupdate aplikasi — Android
akan menolak APK apa pun yang ditandatangani kunci berbeda.

### Kalau gagal

| Pesan | Artinya |
|---|---|
| `already exists. Refusing to overwrite it.` | Sudah ada keystore di sana. Script sengaja tidak menimpanya. Kalau memang mau mulai ulang, pindahkan file itu sendiri lebih dulu. |
| `the two entries differ` | Dua ketikan passphrase tidak sama. Ulangi. |
| `assembleRelease failed with the new key` | Kunci terbuat tapi build gagal. Jalankan `./gradlew assembleRelease` sendiri untuk melihat pesan aslinya. |

---

## Langkah 2 — Hapus branch sisa

```sh
./scripts/release-prep.sh clean-refs
```

Branch `add/add-kali-nethunter-rootfs` adalah sisa pull request lama yang masih
menunjuk history berisi keystore. Ini satu-satunya referensi tersisa yang
**bisa** dihapus pemilik repositori.

Script akan menampilkan SHA yang ditunjuk branch itu dan cara mengembalikannya,
lalu meminta konfirmasi:

```
Delete the branch?
type DELETE to continue:
```

Ketik `DELETE` persis. Jawaban lain apa pun membatalkan tanpa menghapus apa pun.

Bisa dikembalikan karena history lama tercadang di
`~/Desktop/dracxterm-remote-backup.bundle`:

```sh
git push origin <sha>:refs/heads/add/add-kali-nethunter-rootfs
```

### Yang tetap tidak bisa dihapus

Setelah branch terhapus, script menampilkan ref pull request yang masih ada:

```
    94f24cf...  refs/pull/1/head
    94f24cf...  refs/pull/2/head
```

Hanya GitHub Support yang bisa membersihkan itu. Ajukan permintaan di
<https://support.github.com/>, sebutkan repositori dan path
`keystore/dracos-release.keystore`.

**Jangan anggap itu solusinya.** Solusinya adalah rotasi di langkah 1.

---

## Langkah 3 — Uji biner prebuilt di perangkat arm64

```sh
./prebuilts/build.sh                    # kalau belum pernah
./scripts/release-prep.sh device-test
```

### Kenapa ini ada

`./prebuilts/build.sh` menghasilkan BusyBox **1.38.0** menggantikan **1.29.3**
(2018) — tujuh tahun perubahan upstream dengan daftar applet yang berbeda — dan
PRoot yang dibangun dari sumber, bukan lagi biner Termux warisan.

Biner-biner itu terkompilasi, ter-link, arsitekturnya AArch64, dan simbolnya
benar. **Tidak satu pun dari itu bukti bahwa mereka jalan.** Langkah inilah yang
menentukan apakah `./prebuilts/build.sh --install` aman.

### Persiapan perangkat

1. Aktifkan **Opsi Pengembang** → **USB debugging** di ponsel.
2. Hubungkan lewat USB, lalu setujui dialog "Allow USB debugging".
3. Pastikan terbaca:

```sh
adb devices
```

Harus muncul satu baris dengan status `device` (bukan `unauthorized` atau
`offline`).

Kalau ada lebih dari satu perangkat, pilih salah satu:

```sh
export ANDROID_SERIAL=<serial>
```

### Apa yang dikerjakan

Script memeriksa ABI dan level API perangkat lebih dulu, menyalin kelima biner
ke `/data/local/tmp/dracxterm-smoke/`, lalu menjalankan 18 pemeriksaan:

**BusyBox (12)** — versi, `sh`, `ls`, `cat`, `sed`, `awk`, `grep`, tar
round-trip, ketersediaan `xz`, `wget`, `mount`, dan jumlah applet.

**PRoot (6)** — versi terpin, resolusi shared library, lalu **membangun rootfs
minimal dari BusyBox itu sendiri di perangkat dan memasukinya**: menjalankan
shell di dalamnya, melihat isi `/`, membaca file lewat bind mount, dan memeriksa
`uid=0`. Itu adalah jalur runtime aplikasi dalam bentuk mini — kalau bagian ini
jalan, menyiapkan rootfs sungguhan hanya soal skala.

### Membaca hasilnya

```
Result
  18 passed, 0 failed

The built binaries work on real hardware.
```

Kalau semua lolos, hasilnya dicatat di `prebuilts/work/.device-test-passed`
**beserta SHA-256 setiap biner** — jadi kalau Anda membangun ulang, catatan itu
otomatis tidak berlaku lagi.

Kalau ada yang gagal:

```
  FAIL  reports version 1.38.0 — expected BusyBox v1.38.0

Do not install these binaries.
```

Script keluar dengan status non-zero dan **tidak** menulis catatan lulus. Untuk
melihat keluaran mentah setiap perintah:

```sh
DT_VERBOSE=1 ./scripts/release-prep.sh device-test
```

### Kalau gagal di awal

| Pesan | Artinya |
|---|---|
| `no device connected` | `adb devices` kosong. Cek kabel, USB debugging, dan dialog otorisasi. |
| `device ABI is x86_64` | Emulator/perangkat bukan arm64. Tidak bisa dipakai. |
| `cannot execute from ...` | `/data/local/tmp` di-mount `noexec` atau ditolak SELinux. Ini sifat lokasi ujinya, bukan binernya. Coba lokasi lain: `DRACXTERM_DEVICE_DIR=/data/local/tmp/lain ./scripts/release-prep.sh device-test` |

### Setelah lulus

Memasang biner baru ke APK adalah keputusan terpisah — dan urutannya penting:
jalankan `device-test` **sebelum** `--install`, bukan sesudah. Kalau terbalik,
langkah 5 akan menolak sampai pengujian dilakukan.

```sh
./prebuilts/build.sh --install
```

Kalau melakukannya, perbarui kolom SHA-256 di
[`docs/THIRD-PARTY-BINARIES.md`](THIRD-PARTY-BINARIES.md) dalam commit yang sama,
lalu jalankan ulang alur provisioning aplikasi dari awal sampai akhir di
perangkat.

**Rilis v1.1.0 tidak menunggu langkah ini.** APK saat ini masih memakai biner
warisan. Langkah 3 adalah gerbang untuk `--install`, bukan untuk rilis.

---

## Langkah 4 — Unggah empat secret ke GitHub

```sh
./scripts/release-prep.sh secrets
```

Membaca kredensial dari `~/.gradle/gradle.properties` dan mengunggahnya sebagai
GitHub Actions secrets, supaya `.github/workflows/release.yml` bisa membangun APK
bertanda tangan.

| Secret | Isi |
|---|---|
| `DRACOS_KEYSTORE_BASE64` | keystore, di-base64 |
| `DRACOS_STORE_PASSWORD` | password store |
| `DRACOS_KEY_ALIAS` | alias kunci |
| `DRACOS_KEY_PASSWORD` | password kunci |

Konfirmasi yang diminta: ketik `UPLOAD`.

Tidak ada nilai yang dicetak ke layar, dan tidak ada yang lewat argumen baris
perintah — isi `argv` bisa dibaca proses lain lewat `/proc`.

### Pengaman tambahan

Kalau keystore yang akan diunggah berukuran **2744 byte** — ukuran persis
keystore yang bocor — script memberi peringatan dan meminta konfirmasi kedua
(`IUNDERSTAND`). Kalau peringatan ini muncul, hampir pasti Anda melewatkan
langkah 1.

Script juga menolak jalan sama sekali kalau langkah 1 belum selesai:

```
error: signing credentials are not configured.
  Run './scripts/release-prep.sh rotate-key' first. Pushing the burned key's
  credentials as secrets would make the leak worse, not better.
```

### Kalau gagal

`401` atau `403` berarti token `gh` tidak berwenang menulis Actions secrets:

```sh
gh auth refresh -h github.com -s repo
```

---

## Langkah 5 — Tag dan terbitkan

```sh
./scripts/release-prep.sh tag
```

### Pemeriksaan sebelum tag dibuat

Script menjalankan seluruh pemeriksaan yang nanti dilakukan workflow, lebih dulu
— tag itu murah dibuat tapi merepotkan ditarik setelah CI menerbitkan rilis:

```
==> pre-flight
  ok    working tree clean
  ok    on main
  ok    main matches origin/main
  ok    IzzyOnDroid requirements pass
  ok    every locale has a changelog for versionCode 3
  ok    v1.1.0 is unused
  ok    release secrets are present
```

Kalau biner prebuilt belum pernah diuji di perangkat, muncul peringatan — bukan
kesalahan — dengan alasan yang jelas: biner itu tidak ada di dalam APK ini.

Konfirmasi yang diminta: ketik `RELEASE`.

### Apa yang terjadi setelahnya

`git tag -a v1.1.0` dibuat dan di-push. Itu memicu
`.github/workflows/release.yml`, yang:

1. menjalankan pemeriksaan IzzyOnDroid dan uji engine terminal,
2. memastikan nama tag cocok dengan `versionName`,
3. membangun APK bertanda tangan,
4. memverifikasi tanda tangannya dan mencetak sertifikatnya,
5. melaporkan ukuran APK terhadap batas ~30 MB,
6. menerbitkan GitHub Release berisi APK dan file `.sha256`-nya.

Pantau:

```sh
gh run watch -R ExsoKamabay/dracxterm
gh release view v1.1.0 -R ExsoKamabay/dracxterm
```

### Aturan yang tidak boleh dilanggar

**APK yang sudah terbit tidak pernah diganti.** Kalau ada yang salah, naikkan
`versionCode`, tulis changelog baru, dan buat tag baru. Script menolak
`versionCode` yang tag-nya sudah terbit, dan workflow memakai
`gh release create --verify-tag` sehingga tidak bisa mengarang tag.

---

## Ringkasan alur

```
./scripts/release-prep.sh              lihat posisi
        │
        ├─ 1. rotate-key    → passphrase 2×, APK uji ditandatangani, catat SHA-256 sertifikat
        │                     ⚠ CADANGKAN keystore ke media offline
        │
        ├─ 2. clean-refs    → ketik DELETE
        │                     ⚠ ref pull request tetap ada; hubungi GitHub Support
        │
        ├─ 3. device-test   → butuh perangkat arm64; 18 pemeriksaan
        │                     (gerbang untuk --install, bukan untuk rilis)
        │
        ├─ 4. secrets       → ketik UPLOAD
        │
        └─ 5. tag           → ketik RELEASE → CI menerbitkan rilis
                              │
                              └─ ajukan ke IzzyOnDroid
```

Setelah semuanya hijau, jalankan `./scripts/release-prep.sh` sekali lagi untuk
memastikan, lalu ajukan permohonan inklusi. Bahan yang perlu disiapkan ada di
[`docs/IZZYONDROID-SUBMISSION.md`](IZZYONDROID-SUBMISSION.md), termasuk satu hal
yang tidak bisa diselesaikan script mana pun: kebijakan IzzyOnDroid soal
perangkat lunak yang dibuat dengan bantuan AI.

---

## Kesalahan umum

Dua hal ini benar-benar terjadi saat menjalankan panduan ini pertama kali.

### 1. Menjalankan `--install` sebelum `device-test`

```sh
./prebuilts/build.sh --install     # <- biner diganti
./scripts/release-prep.sh tag      # <- ditolak
```

Kalau `--install` dijalankan sebelum langkah 3, isi APK berubah menjadi biner
yang belum pernah dijalankan sama sekali. Sejak versi sekarang, `tag`
**menolak** kondisi itu, bukan sekadar memperingatkan:

```
error: the APK contains prebuilt binaries that have never been run.
```

Dua jalan keluar, keduanya disebutkan oleh pesan errornya:

```sh
./scripts/release-prep.sh device-test        # uji sekarang, lalu lanjut
git checkout -- app/src/main/jniLibs/arm64-v8a/   # kembalikan biner lama
```

Kalau uji perangkat lulus tapi `docs/THIRD-PARTY-BINARIES.md` belum diperbarui,
`tag` juga menolak — dan mencetak SHA-256 terbaru yang harus Anda tulis ke tabel
itu. Dokumentasi dan isi APK tidak boleh berbeda diam-diam.

Urutan yang benar: **`device-test` dulu, baru `--install`.**

### 2. Menyimpan passphrase di dalam direktori repo

Jangan menyalin keluaran `rotate-key` ke file di dalam `~/Desktop/dracxterm/`.
Menaruh passphrase di `docs/pass.txt` berarti `git add -A` berikutnya
meng-commit kunci Anda ke repositori publik — persis kesalahan yang membakar
kunci pertama project ini.

`.gitignore` sekarang menutupi `pass.txt`, `secrets.txt`, `*-password*`, dan
sejenisnya. Tapi jangan bergantung pada itu. Simpan catatan sertifikat di luar
repo:

```sh
~/.android-keys/certificate-notes.txt     # chmod 600
```

Kalau terlanjur, periksa apakah sudah masuk history sebelum panik:

```sh
git log --all --oneline -- docs/pass.txt
```

Kosong berarti belum pernah ter-commit — cukup pindahkan file-nya, tidak perlu
rotasi ulang.

## Yang tidak diotomatisasi, dan alasannya

| Hal | Kenapa |
|---|---|
| Membersihkan `refs/pull/*` | Hanya GitHub Support yang punya wewenangnya |
| Mencadangkan keystore | Media offline adalah keputusan Anda, dan script tidak boleh menyalin kunci ke tempat yang tidak Anda tentukan |
| `--install` biner prebuilt | Mengubah isi APK; harus keputusan sadar setelah langkah 3 lulus |
| Memindai APK dengan exodus / VirusTotal | Layanan pihak ketiga, unggahan manual |
| Menaikkan `versionCode` untuk rilis berikutnya | Perubahan sumber, bukan operasi rilis |
