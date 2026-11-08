package com.linkdisk.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.linkdisk.app.data.LinkDiskPreferences
import com.linkdisk.app.data.StorageAccess
import com.linkdisk.app.databinding.ActivityMainBinding
import com.linkdisk.app.server.FrpcConfigInspector
import com.linkdisk.app.server.FrpcInspectionResult
import com.linkdisk.app.server.FrpcTunnelCandidate
import com.linkdisk.app.server.PublicStatus
import com.linkdisk.app.server.ServerState
import com.linkdisk.app.server.ServerStatus
import com.linkdisk.app.server.TunnelConfig
import com.linkdisk.app.server.WebDavMountGuide
import com.linkdisk.app.server.WebDavService
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: LinkDiskPreferences
    private var currentState = ServerState()
    private var tunnelCandidates: List<FrpcTunnelCandidate> = emptyList()
    private var selectedTunnel: FrpcTunnelCandidate? = null
    private var selectionRequired = false
    private var changingConfig = false
    private var applyingTunnel = false
    private var currentMountGuide: WebDavMountGuide? = null
    private var expandedGuidePlatform: GuidePlatform? = null
    private var pendingStorageAction = PendingStorageAction.NONE

    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onStoragePermissionResult() }

    private val allFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onStoragePermissionResult() }

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
        renderMountGuide()

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

    override fun onResume() {
        super.onResume()
        renderFolder()
    }

    private fun bindActions() {
        binding.shareRootButton.setOnClickListener { showRootWarning() }
        binding.pickFolderButton.setOnClickListener { pickSharedFolder() }
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
        binding.developerGithubButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
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
        binding.guideSourceGroup.check(R.id.guideLocalSourceButton)
        binding.guideSourceGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) renderMountGuide()
        }
        binding.guideWindowsToggle.setOnClickListener { toggleGuidePlatform(GuidePlatform.WINDOWS) }
        binding.guideMacosToggle.setOnClickListener { toggleGuidePlatform(GuidePlatform.MACOS) }
        binding.guideLinuxToggle.setOnClickListener { toggleGuidePlatform(GuidePlatform.LINUX) }
        binding.copyGuideRootButton.setOnClickListener {
            copyAddress(currentMountGuide?.rootUrl.orEmpty())
        }
        binding.copyGuideWindowsAddressButton.setOnClickListener {
            copyAddress(currentMountGuide?.rootUrl.orEmpty())
        }
        binding.copyGuideWindowsUncButton.setOnClickListener {
            copyAddress(currentMountGuide?.windowsUnc.orEmpty())
        }
        binding.copyGuideMacosAddressButton.setOnClickListener {
            copyAddress(currentMountGuide?.rootUrl.orEmpty())
        }
        binding.copyGuideLinuxAddressButton.setOnClickListener {
            copyAddress(currentMountGuide?.linuxUrl.orEmpty())
        }
        setGuidePlatformExpanded(null)
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

    private enum class PendingStorageAction { NONE, SHARE_ROOT, PICK_FOLDER }

    private fun showRootWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_warning_title)
            .setMessage(R.string.root_warning_message)
            .setPositiveButton(R.string.root_warning_confirm) { _, _ ->
                if (StorageAccess.hasAccess(this)) {
                    applyRootScope()
                } else {
                    pendingStorageAction = PendingStorageAction.SHARE_ROOT
                    requestStorageAccess()
                }
            }
            .setNegativeButton(R.string.root_warning_cancel) { _, _ ->
                Toast.makeText(this, R.string.root_warning_cancelled, Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun pickSharedFolder() {
        if (!StorageAccess.hasAccess(this)) {
            pendingStorageAction = PendingStorageAction.PICK_FOLDER
            requestStorageAccess()
        } else {
            showDirectoryPicker(StorageAccess.sharedRoot())
        }
    }

    private fun onStoragePermissionResult() {
        val action = pendingStorageAction
        pendingStorageAction = PendingStorageAction.NONE
        renderFolder()
        if (!StorageAccess.hasAccess(this)) return
        when (action) {
            PendingStorageAction.SHARE_ROOT -> applyRootScope()
            PendingStorageAction.PICK_FOLDER -> showDirectoryPicker(StorageAccess.sharedRoot())
            PendingStorageAction.NONE -> Unit
        }
    }

    private fun applyRootScope() {
        preferences.sharedDirectory = StorageAccess.sharedRoot().absolutePath
        renderFolder()
        Toast.makeText(this, R.string.root_scope_selected, Toast.LENGTH_LONG).show()
    }

    private fun showDirectoryPicker(start: File) {
        lateinit var show: (File) -> Unit
        show = { directory ->
            val root = StorageAccess.sharedRoot()
            val children = directory.listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.canRead() }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            val entries = buildList {
                if (directory != root) {
                    directory.parentFile?.let { add(getString(R.string.picker_parent) to it) }
                }
                children.forEach { add(it.name to it) }
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    directory.absolutePath
                        .removePrefix(root.absolutePath)
                        .ifBlank { getString(R.string.storage_root_name) },
                )
                .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                    show(entries[which].second)
                }
                .setPositiveButton(R.string.picker_select_current) { _, _ ->
                    if (directory.absolutePath == root.absolutePath) {
                        showRootWarning()
                    } else {
                        preferences.sharedDirectory = directory.absolutePath
                        renderFolder()
                    }
                }
                .setNegativeButton(R.string.root_warning_cancel, null)
                .show()
        }
        show(start)
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPermission = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            val opened = runCatching { allFilesAccess.launch(appPermission) }.isSuccess ||
                runCatching {
                    allFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.isSuccess
            if (!opened) {
                Toast.makeText(this, R.string.storage_permission_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun renderFolder() {
        val granted = StorageAccess.hasAccess(this)
        val selected = preferences.sharedDirectory?.let(::File)
        when {
            selected == null -> {
                binding.folderName.setText(R.string.folder_not_selected)
                binding.folderUri.setText(R.string.storage_permission_hint)
            }
            !StorageAccess.isWithinRoot(selected) || !selected.isDirectory -> {
                preferences.sharedDirectory = null
                binding.folderName.setText(R.string.folder_not_selected)
                binding.folderUri.setText(R.string.shared_directory_invalid)
            }
            selected.absolutePath == StorageAccess.sharedRoot().absolutePath -> {
                binding.folderName.setText(R.string.storage_root_name)
                binding.folderUri.text = getString(
                    R.string.storage_root_path,
                    selected.absolutePath,
                )
            }
            else -> {
                binding.folderName.text = selected.name
                binding.folderUri.text = getString(R.string.shared_folder_path, selected.absolutePath)
            }
        }
        binding.storagePermissionText.text = getString(
            if (granted) R.string.storage_permission_granted else R.string.storage_permission_required,
        )
        binding.storagePermissionText.setTextColor(
            ContextCompat.getColor(
                this,
                if (granted) R.color.text_secondary else R.color.text_primary,
            ),
        )
    }

    private fun prepareAndStart() {
        if (!StorageAccess.hasAccess(this)) {
            Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
            requestStorageAccess()
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
        binding.shareRootButton.isEnabled = !busy
        binding.pickFolderButton.isEnabled = !busy
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
        binding.publicConfigContent.visibility =
            if (binding.publicAccessSwitch.isChecked) View.VISIBLE else View.GONE
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
        renderMountGuide()
    }

    private fun renderMountGuide() {
        val publicGuide = WebDavMountGuide.fromRootUrl(currentState.publicUrl)
        val publicAvailable = binding.publicAccessSwitch.isChecked &&
            currentState.publicStatus != PublicStatus.OFF && publicGuide != null
        binding.guidePublicSourceButton.visibility = if (publicAvailable) View.VISIBLE else View.GONE
        if (!publicAvailable && binding.guideSourceGroup.checkedButtonId == R.id.guidePublicSourceButton) {
            binding.guideSourceGroup.check(R.id.guideLocalSourceButton)
        }

        val showPublic = publicAvailable &&
            binding.guideSourceGroup.checkedButtonId == R.id.guidePublicSourceButton
        val guide = if (showPublic) {
            publicGuide
        } else if (currentState.status == ServerStatus.RUNNING) {
            WebDavMountGuide.fromRootUrl(currentState.url)
        } else {
            null
        }
        currentMountGuide = guide

        binding.guideLocalDescription.visibility = if (showPublic) View.GONE else View.VISIBLE
        binding.guidePublicDescription.visibility = if (showPublic) View.VISIBLE else View.GONE
        val waiting = getString(R.string.guide_address_waiting)
        binding.guideRootAddress.text = guide?.rootUrl ?: waiting
        binding.guideWindowsAddress.text = guide?.rootUrl ?: waiting
        binding.guideWindowsUnc.text = guide?.windowsUnc ?: waiting
        binding.guideMacosAddress.text = guide?.rootUrl ?: waiting
        binding.guideLinuxAddress.text = guide?.linuxUrl ?: waiting
        val addressAvailable = guide != null
        binding.copyGuideRootButton.isEnabled = addressAvailable
        binding.copyGuideWindowsAddressButton.isEnabled = addressAvailable
        binding.copyGuideWindowsUncButton.isEnabled = addressAvailable
        binding.copyGuideMacosAddressButton.isEnabled = addressAvailable
        binding.copyGuideLinuxAddressButton.isEnabled = addressAvailable
    }

    private fun toggleGuidePlatform(platform: GuidePlatform) {
        setGuidePlatformExpanded(if (expandedGuidePlatform == platform) null else platform)
    }

    private fun setGuidePlatformExpanded(platform: GuidePlatform?) {
        expandedGuidePlatform = platform
        updateGuidePlatform(
            binding.guideWindowsToggle,
            binding.guideWindowsContent,
            R.string.guide_windows,
            platform == GuidePlatform.WINDOWS,
        )
        updateGuidePlatform(
            binding.guideMacosToggle,
            binding.guideMacosContent,
            R.string.guide_macos,
            platform == GuidePlatform.MACOS,
        )
        updateGuidePlatform(
            binding.guideLinuxToggle,
            binding.guideLinuxContent,
            R.string.guide_linux,
            platform == GuidePlatform.LINUX,
        )
    }

    private fun updateGuidePlatform(
        toggle: MaterialButton,
        content: View,
        titleResource: Int,
        expanded: Boolean,
    ) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.text = getString(
            if (expanded) R.string.guide_platform_expanded else R.string.guide_platform_collapsed,
            getString(titleResource),
        )
    }

    private enum class GuidePlatform {
        WINDOWS,
        MACOS,
        LINUX,
    }

    private class ConfigImportException(val messageResource: Int) : Exception()

    private companion object {
        const val PROJECT_URL = "https://github.com/Lensi-zhang/LinkDisk"
        const val MIN_PUBLIC_PASSWORD_LENGTH = 16
        const val MAX_CONFIG_BYTES = 256 * 1024
    }
}
