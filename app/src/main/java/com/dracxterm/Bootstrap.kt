package com.dracxterm

import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import com.dracxterm.ollama.OllamaLauncher
import com.dracxterm.rootfs.RootfsConfigurator
import com.dracxterm.rootfs.ShellLocator
import java.io.File

/**
 * Prepares the runtime environment for the shell.
 *
 * AUDIT-DRIVEN FACTS this class encodes:
 *  - The prebuilt binaries are arm64-v8a only and live in nativeLibraryDir as lib*.so
 *    (so they are exec()-able on API 29+).
 *  - libproot.so has DT_NEEDED "libtalloc.so.2"; the packager ships that file as
 *    libtalloc.so, so we recreate the libtalloc.so.2 symlink here.
 *  - No rootfs is shipped, so the default command is `busybox ash`. prepare() switches
 *    to proot automatically (prootArgv) once the user installs a rootfs at filesDir/rootfs.
 */
object Bootstrap {

    private const val TAG = "Bootstrap"
    private const val MARKER = ".bootstrap_v1"

    data class Spec(val argv: Array<String>, val env: Array<String>, val cwd: String)

    fun prepare(ctx: Context): Spec {
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val files = ctx.filesDir.absolutePath
        val home = "$files/home"
        val tmp = "$files/tmp"
        val usrLib = "$files/usr/lib"
        val usrBin = "$files/usr/bin"

        listOf(home, tmp, usrLib, usrBin).forEach { File(it).mkdirs() }

        val busybox = "$nativeLib/libbusybox.so"

        // Recreate libtalloc.so.2 -> nativeLib/libtalloc.so (proot's DT_NEEDED).
        symlink("$nativeLib/libtalloc.so", "$usrLib/libtalloc.so.2")

        val marker = File(files, MARKER)
        if (!marker.exists()) {
            installBusyboxApplets(busybox, usrBin)
            writeAshrc(home)
            runCatching { marker.createNewFile() }
        }
        // `xset` guest command (refreshed every launch so upgrades land) for the no-rootfs busybox path.
        installXsetCommand(ctx, usrBin)

        // Runtime selection:
        //  - If the user has installed a Linux rootfs at filesDir/rootfs, launch it
        //    through proot (which dlopens libtalloc + libandroid-shmem via LD_LIBRARY_PATH).
        //  - Otherwise fall back to busybox ash, which works with no rootfs.
        val rootfs = "$files/rootfs"
        val hasGuest = hasRootfs(rootfs)

        // `ollama` guest command. Isolated add-on: OllamaLauncher installs the launcher onto
        // whichever PATH this session will actually use (rootfs /usr/local/bin, or the busybox
        // usr/bin stub that explains the Linux-runtime requirement) and arms the on-demand
        // provisioning watcher. Refreshed every launch, exactly like installXsetCommand above.
        // Never fatal: OllamaLauncher.attach swallows and logs every failure, so a problem here
        // can never delay or block the terminal.
        OllamaLauncher.attach(ctx, if (hasGuest) File(rootfs) else null, usrBin)

        // Proot's own runtime lookups (read on the HOST side, before re-root).
        val prootEnv = arrayOf(
            "LD_LIBRARY_PATH=$nativeLib:$usrLib",
            "PROOT_LOADER=$nativeLib/libproot-loader.so",
            "PROOT_TMP_DIR=$tmp"
        )

        // Guest login shell (real interpreter inside the rootfs), used for SHELL so the sudo/su
        // shims' `${SHELL:-…}` and `su -c` paths resolve to a shell the image actually ships.
        val guestShell = if (hasGuest) (ShellLocator.guestPath(File(rootfs)) ?: "/bin/sh") else "/bin/sh"

        val env = if (hasGuest) {
            // GUEST environment. Default identity is the NORMAL user 'dracos' with HOME=/home/dracos
            // (directive: terminal must start in /home/<username>, whoami=dracos, prompt '$'). The
            // container is PRoot fake-root (kernel euid 0) so escalation via sudo/su still works; the
            // dracos↔root distinction is a presentation layer driven by $DRAC_SU (the prompt state
            // machine in ~/.bashrc + the whoami/id/logname shims on PATH). DRAC_HOME pins the themed
            // rc so the super-user shells load it even with HOME=/root. A standard Linux PATH keeps
            // /etc/profile's `id`/`[` builtins resolvable.
            arrayOf(
                "HOME=/home/dracos",
                "PWD=/home/dracos",
                "USER=dracos",
                "LOGNAME=dracos",
                "DRAC_HOME=/home/dracos",
                "SHELL=$guestShell",
                "TMPDIR=/tmp",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            ) + prootEnv
        } else {
            // BusyBox-over-Android environment (no rootfs): Android paths are correct. There is no
            // real /home/dracos on a non-rooted device, so HOME stays app-private; the identity is
            // still presented as 'dracos' (USER + a whoami function seeded into .ashrc) and the
            // prompt ends with '$' to match the normal-user contract of the primary (proot) path.
            arrayOf(
                "HOME=$home",
                "PWD=$home",
                "USER=dracos",
                "LOGNAME=dracos",
                "TMPDIR=$tmp",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=$usrBin:$nativeLib:/system/bin",
                "ENV=$home/.ashrc"
            ) + prootEnv
        }

        val argv = if (hasGuest) {
            Log.i(TAG, "rootfs found at $rootfs -> launching via proot")
            prootArgv(ctx, rootfs)          // proot sets the guest cwd via -w /home/dracos
        } else {
            arrayOf(busybox, "ash")
        }
        // The host-side cwd handed to execve is always a real host directory ($HOME);
        // proot re-roots the guest itself, so this stays valid in both modes.
        return Spec(argv, env, home)
    }

