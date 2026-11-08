package com.lazybody.linkdisk

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lazybody.linkdisk.data.LinkDiskPreferences
import com.lazybody.linkdisk.databinding.ActivityMainBinding
import com.lazybody.linkdisk.server.FrpcConfigInspector
import com.lazybody.linkdisk.server.FrpcInspectionResult
import com.lazybody.linkdisk.server.FrpcTunnelCandidate
import com.lazybody.linkdisk.server.PublicStatus
import com.lazybody.linkdisk.server.ServerState
import com.lazybody.linkdisk.server.ServerStatus
import com.lazybody.linkdisk.server.TunnelConfig
import com.lazybody.linkdisk.server.WebDavService
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: LinkDiskPreferences
    private var currentState = ServerState()
    private var tunnelCandidates: List<FrpcTunnelCandidate> = emptyList()
    private var selectedTunnel: FrpcTunnelCandidate? = null
    private var selectionRequired = false
    private var changingConfig = false
    private var applyingTunnel = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) persistFolder(uri)
    }

    private val configPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfig(uri)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { WebDavService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = LinkDiskPreferences(this)

        binding.portInput.setText(preferences.port.toString())
        binding.usernameInput.setText(preferences.username)
        binding.passwordInput.setText(preferences.password)
        binding.publicAccessSwitch.isChecked = preferences.publicAccessEnabled
        binding.frpcConfigInput.setText(preferences.frpcConfig)
        binding.publicUrlInput.setText(preferences.publicUrl)
        renderFolder()
        bindActions()
        restoreConfigInspection()
        updatePublicInputs(busy = false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WebDavService.state.collect { renderState(it) }
            }
        }
    }

    override fun onStop() {
        saveSettings(showErrors = false)
        super.onStop()
    }

    private fun bindActions() {
        binding.chooseFolderButton.setOnClickListener {
            folderPicker.launch(preferences.sharedTreeUri?.let(Uri::parse))
        }
        binding.serverButton.setOnClickListener {
            if (currentState.status == ServerStatus.RUNNING) {
                WebDavService.stop(this)
            } else {
                prepareAndStart()
            }
        }
        binding.publicAccessSwitch.setOnCheckedChangeListener { _, _ ->
            updatePublicInputs(
                busy = currentState.status == ServerStatus.STARTING ||
                    currentState.status == ServerStatus.RUNNING,
            )
            renderPublicState(currentState)
        }
        binding.openChmlfrpButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHMLFRP_PANEL_URL)))
        }
        binding.pasteConfigButton.setOnClickListener { pasteConfig() }
        binding.importConfigButton.setOnClickListener { configPicker.launch(arrayOf("*/*")) }
        binding.selectTunnelButton.setOnClickListener { showTunnelPicker() }
        binding.testPublicButton.setOnClickListener {
            if (selectionRequired && selectedTunnel == null) {
                Toast.makeText(this, R.string.config_candidate_required, Toast.LENGTH_SHORT).show()
                showTunnelPicker()
            } else {
                binding.publicAccessSwitch.isChecked = true
                prepareAndStart()
            }
        }
        binding.advancedConfigToggle.setOnClickListener {
            setAdvancedConfigExpanded(binding.advancedConfigContent.visibility != View.VISIBLE)
        }
        binding.frpcConfigInput.doAfterTextChanged {
            if (!changingConfig) clearInspection()
        }
        binding.portInput.doAfterTextChanged {
            if (!applyingTunnel) selectionRequired = false
        }
        binding.publicUrlInput.doAfterTextChanged {
            if (!applyingTunnel) selectionRequired = false
        }
        binding.copyAddressButton.setOnClickListener { copyAddress(currentState.url) }
        binding.copyPublicAddressButton.setOnClickListener { copyAddress(currentState.publicUrl) }
    }

    private fun pasteConfig() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        val rawConfig = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (rawConfig.isBlank()) {
            Toast.makeText(this, R.string.clipboard_config_empty, Toast.LENGTH_SHORT).show()
            return
        }
        applyImportedConfig(rawConfig)
    }

    private fun importConfig(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use(::readConfigBytes)
                ?: throw ConfigImportException(R.string.config_import_failed)
            val rawConfig = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw ConfigImportException(R.string.config_import_encoding)
            }
            applyImportedConfig(rawConfig)
        } catch (error: ConfigImportException) {
            Toast.makeText(this, error.messageResource, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.config_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun readConfigBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() <= MAX_CONFIG_BYTES) {
            val remaining = MAX_CONFIG_BYTES + 1 - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        if (output.size() > MAX_CONFIG_BYTES) {
            throw ConfigImportException(R.string.config_import_too_large)
        }
        return output.toByteArray()
    }

    private fun applyImportedConfig(rawConfig: String) {
        changingConfig = true
        try {
            binding.frpcConfigInput.setText(rawConfig)
        } finally {
            changingConfig = false
        }
        renderInspectionResult(FrpcConfigInspector.inspect(rawConfig), fromImport = true)
    }

    private fun restoreConfigInspection() {
        val rawConfig = binding.frpcConfigInput.text?.toString().orEmpty()
        if (rawConfig.isBlank()) return
        renderInspectionResult(FrpcConfigInspector.inspect(rawConfig), fromImport = false)
    }

    private fun renderInspectionResult(result: FrpcInspectionResult, fromImport: Boolean) {
        when (result) {
            is FrpcInspectionResult.Failure -> {
                tunnelCandidates = emptyList()
                selectedTunnel = null
                selectionRequired = false
                binding.selectTunnelButton.visibility = View.GONE
                binding.configInspectionSummary.setText(
                    getString(R.string.config_recognition_failed, result.message),
                )
                binding.configInspectionSummary.setTextColor(
                    ContextCompat.getColor(this, R.color.dot_error),
                )
            }
            is FrpcInspectionResult.Success -> {
                tunnelCandidates = result.candidates
                selectedTunnel = null
                binding.selectTunnelButton.visibility = if (tunnelCandidates.size > 1) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                binding.selectTunnelButton.setText(R.string.choose_tunnel)
                when {
                    tunnelCandidates.size == 1 && fromImport -> applyTunnel(tunnelCandidates.single())
                    tunnelCandidates.size == 1 -> {
                        selectedTunnel = tunnelCandidates.single()
                        renderTunnelSummary(tunnelCandidates.single())
                    }
                    else -> {
                        selectionRequired = fromImport
                        binding.configInspectionSummary.setText(
                            getString(R.string.config_candidates_found, tunnelCandidates.size),
                        )
                        binding.configInspectionSummary.setTextColor(
                            ContextCompat.getColor(this, R.color.text_secondary),
                        )
                    }
                }
            }
        }
        updatePublicInputs(
            busy = currentState.status == ServerStatus.STARTING ||
                currentState.status == ServerStatus.RUNNING,
        )
    }

    private fun clearInspection() {
        tunnelCandidates = emptyList()
        selectedTunnel = null
        selectionRequired = false
        binding.selectTunnelButton.visibility = View.GONE
        binding.configInspectionSummary.setText(R.string.config_not_recognized)
        binding.configInspectionSummary.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        binding.frpcConfigInputLayout.error = null
    }

    private fun showTunnelPicker() {
        if (tunnelCandidates.isEmpty()) return
        val labels = Array<CharSequence>(tunnelCandidates.size) { index ->
            val candidate = tunnelCandidates[index]
            getString(
                R.string.tunnel_option,
                candidate.name,
                candidate.type,
                candidate.localPort,
                candidate.publicUrl,
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_tunnel_title)
            .setSingleChoiceItems(labels, tunnelCandidates.indexOf(selectedTunnel)) { dialog, index ->
                applyTunnel(tunnelCandidates[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTunnel(candidate: FrpcTunnelCandidate) {
        applyingTunnel = true
        try {
            binding.portInput.setText(candidate.localPort.toString())
            binding.publicUrlInput.setText(candidate.publicUrl)
        } finally {
            applyingTunnel = false
        }
        selectedTunnel = candidate
        selectionRequired = false
        binding.portInputLayout.error = null
        binding.publicUrlInputLayout.error = null
        binding.frpcConfigInputLayout.error = null
        binding.selectTunnelButton.setText(R.string.rechoose_tunnel)
        renderTunnelSummary(candidate)
    }

    private fun renderTunnelSummary(candidate: FrpcTunnelCandidate) {
        binding.configInspectionSummary.setText(
            getString(
                R.string.config_recognized,
                candidate.name,
                candidate.type,
                candidate.localPort,
                candidate.publicUrl,
            ),
        )
        binding.configInspectionSummary.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )
    }

    private fun setAdvancedConfigExpanded(expanded: Boolean) {
        binding.advancedConfigContent.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.advancedConfigToggle.setText(
            if (expanded) R.string.collapse_advanced_config else R.string.expand_advanced_config,
        )
    }

    private fun copyAddress(address: String) {
        if (address.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), address),
        )
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun persistFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            val previous = preferences.sharedTreeUri
            if (!previous.isNullOrBlank() && previous != uri.toString()) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags)
                }
            }
            preferences.sharedTreeUri = uri.toString()
            renderFolder()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.folder_permission_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderFolder() {
        val tree = preferences.sharedTreeUri
        if (tree.isNullOrBlank()) {
            binding.folderName.setText(R.string.folder_not_selected)
            binding.folderUri.setText(R.string.folder_hint)
            binding.chooseFolderButton.setText(R.string.choose_folder)
            return
        }
        val uri = Uri.parse(tree)
        binding.folderName.text = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment
            ?: getString(R.string.folder_not_selected)
        binding.folderUri.text = uri.path ?: tree
        binding.chooseFolderButton.setText(R.string.rechoose_folder)
    }

    private fun prepareAndStart() {
        if (preferences.sharedTreeUri.isNullOrBlank()) {
            Toast.makeText(this, R.string.select_folder_first, Toast.LENGTH_SHORT).show()
            folderPicker.launch(null)
            return
        }
        if (!saveSettings(showErrors = true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WebDavService.start(this)
        }
    }

    private fun saveSettings(showErrors: Boolean): Boolean {
        val port = binding.portInput.text?.toString()?.toIntOrNull()
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val publicEnabled = binding.publicAccessSwitch.isChecked
        val validPort = port != null && port in 1024..65535
        val usernameError = when {
            username.contains(':') -> getString(R.string.invalid_username)
            publicEnabled && username.isEmpty() -> getString(R.string.public_username_required)
            else -> null
        }
        val passwordError = when {
            username.isNotEmpty() && password.isEmpty() -> getString(R.string.invalid_password)
            publicEnabled && password.length < MIN_PUBLIC_PASSWORD_LENGTH ->
                getString(R.string.public_password_required)
            else -> null
        }
        if (showErrors) {
            binding.portInputLayout.error = if (validPort) null else getString(R.string.invalid_port)
            binding.usernameInputLayout.error = usernameError
            binding.passwordInputLayout.error = passwordError
        }
        if (!validPort || usernameError != null || passwordError != null) return false

        val config = TunnelConfig(
            localPort = port!!,
            publicUrl = binding.publicUrlInput.text?.toString()?.trim().orEmpty(),
            rawConfig = binding.frpcConfigInput.text?.toString().orEmpty(),
        )
        val configError = if (publicEnabled) {
            runCatching { config.validate() }.exceptionOrNull()
        } else {
            null
        }
        if (showErrors) {
            val message = configError?.message
            if (message != null) setAdvancedConfigExpanded(true)
            binding.publicUrlInputLayout.error = message?.takeIf { it.contains("URL") }
            binding.frpcConfigInputLayout.error = message?.takeUnless { it.contains("URL") }
        }
        if (configError != null) return false

        preferences.username = username
        preferences.password = password
        preferences.tunnelConfig = config
        preferences.publicAccessEnabled = publicEnabled
        return true
    }

    private fun renderState(state: ServerState) {
        currentState = state
        val busy = state.status == ServerStatus.STARTING || state.status == ServerStatus.RUNNING
        binding.portInput.isEnabled = !busy
        binding.usernameInput.isEnabled = !busy
        binding.passwordInput.isEnabled = !busy
        binding.chooseFolderButton.isEnabled = !busy
        binding.publicAccessSwitch.isEnabled = !busy
        updatePublicInputs(busy)
        binding.serverButton.isEnabled = state.status != ServerStatus.STARTING
        binding.copyAddressButton.isEnabled = state.status == ServerStatus.RUNNING && state.url.isNotEmpty()

        val title: Int
        val dotColor: Int
        val cardColor: Int
        when (state.status) {
            ServerStatus.STOPPED -> {
                title = R.string.status_stopped
                dotColor = R.color.dot_stopped
                cardColor = R.color.status_stopped
            }
            ServerStatus.STARTING -> {
                title = R.string.status_starting
                dotColor = R.color.dot_starting
                cardColor = R.color.status_starting
            }
            ServerStatus.RUNNING -> {
                title = R.string.status_running
                dotColor = R.color.dot_running
                cardColor = R.color.status_running
            }
            ServerStatus.ERROR -> {
                title = R.string.status_error
                dotColor = R.color.dot_error
                cardColor = R.color.status_error
            }
        }
        binding.statusTitle.setText(title)
        binding.statusDot.setTextColor(ContextCompat.getColor(this, dotColor))
        binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(this, cardColor))
        binding.serverButton.setText(
            when (state.status) {
                ServerStatus.RUNNING -> R.string.stop_server
                ServerStatus.STARTING -> R.string.status_starting
                else -> R.string.start_server
            },
        )
        binding.serverAddress.text = when {
            state.url.isNotEmpty() -> state.url
            state.status == ServerStatus.RUNNING -> getString(R.string.waiting_ip)
            else -> getString(R.string.address_hint)
        }
        binding.serverError.visibility = if (state.status == ServerStatus.ERROR) View.VISIBLE else View.GONE
        binding.serverError.text = state.error
        renderPublicState(state)
    }

    private fun updatePublicInputs(busy: Boolean) {
        val enabled = !busy && binding.publicAccessSwitch.isChecked
        binding.frpcConfigInput.isEnabled = enabled
        binding.publicUrlInput.isEnabled = enabled
        binding.pasteConfigButton.isEnabled = enabled
        binding.importConfigButton.isEnabled = enabled
        binding.selectTunnelButton.isEnabled = enabled && tunnelCandidates.size > 1
        binding.testPublicButton.isEnabled = !busy
    }

    private fun renderPublicState(state: ServerState) {
        binding.publicStatusText.setText(
            when (state.publicStatus) {
                PublicStatus.OFF -> if (binding.publicAccessSwitch.isChecked) {
                    R.string.public_enabled_pending
                } else {
                    R.string.public_off
                }
                PublicStatus.STARTING -> R.string.public_starting
                PublicStatus.RUNNING -> R.string.public_running
                PublicStatus.RECONNECTING -> R.string.public_reconnecting
                PublicStatus.ERROR -> R.string.public_error
            },
        )
        val showAddress = state.publicStatus != PublicStatus.OFF && state.publicUrl.isNotEmpty()
        binding.publicAddress.visibility = if (showAddress) View.VISIBLE else View.GONE
        binding.publicAddress.text = state.publicUrl
        binding.copyPublicAddressButton.isEnabled =
            state.publicStatus == PublicStatus.RUNNING && state.publicUrl.isNotEmpty()
        val showError = state.publicError.isNotEmpty() &&
            (state.publicStatus == PublicStatus.ERROR || state.publicStatus == PublicStatus.RECONNECTING)
        binding.publicError.visibility = if (showError) View.VISIBLE else View.GONE
        binding.publicError.text = state.publicError
    }

    private class ConfigImportException(val messageResource: Int) : Exception()

    private companion object {
        const val CHMLFRP_PANEL_URL = "https://panel.chmlfrp.net/"
        const val MIN_PUBLIC_PASSWORD_LENGTH = 16
        const val MAX_CONFIG_BYTES = 256 * 1024
    }
}
