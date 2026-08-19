package com.dracxterm.rootfs

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dracxterm.MainActivity
import com.dracxterm.R
import com.dracxterm.databinding.ActivityProvisioningBinding
import kotlin.concurrent.thread

/**
 * Launcher activity. Runs the RootFS provisioning pipeline (first run only) behind a
 * splash/progress screen, then hands off to the terminal. Blocks the back gesture and
 * user interaction while provisioning is in flight.
 *
 * Storage/media permission is deliberately NOT requested here. Per product decision the app must
 * never surface a permission dialog at startup — storage access is opt-in and only requested when
 * the user chooses Settings ▸ Storage Access ▸ Grant (handled in MainActivity/xset).
 */
class ProvisioningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisioningBinding
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(ShellLocator.TAG, "[BOOT] ProvisioningActivity started")
        binding = ActivityProvisioningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.retry.visibility = View.GONE

        // Prevent leaving mid-provision.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow while busy */ if (!busy) finish() }
        })

        // Boot straight into provisioning — no startup permission prompt.
        startProvisioning()
    }

    private fun startProvisioning() {
        busy = true
        binding.retry.visibility = View.GONE
        binding.status.text = getString(R.string.provision_starting)
        thread(name = "provisioning") {
            val outcome = BootManager(this).boot { _, message, percent ->
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
            main.post { finishBoot(outcome) }
        }
    }

    private fun finishBoot(outcome: BootManager.Outcome) {
        busy = false
        when (outcome.mode) {
            BootManager.BootMode.LINUX, BootManager.BootMode.BUSYBOX -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            BootManager.BootMode.ERROR -> {
                binding.progress.isIndeterminate = false
                binding.progress.progress = 0
                binding.status.text = getString(R.string.provision_failed, outcome.message)
                binding.retry.visibility = View.VISIBLE
                binding.retry.setOnClickListener { startProvisioning() }
            }
        }
    }
}