    /**
     * Build a proot FAKE-ROOT (-0) one-shot invocation that runs `/bin/sh -c <script>` inside the
     * rootfs, for bootstrap-time maintenance such as the dpkg recovery engine. Fake-root is the
     * only context in which chown/permission repair inside a proot guest succeeds without Android
     * root. Ensures proot's runtime deps (libtalloc symlink, tmp dir) exist, exactly like prepare().
     * Returns (argv, env) ready for ProcessBuilder.
     */
    fun fakerootShell(ctx: Context, rootfs: String, script: String): Pair<Array<String>, Array<String>> {
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val files = ctx.filesDir.absolutePath
        val tmp = "$files/tmp"; val usrLib = "$files/usr/lib"
        listOf(tmp, usrLib).forEach { File(it).mkdirs() }
        symlink("$nativeLib/libtalloc.so", "$usrLib/libtalloc.so.2")   // proot DT_NEEDED
        val env = arrayOf(
            "HOME=/root", "PWD=/root", "TMPDIR=/tmp",
            "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH=$nativeLib:$usrLib",
            "PROOT_LOADER=$nativeLib/libproot-loader.so",
            "PROOT_TMP_DIR=$tmp"
        )
        val argv = arrayOf(
            "$nativeLib/libproot.so", "-0", "--link2symlink", "-r", rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/", "/bin/sh", "-c", script
        )
        return argv to env
    }

    /**
     * True when [rootfs] provides a login shell. Uses ShellLocator (Os.lstat based, wide
     * candidate set) so symlinked shells such as Alpine /bin/sh -> /bin/busybox are detected
     * correctly instead of being missed by File.exists() (which follows the link to the host).
     */
    private fun hasRootfs(rootfs: String): Boolean =
        ShellLocator.find(File(rootfs)) != null

