# drac-Xterm — Terminal Settings Integration (Final Phase Report, TRUTH MODE)

Tujuan: memastikan **Terminal Settings Dashboard (`xset` TUI)** terintegrasi penuh, stabil, tanpa placeholder, dan siap build/uji. Dashboard adalah **TUI di dalam terminal** (bukan Activity/Fragment/Dialog).

## 1. Hasil audit integrasi (dibaca dari source)

Jalur end-to-end **sudah ter-wire** dari fase-fase sebelumnya dan terverifikasi koheren:

```
guest `xset [mod]`  (assets/compat/xset: printf OSC 5391 "DRACX;open;<mod>")
   → engine parse OSC 5391 → Terminal.takeAppControl()
   → JNI NativeTerminal.nativeAppControl → TerminalSession.appControl()
   → TerminalView poll (rev-tracked, priming-safe) → onAppControl
   → MainActivity.handleAppControl → TerminalView.openDashboard(mod)
   → XsetController.open → onDraw paints controller.render() via SHARED drawGrid
   → keyboard routed to controller (sendSpecial/sendChar/onKeyDown guard d.active)
```

Instansiasi & binding nyata: `MainActivity`: `xset = XsetController(makeXsetContext())` → `terminal.dashboard = xset`; `onAppControl = { handleAppControl(it) }`.

**Persistence nyata (bukan stub):** `XsetContext` meng-apply live ke setter `TerminalView` nyata; `exportConfig`/`importConfig` menulis/membaca `filesDir/xset-config.json` via `XsetStore.toJson/fromJson`; reset via `store.resetAll` + `reapplyAll`.

**Compile-safety statis:** 14/14 setter `TerminalView` + semua helper `MainActivity` yang dirujuk `XsetContext` ada.

**No placeholder:** scan seluruh `java/`+`cpp/`+`assets/` → **tidak ada** TODO/FIXME/dummy/stub/fake/HACK (satu-satunya komentar adalah "not shipped as placeholders" untuk modul roadmap yang sengaja di-comment).

## 2. Defect ditemukan & diperbaiki (RCA)

**Touch-event leak saat dashboard aktif (fungsional/UX).**
- **Akar:** `TerminalView.onTouchEvent` tidak meng-guard `dashboard.active`. Keyboard sudah di-guard, sentuh tidak. Saat dashboard terbuka (PTY idle, ter-overlay), swipe → `session.scroll()` menggeser scrollback terminal tersembunyi; long-press → memulai seleksi; pinch → resize; double-tap → reset zoom; tap → `scrollToBottom`.
- **Dampak:** dashboard bisa merusak posisi scroll/seleksi terminal di belakangnya. Tidak layak produksi.
- **Fix (minimal, aman):** `if (dashboard?.active == true) return true` di awal `onTouchEvent`. Menutup semua kebocoran; dashboard tetap keyboard-driven. **Perilaku terminal normal 100% tak berubah** saat dashboard tertutup — tidak melanggar Stability Policy (justru melindunginya).
- **Risiko regresi:** nihil pada jalur terminal normal (guard hanya aktif ketika `dashboard.active`).

## 2b. Production hardening — OSC string bound (engine)

**Temuan (Release audit).** `AnsiParser::osc()` menambah `oscBuf_` **tanpa batas panjang**. Program guest yang mengirim OSC (`ESC ]`) panjang tanpa terminator ST bisa menumbuhkan buffer tanpa batas → **memory-exhaustion DoS**, tepat pada channel yang dipakai `xset` (OSC 5391).

**Fix (minimal, aman, engine).** Ceiling `kMaxOscBytes = 1 MiB`: `if (b >= 0x20 && oscBuf_.size() < kMaxOscBytes) oscBuf_.push_back(...)`. Payload sah (title, base64 clipboard OSC 52, DRACX) jauh di bawah 1 MiB → **nol regresi**; OSC liar dibatasi dan parser tetap pulih ke Ground di ST. `dcs()` sudah membuang payload (aman, tak diubah).

**Bukti (host).** `t_osc_bound.cpp` (`-Wall -Wextra -Werror`): title/appControl tak teregresi; OSC berlebih dipotong tepat di cap (1.048.574 B); parser memproses OSC 5391 berikutnya (pulih). Sanity: tanpa cap, assertion `size <= CAP` **gagal** → membuktikan cap bekerja.

## 3. Regression audit (WAJIB) — status

| Item | Status | Bukti |
|---|---|---|
| xset logic (store/registry/nav/adjust/apply/reset/search/export-import/layout/parity/error-recovery) | ✅ VERIFIED | host mirror **191 assertion PASS** |
| Engine C++ (parser/screen/terminal/appctrl) + OSC bound | ✅ VERIFIED | `t_baseline`+`t_appctrl`+`t_osc_bound` pass `-Wall -Wextra -Werror` |
| Design System konsistensi | ✅ VERIFIED | token parity byte-identical (mirror grup [9]) |
| Error recovery (modul gagal tak crash) | ✅ VERIFIED | mirror grup [10] |
| Input routing (keyboard) tidak bocor | ✅ VERIFIED (static) | guard `d.active` di sendSpecial/sendChar/sendText/onKeyDown |
| Touch tidak bocor ke PTY saat dashboard aktif | ✅ VERIFIED (static) | guard baru di onTouchEvent |
| Scroll/Zoom/Toolbar terminal normal | 🟡 PARTIALLY | guard hanya aktif saat dashboard on; jalur normal tak diubah — perilaku runtime device-verify |
| Kompilasi Kotlin/Android; dashboard benar-benar terbuka di perangkat; SharedPreferences persist; live-render | 📱 DEVICE VERIFY REQUIRED | tak ada kotlinc/SDK/device di lingkungan ini |
| RootFS/Compatibility Engine | ✅ tak-tersentuh (integrasi hanya menambah command `xset`, idempotent, non-fatal) | Bootstrap.installXsetCommand |
| Memory leak / race condition | 🟡 PARTIALLY | tak ada pattern baru berisiko (dashboard single-thread UI, poll rev-tracked); analisis statis, bukan profiler |

## 4. Jujur: yang belum dapat diverifikasi di lingkungan ini
Kompilasi Gradle (`./gradlew assembleDebug`), pembukaan dashboard aktual, dialog IME, persist SharedPreferences lintas-restart, live-render `session.configure`, dan render Canvas — semuanya **DEVICE VERIFY REQUIRED**. Tidak diklaim selesai di level runtime Android.

## 5. Quality gate
Fitur lama utuh (guard hanya menambah cabang saat dashboard aktif); fitur baru fungsional di level logic (host-verified); audit selesai; regresi diperiksa; API Contract & Design System tetap dipatuhi. Item runtime Android ditandai DEVICE VERIFY REQUIRED secara eksplisit.
