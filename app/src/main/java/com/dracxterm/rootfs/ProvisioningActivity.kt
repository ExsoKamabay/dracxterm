package com.dracxterm.rootfs

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dracxterm.MainActivity
import com.dracxterm.R
import com.dracxterm.databinding.ActivityProvisioningBinding
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Launcher activity. Runs the RootFS provisioning pipeline (first run only) behind a
 * splash/progress screen, then hands off to the terminal. Blocks the back gesture and
 * user interaction while provisioning is in flight.
 *
 * Storage/media permission is deliberately NOT requested here. Per product decision the app must
 * never surface a permission dialog at startup — storage access is opt-in and only requested when
 * the user chooses Settings ▸ Storage Access ▸ Grant (handled in MainActivity/xset).
 *
 * The Linux image is handled the same way. Nothing is fetched over the network unless the
 * user presses the download button on the consent panel; declining is a first-class
 * outcome that leads to a working BusyBox terminal, not to an error state.
 */
class ProvisioningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisioningBinding
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = true
    private val cancelRequested = AtomicBoolean(false)

    private val state by lazy { ProvisioningState(this) }
    private val downloader by lazy { RootfsDownloader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(ShellLocator.TAG, "[BOOT] ProvisioningActivity started")
        binding = ActivityProvisioningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideAllControls()

        // Prevent leaving mid-provision. While the consent panel is up the user is not
        // blocked: back means "not now", which is the same as continuing with BusyBox.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.consent.visibility == View.VISIBLE -> declineAndContinue(persist = false)
                    !busy -> finish()
                    else -> { /* swallow while working */ }
                }
            }
        })

        startProvisioning()
    }

    // ------------------------------------------------------------------ pipeline

    private fun startProvisioning() {
        busy = true
        hideAllControls()
        binding.status.text = getString(R.string.provision_starting)
        binding.progress.visibility = View.VISIBLE
        thread(name = "provisioning") {
            val outcome = BootManager(this).boot(stageListener())
            main.post { finishBoot(outcome) }
        }
    }

    private fun stageListener() = BootManager.Listener { _, message, percent ->
        main.post {
            binding.status.text = message
            if (percent < 0) {
                binding.progress.isIndeterminate = true
            } else {
                binding.progress.isIndeterminate = false
                binding.progress.progress = percent.coerceIn(0, 100)
            }
        }
    }

    private fun finishBoot(outcome: BootManager.Outcome) {
        busy = false
        when (outcome.mode) {
            BootManager.BootMode.LINUX, BootManager.BootMode.BUSYBOX -> launchTerminal()
            BootManager.BootMode.NEEDS_IMAGE -> showConsent()
            BootManager.BootMode.ERROR -> showError(getString(R.string.provision_failed, outcome.message))
        }
    }

    private fun launchTerminal() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ------------------------------------------------------------------ consent

    private fun showConsent() {
        val entry = RootfsCatalog.default()
        val partial = downloader.partialBytes(entry)

        hideAllControls()
        binding.status.text = ""
        binding.consent.visibility = View.VISIBLE

        if (partial > 0L) {
            binding.consentBody.text = getString(
                R.string.consent_body_resume,
                entry.label,
                Formatter.formatShortFileSize(this, partial),
                RootfsCatalog.host(entry)
            )
            binding.consentDownload.setText(R.string.consent_download_resume)
        } else {
            binding.consentBody.text = getString(
                R.string.consent_body,
                entry.label,
                Formatter.formatShortFileSize(this, entry.approxBytes),
                RootfsCatalog.host(entry),
                Formatter.formatShortFileSize(this, entry.approxBytes + entry.approxExtractedBytes)
            )
            binding.consentDownload.setText(R.string.consent_download)
        }

        binding.consentDownload.setOnClickListener {
            if (!hasNetwork()) {
                binding.status.text = getString(R.string.consent_no_network)
                return@setOnClickListener
            }
            startDownload(entry)
        }
        binding.consentSkip.setOnClickListener { declineAndContinue(persist = true) }
    }

    /**
     * The user chose not to fetch an image. [persist] records the choice so the offer is
     * not repeated on every launch; a cancelled download passes false, because cancelling
     * a transfer is not the same as saying no.
     */
    private fun declineAndContinue(persist: Boolean) {
        if (persist) state.imageOfferDeclined = true
        hideAllControls()
        binding.status.text = getString(R.string.consent_starting_busybox)
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        launchTerminal()
    }

    // ------------------------------------------------------------------ download

    private fun startDownload(entry: RootfsCatalog.Entry) {
        busy = true
        cancelRequested.set(false)
        hideAllControls()
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        binding.status.text = getString(R.string.consent_downloading_unknown, entry.label, "0 B")
        binding.cancel.visibility = View.VISIBLE
        binding.cancel.isEnabled = true
        binding.cancel.setText(R.string.consent_cancel)
        binding.cancel.setOnClickListener {
            cancelRequested.set(true)
            binding.cancel.isEnabled = false
        }

        thread(name = "rootfs-download") {
            val result = downloader.download(
                entry,
                { downloaded, total -> main.post { renderDownloadProgress(entry, downloaded, total) } },
                { cancelRequested.get() }
            )
            main.post { onDownloadFinished(entry, result) }
        }
    }

    private fun renderDownloadProgress(entry: RootfsCatalog.Entry, downloaded: Long, total: Long) {
        if (total > 0L) {
            binding.progress.isIndeterminate = false
            binding.progress.progress = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            binding.status.text = getString(
                R.string.consent_downloading,
                entry.label,
                Formatter.formatShortFileSize(this, downloaded),
                Formatter.formatShortFileSize(this, total)
            )
        } else {
            binding.progress.isIndeterminate = true
            binding.status.text = getString(
                R.string.consent_downloading_unknown,
                entry.label,
                Formatter.formatShortFileSize(this, downloaded)
            )
        }
    }

    private fun onDownloadFinished(entry: RootfsCatalog.Entry, result: RootfsDownloader.Result) {
        binding.cancel.visibility = View.GONE
        binding.cancel.isEnabled = true
        when (result) {
            is RootfsDownloader.Result.Cancelled -> {
                busy = false
                binding.status.text = getString(R.string.consent_cancelled)
                // The partial file is kept; the next launch offers to resume.
                main.postDelayed({ declineAndContinue(persist = false) }, 900L)
            }
            is RootfsDownloader.Result.Failed -> {
                busy = false
                // Retry restarts the pipeline, which lands back on the consent panel with
                // the "resume" wording when a partial file survived the failure. A failed
                // download must never trap the user: BusyBox is still there, so offer it.
                showError(getString(R.string.consent_download_failed, result.reason), allowBusybox = true)
            }
            is RootfsDownloader.Result.Ok -> extractDownloaded(entry, result.file)
        }
    }

    private fun extractDownloaded(entry: RootfsCatalog.Entry, file: File) {
        val archive = RootfsArchive.classify(file.name, RootfsArchive.Source.LocalFile(file))
        if (archive == null) {
            busy = false
            showError(getString(R.string.provision_failed, "unrecognised archive ${file.name}"))
            return
        }
        binding.progress.isIndeterminate = true
        binding.status.text = getString(R.string.provision_starting)
        thread(name = "provisioning-downloaded") {
            val outcome = BootManager(this).provision(archive, stageListener())
            main.post { finishBoot(outcome) }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * [allowBusybox] adds a way out that does not require the failure to be fixed. It is
     * offered for download failures, where a perfectly good BusyBox terminal is waiting,
     * and withheld for provisioning failures, where the sandbox may be half-written and
     * launching the terminal would hide a real problem.
     */
    private fun showError(message: String, allowBusybox: Boolean = false) {
        busy = false
        binding.progress.isIndeterminate = false
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        binding.status.text = message
        binding.consent.visibility = View.GONE
        binding.retry.visibility = View.VISIBLE
        binding.retry.setOnClickListener { startProvisioning() }
        if (allowBusybox) {
            binding.cancel.visibility = View.VISIBLE
            binding.cancel.isEnabled = true
            binding.cancel.setText(R.string.consent_skip)
            binding.cancel.setOnClickListener { declineAndContinue(persist = false) }
        }
    }

    private fun hideAllControls() {
        binding.retry.visibility = View.GONE
        binding.cancel.visibility = View.GONE
        binding.consent.visibility = View.GONE
        binding.progress.visibility = View.GONE
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