    /**
     * proot command for a user-supplied rootfs. Selected automatically by prepare() when
     * a rootfs is present at filesDir/rootfs; no rootfs is bundled in the components.
     */
    fun prootArgv(ctx: Context, rootfs: String): Array<String> {
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val rootfsDir = File(rootfs)

        // Writable guest HOME backed by a host directory, bound over /home/dracos below. World-
        // writable so the session can always write it regardless of host-side ownership of the
        // extracted files (an in-rootfs home can otherwise be read-only under proot).
        val guestHome = File(ctx.filesDir, "guest-home").apply { mkdirs() }
        runCatching { Os.chmod(guestHome.absolutePath, 0x1FF) }   // 0777
        seedUserHome(ctx, guestHome)
        runCatching { File(rootfsDir, "root").mkdirs() }          // /root exists (super-user HOME target)
        runCatching { File(rootfsDir, "home/dracos").mkdirs() }   // /home/dracos exists (normal-user HOME + bind target)
        // Idempotently ensure the normal login user 'dracos' (uid/gid 1000) + passwordless sudo,
        // EVERY launch — so an ALREADY-provisioned rootfs (configured before this feature existed)
        // also gains the user without a re-provision. Never fatal: on any error we still boot.
        runCatching { RootfsConfigurator().ensureUser(rootfsDir) }
            .onFailure { Log.w(ShellLocator.TAG, "[BOOTSTRAP] ensureUser skipped: ${it.message}") }
        installCompatShims(ctx, rootfsDir)                        // sudo/su/fakeroot + whoami/id/logname (idempotent)
        installXsetCommand(ctx, File(rootfsDir, "usr/local/bin").absolutePath)  // `xset` dashboard command

        val binds = ArrayList<String>()
        fun bind(src: String?, dst: String) { if (src != null) { binds += "-b"; binds += "$src:$dst" } }
        bind("/dev", "/dev"); bind("/proc", "/proc"); bind("/sys", "/sys")

        // Bind the writable guest HOME onto /home/dracos.
        bind(guestHome.absolutePath, "/home/dracos")

        // ---- Storage integration (Settings ▸ Storage Access; adapts termux-setup-storage) ----
        // SESSION-STABLE MOUNTING. proot binds are fixed at spawn and a running proot can NEVER be
        // re-bound, so each real volume is grafted ONCE here to a stable, TOP-LEVEL guest mountpoint
        // /mnt/<name> — UNCONDITIONALLY, i.e. regardless of whether storage permission is held yet.
        //
        // ROOT-CAUSE FIX: a proot bind is only a path-translation rule; ESTABLISHING it needs no read
        // permission and fabricates nothing — reads stay OS-gated, so an unpermitted source degrades to
        // an EACCES/empty (but REAL) directory, never mock data. The previous build gated the bind on a
        // start-time permission snapshot (storageBackable). But the user grants access through xset
        // AFTER the shell has spawned (the normal first-run flow), so the snapshot was false at spawn
        // and the running proot ended up with no backing to expose — which is exactly why ~/sdcard and
        // ~/sdcard-1 never appeared after enabling Storage Access. Binding unconditionally closes that
        // gap: the backing is always present in the running namespace, and a later grant + the live
        // symlink below make ~/sdcard real on the SAME session (same PTY, same native handle, no respawn).
        //   • /mnt/sdcard      <- internal shared-storage root (Environment.getExternalStorageDirectory()).
        //   • /mnt/sdcard-1 …  <- a REMOVABLE volume ROOT, only when one genuinely exists & is readable.
        // TOP-LEVEL, not nested under the /home/dracos bind: overlapping/nested binds are a proot
        // path-canonicalisation hazard (empirically, on proot 5.1.0 a bind mountpoint nested under
        // another bind can fail to stat even though its contents remain reachable), so the mountpoint is
        // kept OUTSIDE the home-bind subtree and surfaced into HOME by a plain symlink instead.
        runCatching { File(rootfsDir, "mnt").mkdirs() }          // ensure the guest /mnt bind-parent exists
        val bound = LinkedHashSet<String>()
        for ((name, src) in storageVolumes(ctx)) {
            bind(src.absolutePath, "/mnt/$name")
            bound += name
        }
        boundStorageNames = bound
        applyStorageVisibility(ctx, storageAccessEnabled(ctx))   // reflect the persisted ON/OFF at spawn
        logStorageTrace(ctx, "spawn")

        // Container identity: PRoot FAKE-ROOT (-0). In a single-user PRoot container without Android
        // root, fake-root is the ONLY context in which apt/dpkg/sudo/su can perform privileged writes
        // (dpkg checks geteuid()==0; sudo requires a uid-0 setuid binary PRoot cannot forge). So the
        // kernel euid is 0 in every state — that is a PRoot invariant, not a choice. The NORMAL vs
        // SUPER_USER distinction the directive asks for is therefore presented at the shell level:
        // the login runs as 'dracos' (HOME=/home/dracos, prompt '$', whoami/id shims report 1000)
        // while `sudo`/`su`/`sudo su` flip $DRAC_SU -> root/'#'/uid 0 and back on exit — with real
        // privilege throughout because euid genuinely is 0. This mirrors proot-distro (Termux),
        // UserLAnd and Andronix, which all run their containers as root under the hood.
        // --link2symlink: emulate hardlinks via symlinks so dpkg's link()-based backups succeed on
        // Android's app-private filesystem (the proven proot-distro/Termux fix for apt/dpkg).
        val shell = ShellLocator.guestPath(rootfsDir) ?: "/bin/sh"
        val head = arrayOf("$nativeLib/libproot.so", "-0", "--link2symlink", "-r", rootfs) + binds.toTypedArray()
        Log.i(ShellLocator.TAG, "[BOOTSTRAP] login user=dracos HOME=/home/dracos (fake-root euid 0, link2symlink), shell=$shell")
        val tail = arrayOf("-w", "/home/dracos", shell, "-l")

        val argv = head + tail
        Log.i(ShellLocator.TAG, "[BOOTSTRAP] proot argv: " + argv.joinToString(" "))
        return argv
    }

