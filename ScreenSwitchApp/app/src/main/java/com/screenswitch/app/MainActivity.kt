// [Handled & Written by Cloud Agent]
package com.screenswitch.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.screenswitch.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var controller: ScreenController

    private var suppressTimeoutCallback = false
    private var pendingTimeoutSeconds: Int? = null

    private val writeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = pendingTimeoutSeconds
        pendingTimeoutSeconds = null
        if (pending != null && Settings.System.canWrite(this)) {
            applyTimeout(pending)
        } else {
            syncTimeoutFromSystem()
            render()
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAdminState()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, ScreenAdminReceiver::class.java)

        val restoredKeepAwake = savedInstanceState?.getBoolean(KEY_KEEP_AWAKE) ?: false
        controller = ScreenController(
            ScreenState(
                keepAwake = restoredKeepAwake,
                timeoutSeconds = readSystemTimeoutSeconds() ?: ScreenState().timeoutSeconds
            )
        )

        if (restoredKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupUi()
        render()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminState()
        syncTimeoutFromSystem()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_KEEP_AWAKE, controller.state.keepAwake)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun setupUi() {
        binding.switchKeepAwake.setOnCheckedChangeListener { _, isChecked ->
            controller.setKeepAwake(isChecked).forEach(::executeCommand)
            render()
        }

        binding.timeoutGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressTimeoutCallback) return@addOnButtonCheckedListener
            val seconds = when (checkedId) {
                R.id.btnTimeout15 -> 15
                R.id.btnTimeout30 -> 30
                R.id.btnTimeout60 -> 60
                R.id.btnTimeout120 -> 120
                else -> return@addOnButtonCheckedListener
            }
            requestTimeoutChange(seconds)
        }

        binding.btnLockScreen.setOnClickListener {
            controller.requestLockScreen().forEach(::executeCommand)
        }
    }

    private fun requestTimeoutChange(seconds: Int) {
        if (Settings.System.canWrite(this)) {
            applyTimeout(seconds)
        } else {
            pendingTimeoutSeconds = seconds
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            )
            writeSettingsLauncher.launch(intent)
        }
    }

    private fun applyTimeout(seconds: Int) {
        try {
            controller.setScreenTimeout(seconds).forEach(::executeCommand)
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun executeCommand(command: ScreenCommand) {
        when (command) {
            is ScreenCommand.SetKeepAwake -> {
                if (command.enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            is ScreenCommand.SetTimeout -> {
                try {
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        command.seconds * 1000
                    )
                    Toast.makeText(
                        this,
                        getString(R.string.timeout_applied, command.seconds),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (error: SecurityException) {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                }
            }

            ScreenCommand.LockNow -> devicePolicyManager.lockNow()

            ScreenCommand.RequestDeviceAdmin -> requestDeviceAdmin()
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        adminLauncher.launch(intent)
    }

    private fun readSystemTimeoutSeconds(): Int? {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000
        } catch (error: Settings.SettingNotFoundException) {
            null
        } catch (error: SecurityException) {
            null
        }
    }

    private fun syncTimeoutFromSystem() {
        readSystemTimeoutSeconds()?.let { controller.setScreenTimeout(it) }
    }

    private fun refreshAdminState() {
        controller.setDeviceAdminActive(devicePolicyManager.isAdminActive(adminComponent))
    }

    private fun render() {
        val state = controller.state

        if (binding.switchKeepAwake.isChecked != state.keepAwake) {
            binding.switchKeepAwake.isChecked = state.keepAwake
        }

        binding.textPrimaryStatus.text = getString(
            if (state.keepAwake) R.string.status_awake else R.string.status_normal
        )
        binding.textTimeoutStatus.text = getString(R.string.status_timeout, state.timeoutSeconds)
        binding.textAdminStatus.text = getString(
            if (state.deviceAdminActive) R.string.status_admin_on else R.string.status_admin_off
        )
        binding.btnLockScreen.text = getString(
            if (state.deviceAdminActive) R.string.action_lock_now else R.string.action_enable_admin
        )

        val selectedId = when (state.timeoutSeconds) {
            15 -> R.id.btnTimeout15
            30 -> R.id.btnTimeout30
            60 -> R.id.btnTimeout60
            120 -> R.id.btnTimeout120
            else -> -1
        }

        suppressTimeoutCallback = true
        if (selectedId == -1) {
            binding.timeoutGroup.clearChecked()
        } else {
            binding.timeoutGroup.check(selectedId)
        }
        suppressTimeoutCallback = false
    }

    companion object {
        private const val KEY_KEEP_AWAKE = "keep_awake"
    }
}
