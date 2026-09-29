package com.example.aikeyboardoverlay

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.aikeyboardoverlay.accessibility.TextEditorAccessibilityService
import com.example.aikeyboardoverlay.common.AppBroadcasts
import com.example.aikeyboardoverlay.databinding.ActivityMainBinding
import com.example.aikeyboardoverlay.overlay.FloatingUIService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val localBroadcastManager by lazy { LocalBroadcastManager.getInstance(this) }

    private val modelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                AppBroadcasts.ACTION_MODEL_STATE_CHANGED -> {
                    setModelActive(intent.getBooleanExtra(AppBroadcasts.EXTRA_MODEL_READY, false))
                }
                AppBroadcasts.ACTION_MODEL_TEST_RESULT -> {
                    binding.etModelOutput.setText(intent.getStringExtra(AppBroadcasts.EXTRA_OUTPUT).orEmpty())
                    binding.btnModelEnter.isEnabled = true
                }
            }
        }
    }

    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStatus()
        }

    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRequestOverlay.setOnClickListener {
            requestOverlayPermission()
        }
        binding.btnRequestAccessibility.setOnClickListener {
            requestAccessibilityPermission()
        }
        binding.btnStartOverlay.setOnClickListener {
            if (hasOverlayPermission() && isAccessibilityServiceEnabled()) {
                val serviceIntent = Intent(this, FloatingUIService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "Please grant both permissions first", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, FloatingUIService::class.java))
        }
        binding.btnModelEnter.setOnClickListener {
            val prompt = binding.etModelInput.text.toString().trim()
            if (prompt.isEmpty()) {
                binding.etModelOutput.setText("Enter text first")
                return@setOnClickListener
            }
            binding.btnModelEnter.isEnabled = false
            binding.etModelOutput.setText("Processing...")
            localBroadcastManager.sendBroadcast(
                Intent(AppBroadcasts.ACTION_MODEL_TEST_REQUEST)
                    .putExtra(AppBroadcasts.EXTRA_PROMPT, prompt)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        localBroadcastManager.registerReceiver(
            modelReceiver,
            android.content.IntentFilter().apply {
                addAction(AppBroadcasts.ACTION_MODEL_STATE_CHANGED)
                addAction(AppBroadcasts.ACTION_MODEL_TEST_RESULT)
            }
        )
        localBroadcastManager.sendBroadcast(Intent(AppBroadcasts.ACTION_MODEL_STATE_QUERY))
    }

    override fun onStop() {
        localBroadcastManager.unregisterReceiver(modelReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        val overlayGranted = hasOverlayPermission()
        val accessibilityGranted = isAccessibilityServiceEnabled()

        binding.tvOverlayStatus.text =
            "Overlay Permission: ${if (overlayGranted) "Granted ✅" else "Not granted ❌"}"
        binding.tvAccessibilityStatus.text =
            "Accessibility Permission: ${if (accessibilityGranted) "Granted ✅" else "Not granted ❌"}"
        binding.btnRequestOverlay.isEnabled = !overlayGranted
        binding.btnRequestAccessibility.isEnabled = !accessibilityGranted
        binding.btnStartOverlay.isEnabled = overlayGranted && accessibilityGranted
    }

    private fun setModelActive(active: Boolean) {
        binding.tvModelStatus.text =
            if (active) "Model status: Active ✅" else "Model status: Not active ❌"
        binding.tvModelHint.text =
            if (active) "Model loaded. Enter text below to test it."
            else "Model is not loaded. Enable the accessibility service and wait for loading."
        binding.modelControls.visibility =
            if (active) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsLauncher.launch(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, TextEditorAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val enabledComponentName =
                ComponentName.unflattenFromString(colonSplitter.next())
            if (enabledComponentName == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(
            this,
            "Find and enable '${getString(R.string.accessibility_service_label)}' in the list",
            Toast.LENGTH_LONG
        ).show()
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