    /** Read the persisted Storage-Access opt-in from the xset SharedPreferences store (key
     *  `storage.enabled`). Default OFF: the directive requires the ~/sdcard entries to appear
     *  only after the user enables the feature in Settings. */
    private fun storageAccessEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("xset", Context.MODE_PRIVATE)
            .getString("storage.enabled", "off")
            .let { it == "on" || it == "1" || it == "true" }

    // Start-time snapshot of whether this process holds shared-storage access, taken once at
    // MainActivity.onCreate. It NO LONGER gates the spawn-time binds (those are now unconditional —
    // see prootArgv), because gating on a pre-grant snapshot was the root cause of ~/sdcard never
    // appearing. It is retained only as a diagnostic baseline (logged by STORAGE_TRACE) and is
    // refreshed live by applyStorageVisibility from PermissionManager.storageGranted at each toggle,
    // so a grant made after start is honoured without a relaunch wherever the OS exposes it to the
    // running process.
    @Volatile private var storageBackableFlag: Boolean = false

    // Guest volume names actually bound at spawn (proot binds are spawn-fixed). applyStorageVisibility
    // only creates ~/<name> symlinks for names in this set, so a volume that appears AFTER spawn — which
    // a running proot can never bind — never gets a dangling link.
    @Volatile private var boundStorageNames: Set<String> = emptySet()

    /** Snapshot, once per process (call from MainActivity.onCreate BEFORE the first session starts),
     *  whether this process holds path-level shared-storage access. Diagnostic baseline only; the
     *  spawn binds are unconditional and the live toggle re-checks permission. Non-fatal. */
    fun captureStorageBackable(ctx: Context) {
        storageBackableFlag = runCatching { PermissionManager.storageGranted(ctx) }.getOrDefault(false)
    }

    /** The storage volumes to expose for THIS device: guest name -> REAL host source root. `sdcard`
     *  is the internal shared-storage root (/sdcard = Environment.getExternalStorageDirectory()).
     *  Removable volumes (SD card / USB-OTG) become `sdcard-1`, `sdcard-2`, … — each the volume ROOT,
     *  and ONLY when that root actually exists and is readable. There is deliberately NO app-specific
     *  fallback: a private app dir is not external storage, so an absent/unreadable removable volume
     *  is simply omitted (sdcard-1 is never invented). Order: internal first, then removables. */
    private fun storageVolumes(ctx: Context): LinkedHashMap<String, File> {
        val out = LinkedHashMap<String, File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?.takeIf { it.exists() }?.let { out["sdcard"] = it }
        val volumes = runCatching { ctx.getExternalFilesDirs(null) }.getOrNull() ?: emptyArray()
        var ext = 0
        for (i in 1 until volumes.size) {
            val appDir = volumes[i] ?: continue
            val root = generateSequence(appDir as File?) { it.parentFile }
                .firstOrNull { it.name.equals("Android", ignoreCase = true) }?.parentFile
            // Real removable ROOT only — exists AND readable. No fabricated placeholder otherwise.
            if (root != null && root.exists() && root.canRead()) {
                ext++
                out[if (ext == 1) "sdcard-1" else "sdcard-$ext"] = root
            }
        }
        return out
    }

    /** Guest names this build may expose now (`sdcard*`) plus legacy names from earlier versions,
     *  used to purge stale visibility links so an old `~/storage`/`~/external` never lingers. */
    private val STORAGE_LINK_NAMES_TO_CLEAR =
        listOf("storage", "external", "external-2", "external-3", "sdcard", "sdcard-1", "sdcard-2", "sdcard-3")

    /**
     * LIVE Storage-Access visibility toggle on the RUNNING session — no proot restart, no session
     * swap. The backing binds (/mnt/<name>) are grafted once at spawn; here we only create or remove
     * the plain symlinks  ~/<name> -> /mnt/<name>  inside the host-side guest-home that proot has
     * bound onto /home/dracos, so the change is visible to the live shell immediately (a genuine
     * filesystem edit — not a cosmetic `ls` filter):
     *   • enabled  -> create ~/sdcard (+ ~/sdcard-1 … for each REAL volume bound at spawn)
     *   • disabled -> remove them (and any legacy-named links)
     * Permission is re-checked LIVE here (PermissionManager.storageGranted), not read from the stale
     * start-time snapshot, so a grant made through xset AFTER the shell spawned is honoured without a
     * relaunch wherever the OS exposes it to the running process. Idempotent + non-fatal. Returns true
     * when the requested state was applied; false only when ON was requested but no storage permission
     * is held — so the caller can ask the user to grant it instead of fabricating an empty ~/sdcard. */
    fun applyStorageVisibility(ctx: Context, enabled: Boolean): Boolean {
        val guestHome = File(ctx.filesDir, "guest-home").apply { runCatching { mkdirs() } }
        if (!enabled) {
            for (name in STORAGE_LINK_NAMES_TO_CLEAR) removeLink(File(guestHome, name))
            logStorageTrace(ctx, "toggle-off")
            return true
        }
        // Always clear legacy-scheme links even on ON, so an old `~/storage` never shadows `~/sdcard`.
        for (name in listOf("storage", "external", "external-2", "external-3")) removeLink(File(guestHome, name))
        // LIVE permission re-check (refreshes the diagnostic snapshot too).
        val granted = runCatching { PermissionManager.storageGranted(ctx) }.getOrDefault(false)
        storageBackableFlag = granted
        if (!granted) { logStorageTrace(ctx, "toggle-on-denied"); return false }
        // Create ~/<name> -> /mnt/<name> for each volume bound at spawn (never for one that appeared
        // later: a running proot cannot bind it, so the link would dangle).
        for (name in boundStorageNames) ensureSymlink(File(guestHome, name), "/mnt/$name")
        logStorageTrace(ctx, "toggle-on")
        return true
    }

    /** Safe diagnostic trace: `adb logcat -s STORAGE_TRACE`. Logs only STRUCTURAL facts — never file
     *  contents, user file names, or secrets — so a single device run pinpoints which layer
     *  (permission / spawn bind / live symlink) is in play. */
    private fun logStorageTrace(ctx: Context, phase: String) {
        runCatching {
            val guestHome = File(ctx.filesDir, "guest-home")
            val enabled = storageAccessEnabled(ctx)
            val granted = runCatching { PermissionManager.storageGranted(ctx) }.getOrDefault(false)
            Log.i("STORAGE_TRACE", "[$phase] enabled=$enabled granted=$granted backableSnapshot=$storageBackableFlag " +
                "guestHome=${guestHome.absolutePath} boundAtSpawn=$boundStorageNames")
            for ((name, src) in storageVolumes(ctx)) {
                val link = File(guestHome, name)
                val target = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
                Log.i("STORAGE_TRACE", "[$phase]   vol=$name guestMount=/mnt/$name src=${src.absolutePath} " +
                    "srcExists=${src.exists()} srcReadable=${src.canRead()} boundAtSpawn=${name in boundStorageNames} " +
                    "link=~/$name linkExists=${isSymlink(link)} linkTarget=$target")
            }
        }
    }

    private fun ensureSymlink(link: File, target: String) {
        runCatching {
            val cur = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
            if (cur == target) return
            removeLink(link)
            Os.symlink(target, link.absolutePath)
        }.onFailure { Log.w(TAG, "storage link ${link.name} -> $target failed: ${it.message}") }
    }

    /** Remove a visibility entry: unlink a symlink, or delete an empty legacy mountpoint dir. Never
     *  touches a non-empty directory, so real user data is never at risk. */
    private fun removeLink(link: File) {
        runCatching {
            if (isSymlink(link)) Os.remove(link.absolutePath)
            else if (link.isDirectory && (link.list()?.isEmpty() != false)) link.delete()
        }
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { (Os.lstat(f.absolutePath).st_mode.toInt() and 0xF000) == 0xA000 }.getOrDefault(false)

    /** Seed a themed prompt + one-shot login banner into the (persistent) writable guest home,
     *  without clobbering edits the user makes across reboots. */
    private fun seedUserHome(ctx: Context, home: File) {
        // Reference-style two-line prompt as a Prompt State Machine (NORMAL vs SUPER_USER). Shipped
        // as an asset so there is no fragile in-Kotlin PS1 escaping; app-managed and refreshed every
        // launch (like .profile/.banner.sh) so prompt/state changes reach already-provisioned installs.
        // The base login shell has $DRAC_SU unset -> NORMAL user 'dracos' (trailing '$'); the
        // sudo/su/fakeroot shims export DRAC_SU=1 -> SUPER_USER 'root' (trailing '#'). Exiting that
        // shell returns to the base shell -> NORMAL again (a pure shell-nesting state transition).
        runCatching {
            ctx.assets.open("bashrc").use { i -> File(home, ".bashrc").outputStream().use { i.copyTo(it) } }
        }

        // Ship the ASCII banner + its centering displayer alongside the home. These are
        // app-managed identity files, refreshed every launch so version upgrades take effect.
        runCatching {
            ctx.assets.open("banner.art").use { i -> File(home, ".banner.art").outputStream().use { i.copyTo(it) } }
        }
        runCatching {
            ctx.assets.open("banner.sh").use { i -> File(home, ".banner.sh").outputStream().use { i.copyTo(it) } }
        }
        // Suppress the Kali "Message from Kali developers" MOTD the documented way: pam_motd
        // skips it when ~/.hushlogin exists. Non-destructive — no system file is edited.
        runCatching { File(home, ".hushlogin").writeText("") }

        // Login profile (app-managed): source the interactive rc, then show the app banner ONCE
        // per login shell (not per subshell). Refreshed each launch so the flow stays current.
        runCatching {
            File(home, ".profile").writeText(
                "[ -f \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"\n" +
                "[ -f \"\$HOME/.banner.sh\" ] && sh \"\$HOME/.banner.sh\"\n"
            )
        }
    }

    /** Install PATH-based compatibility shims (sudo/su/fakeroot) into the guest's /usr/local/bin —
     *  the first standard directory on PATH, so they shadow the distro binaries that cannot work
     *  under PRoot. The session already runs as fake-root (uid 0), so each shim strips privilege-
     *  escalation syntax and execs the target as root, letting habitual `sudo apt …`, `sudo -i`,
     *  `sudo su`, `su -c`, and existing scripts keep working. Shipped as assets (no fragile in-Kotlin
     *  shell string). Host-side + refreshed every launch, so it also repairs an ALREADY-provisioned
     *  rootfs without re-provisioning. Non-fatal by design — never blocks the terminal. */
    private fun installCompatShims(ctx: Context, rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").apply { runCatching { mkdirs() } }
        // sudo/su/fakeroot: escalation shims (present the SUPER_USER identity).
        // whoami/id/logname: identity shims that keep the PRESENTED user in sync with $DRAC_SU, so
        // the normal-user default reports 'dracos'/1000 and super-user reports 'root'/0. They sit in
        // /usr/local/bin (first on PATH) so they shadow the distro's coreutils versions.
        for (name in arrayOf("sudo", "su", "fakeroot", "whoami", "id", "logname")) {
            val f = File(binDir, name)
            runCatching {
                ctx.assets.open("compat/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
                Os.chmod(f.absolutePath, 0x1ED)   // 0755 = rwxr-xr-x
            }.onFailure { Log.w(ShellLocator.TAG, "[COMPAT] shim $name install failed: ${it.message}") }
        }
    }

    /** Install the `xset` guest command (opens the in-terminal dashboard via OSC 5391) into [binDir].
     *  Shipped as an asset; refreshed every launch so it also lands on an already-provisioned rootfs.
     *  Non-fatal — a failure never blocks the terminal. */
    private fun installXsetCommand(ctx: Context, binDir: String) {
        runCatching {
            File(binDir).mkdirs()
            val f = File(binDir, "xset")
            ctx.assets.open("compat/xset").use { i -> f.outputStream().use { i.copyTo(it) } }
            Os.chmod(f.absolutePath, 0x1ED)   // 0755 = rwxr-xr-x
        }.onFailure { Log.w(TAG, "[XSET] command install failed: ${it.message}") }
    }

    private fun installBusyboxApplets(busybox: String, usrBin: String) {
        val applets = runCatching {
            val p = ProcessBuilder(busybox, "--list").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            out.filter { it.isNotBlank() }
        }.getOrElse {
            Log.w(TAG, "busybox --list failed, using core applet set", it)
            listOf("sh", "ash", "ls", "cat", "echo", "env", "pwd", "cd", "mkdir",
                   "rm", "cp", "mv", "ln", "chmod", "grep", "sed", "awk", "vi",
                   "ps", "kill", "uname", "id", "whoami", "clear", "date", "head", "tail")
        }
        for (a in applets) symlink(busybox, "$usrBin/$a")
        Log.i(TAG, "installed ${applets.size} busybox applet links")
    }

    private fun writeAshrc(home: String) {
        // BusyBox fallback (no rootfs): coloured '$' prompt + a whoami function presenting 'dracos',
        // so the normal-user contract (whoami=dracos, trailing '$') also holds without a Linux image.
        // No real /home/dracos exists on a non-rooted device, so HOME stays app-private here.
        val esc = "\u001B"
        val ashrc = buildString {
            append("export PS1=\"${esc}[35mdracos${esc}[0m:${esc}[36m\\w${esc}[0m\\\$ \"\n")
            append("whoami() { echo dracos; }\n")
        }
        runCatching { File(home, ".ashrc").writeText(ashrc) }
    }

    private fun symlink(target: String, linkPath: String) {
        val f = File(linkPath)
        if (f.exists()) f.delete()
        runCatching { Os.symlink(target, linkPath) }
            .onFailure { Log.w(TAG, "symlink $linkPath -> $target failed: ${it.message}") }
    }
}
