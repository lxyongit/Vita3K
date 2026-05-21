package org.vita3k.emulator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.vita3k.emulator.data.AppStorage
import org.vita3k.emulator.data.AppInfo
import org.vita3k.emulator.data.AppRepository
import org.vita3k.emulator.data.UiLanguages
import org.vita3k.emulator.ui.navigation.AppNavigation
import org.vita3k.emulator.ui.theme.Vita3KTheme
import org.vita3k.emulator.ui.viewmodel.InstallResultStatus
import org.vita3k.emulator.ui.viewmodel.AppsListViewModel
import org.vita3k.emulator.ui.viewmodel.InstallViewModel
import org.vita3k.emulator.ui.viewmodel.SettingsViewModel
import org.vita3k.emulator.ui.viewmodel.UserManagementViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {
    private val appsListViewModel: AppsListViewModel by viewModels()
    private val installViewModel: InstallViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val userManagementViewModel: UserManagementViewModel by viewModels()

    private var pendingExternalArchiveLaunch: PendingExternalArchiveLaunch? = null

    private val emulatorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (appsListViewModel.initialized) {
            appsListViewModel.reloadAppsList()
        }
    }

    private var pendingFolderCallback: ((String?) -> Unit)? = null
    private var pendingFileCallback: ((String?) -> Unit)? = null
    private var pendingArchiveFolderCallback: ((List<String>) -> Unit)? = null
    private var pendingInstallFileExtensions: Set<String>? = null
    private var pendingArchiveFolderExtensions: Set<String>? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private data class PendingExternalArchiveLaunch(
        val operationId: Long,
        val archivePath: String,
    )

    private data class ArchiveMetadata(
        val titleId: String?,
        val title: String?,
    )

    private val folderPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (StorageAccess.hasStorageAccess(this)) {
            launchPendingStorageAction()
        } else {
            cancelPendingStorageRequest()
        }
    }

    private val manageFolderAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (StorageAccess.hasStorageAccess(this)) {
            launchPendingStorageAction()
        } else {
            cancelPendingStorageRequest()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val selectedPath = if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> StorageAccess.resolveUriToPath(this, uri) }
        } else {
            null
        }
        val validatedPath = selectedPath?.takeIf { path ->
            pendingInstallFileExtensions?.let { allowed ->
                StorageAccess.matchesAllowedExtension(path, allowed)
            } ?: true
        }
        dispatchFileResult(validatedPath)
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingArchiveFolderCallback != null) {
            val selectedPaths = if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { treeUri ->
                    StorageAccess.resolveTreeFilePaths(
                        context = this,
                        treeUri = treeUri,
                        allowedExtensions = pendingArchiveFolderExtensions ?: emptySet()
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
            dispatchArchiveFolderResult(selectedPaths)
            return@registerForActivityResult
        }

        val selectedPath = if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> StorageAccess.resolveTreeUriToPath(this, uri) }
        } else {
            null
        }
        dispatchFolderResult(selectedPath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prepareFrontendRuntime()
        UiLanguages.applyStored(this)
        setTheme(R.style.Theme_Vita3K)

        val storagePath = resolveStoragePath(intent)
        val initialRoute = intent.getStringExtra(EXTRA_INITIAL_ROUTE)
        val installArchivePath = intent.getStringExtra(EXTRA_INSTALL_ARCHIVE_PATH)
        if (initialRoute == ROUTE_SETTINGS || !installArchivePath.isNullOrBlank()) {
            AppStorage.setInitialSetupCompleted(this, true)
        }
        appsListViewModel.initialize(storagePath)
        observeExternalInstallCompletion()

        setContent {
            Vita3KTheme {
                AppNavigation(
                    appsListViewModel = appsListViewModel,
                    installViewModel = installViewModel,
                    settingsViewModel = settingsViewModel,
                    userManagementViewModel = userManagementViewModel,
                    initialRoute = initialRoute,
                    onAppLaunch = { app -> launchApp(app.titleId, app.title) }
                )
            }
        }

        handleExternalInstallRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalInstallRequest(intent)
    }

    fun requestStorageFolderChange(onResult: (String?) -> Unit) {
        requestFolderPath(onResult)
    }

    fun requestFolderPath(onResult: (String?) -> Unit) {
        pendingFolderCallback = onResult
        pendingFileCallback = null
        pendingArchiveFolderCallback = null
        pendingInstallFileExtensions = null
        pendingArchiveFolderExtensions = null
        pendingStorageAction = { launchFolderPicker() }
        ensureStorageAccess()
    }

    fun requestFilePath(mimeTypes: Array<String>, onResult: (String?) -> Unit) {
        pendingFileCallback = onResult
        pendingFolderCallback = null
        pendingArchiveFolderCallback = null
        pendingInstallFileExtensions = null
        pendingArchiveFolderExtensions = null
        pendingStorageAction = { launchFilePicker(mimeTypes) }
        ensureStorageAccess()
    }

    fun requestInstallFilePath(
        allowedExtensions: Set<String>,
        onResult: (String?) -> Unit
    ) {
        pendingFileCallback = onResult
        pendingFolderCallback = null
        pendingArchiveFolderCallback = null
        pendingInstallFileExtensions = allowedExtensions
        pendingArchiveFolderExtensions = null
        pendingStorageAction = { launchInstallFilePicker() }
        ensureStorageAccess()
    }

    fun requestArchiveFolderPaths(
        allowedExtensions: Set<String>,
        onResult: (List<String>) -> Unit
    ) {
        pendingFileCallback = null
        pendingFolderCallback = null
        pendingArchiveFolderCallback = onResult
        pendingInstallFileExtensions = null
        pendingArchiveFolderExtensions = allowedExtensions
        pendingStorageAction = { launchInstallFolderPicker() }
        ensureStorageAccess()
    }

    override fun onResume() {
        super.onResume()
        prepareFrontendRuntime()
    }

    private fun ensureStorageAccess() {
        if (StorageAccess.hasStorageAccess(this)) {
            launchPendingStorageAction()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageFolderAccessLauncher.launch(StorageAccess.createManageAllFilesIntent(this))
            return
        }

        folderPermissionLauncher.launch(StorageAccess.missingStoragePermissions(this))
    }

    private fun launchApp(titleId: String, appTitle: String) {
        emulatorLauncher.launch(Emulator.createLaunchIntent(this, titleId, appTitle))
    }

    private fun launchFilePicker(mimeTypes: Array<String>) {
        filePickerLauncher.launch(StorageAccess.createFilePickerIntent(mimeTypes))
    }

    private fun launchInstallFilePicker() {
        filePickerLauncher.launch(StorageAccess.createInstallFilePickerIntent())
    }

    private fun launchFolderPicker() {
        folderPickerLauncher.launch(StorageAccess.createFolderPickerIntent())
    }

    private fun launchInstallFolderPicker() {
        folderPickerLauncher.launch(StorageAccess.createInstallFolderPickerIntent())
    }

    private fun launchPendingStorageAction() {
        val action = pendingStorageAction ?: return
        pendingStorageAction = null
        action.invoke()
    }

    private fun cancelPendingStorageRequest() {
        pendingStorageAction = null
        dispatchFileResult(null)
        dispatchFolderResult(null)
        dispatchArchiveFolderResult(emptyList())
    }

    private fun dispatchFolderResult(path: String?) {
        val callback = pendingFolderCallback
        pendingFolderCallback = null
        callback?.invoke(path?.takeIf { it.isNotBlank() })
    }

    private fun dispatchFileResult(path: String?) {
        val callback = pendingFileCallback
        pendingFileCallback = null
        pendingInstallFileExtensions = null
        callback?.invoke(path?.takeIf { it.isNotBlank() })
    }

    private fun dispatchArchiveFolderResult(paths: List<String>) {
        val callback = pendingArchiveFolderCallback
        pendingArchiveFolderCallback = null
        pendingArchiveFolderExtensions = null
        callback?.invoke(paths)
    }

    private fun prepareFrontendRuntime() {
        NativeLib.prepareFrontend()
    }

    private fun resolveStoragePath(intent: Intent?): String {
        return intent?.getStringExtra(EXTRA_STORAGE_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: AppStorage.storageRootPath(this)
    }

    private fun handleExternalInstallRequest(intent: Intent?) {
        val firmwarePaths = intent?.getStringArrayListExtra(EXTRA_INSTALL_FIRMWARE_PATHS)
            .orEmpty()
            .filter { it.isNotBlank() }
        val archivePath = intent?.getStringExtra(EXTRA_INSTALL_ARCHIVE_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: return

        intent.removeExtra(EXTRA_INSTALL_FIRMWARE_PATHS)
        intent.removeExtra(EXTRA_INSTALL_ARCHIVE_PATH)
        AppStorage.setInitialSetupCompleted(this, true)
        val operationId = if (firmwarePaths.isNotEmpty()) {
            installViewModel.installFirmwareThenArchive(firmwarePaths, archivePath)
        } else {
            installViewModel.installArchive(archivePath, forceReinstall = false)
        }
        pendingExternalArchiveLaunch = PendingExternalArchiveLaunch(
            operationId = operationId,
            archivePath = archivePath,
        )
    }

    private fun observeExternalInstallCompletion() {
        lifecycleScope.launch {
            InstallServiceController.state.collectLatest { state ->
                val pendingLaunch = pendingExternalArchiveLaunch ?: return@collectLatest
                if (state.operationId != pendingLaunch.operationId || state.installing || state.installResult == null) {
                    return@collectLatest
                }

                pendingExternalArchiveLaunch = null
                if (state.installResult?.status != InstallResultStatus.SUCCESS) {
                    return@collectLatest
                }

                findInstalledAppForArchive(pendingLaunch.archivePath)?.let { app ->
                    launchApp(app.titleId, app.title)
                }
            }
        }
    }

    private suspend fun findInstalledAppForArchive(archivePath: String): AppInfo? {
        AppRepository.refreshAppsList()
        val apps = AppRepository.getAppList()
        val metadata = extractArchiveMetadata(archivePath)

        val titleIdCandidates = buildList {
            metadata?.titleId?.takeIf { it.isNotBlank() }?.let(::add)
            File(archivePath).nameWithoutExtension.takeIf { it.isNotBlank() }?.let(::add)
        }.map(::normalizeLookupKey).filter(String::isNotBlank)

        apps.firstOrNull { app ->
            titleIdCandidates.contains(normalizeLookupKey(app.titleId))
        }?.let { return it }

        val titleCandidates = buildList {
            metadata?.title?.takeIf { it.isNotBlank() }?.let(::add)
            File(archivePath).nameWithoutExtension.takeIf { it.isNotBlank() }?.let(::add)
        }

        return apps.firstOrNull { app ->
            titleCandidates.any { candidate -> titleMatches(app.title, candidate) }
        }
    }

    private fun extractArchiveMetadata(archivePath: String): ArchiveMetadata? {
        val archive = File(archivePath)
        if (!archive.isFile) {
            return null
        }

        return runCatching {
            ZipFile(archive).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith("sce_sys/param.sfo")) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        return@runCatching parseParamSfo(bytes)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun parseParamSfo(bytes: ByteArray): ArchiveMetadata? {
        if (bytes.size < SFO_HEADER_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != SFO_MAGIC) {
            return null
        }

        buffer.int
        val keyTableStart = buffer.int
        val dataTableStart = buffer.int
        val entryCount = buffer.int
        val values = mutableMapOf<String, String>()

        for (index in 0 until entryCount) {
            val entryOffset = SFO_HEADER_SIZE + index * SFO_INDEX_ENTRY_SIZE
            if (entryOffset + SFO_INDEX_ENTRY_SIZE > bytes.size) {
                break
            }

            buffer.position(entryOffset)
            val keyOffset = buffer.short.toInt() and 0xffff
            buffer.short
            val dataLength = buffer.int
            buffer.int
            val dataOffset = buffer.int
            val keyStart = keyTableStart + keyOffset
            val dataStart = dataTableStart + dataOffset
            if (keyStart !in bytes.indices || dataStart !in bytes.indices || dataLength <= 0) {
                continue
            }

            val key = readNullTerminatedString(bytes, keyStart, bytes.size - keyStart)
            val value = readNullTerminatedString(bytes, dataStart, dataLength)
            if (key.isNotBlank() && value.isNotBlank()) {
                values[key] = value
            }
        }

        val titleId = values["TITLE_ID"]?.takeIf { it.isNotBlank() }
        val title = values["TITLE"]?.takeIf { it.isNotBlank() }
        return if (titleId == null && title == null) null else ArchiveMetadata(titleId, title)
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, maxLength: Int): String {
        if (start !in bytes.indices || maxLength <= 0) {
            return ""
        }

        val maxEnd = (start + maxLength).coerceAtMost(bytes.size)
        var end = start
        while (end < maxEnd && bytes[end].toInt() != 0) {
            end++
        }

        return if (end > start) String(bytes, start, end - start, Charsets.UTF_8).trim() else ""
    }

    private fun normalizeLookupKey(value: String): String {
        return value.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
    }

    private fun titleMatches(appTitle: String, candidateTitle: String): Boolean {
        val normalizedApp = appTitle.trim().lowercase(Locale.ROOT)
        val normalizedCandidate = candidateTitle.trim().lowercase(Locale.ROOT)
        if (normalizedApp.isBlank() || normalizedCandidate.isBlank()) {
            return false
        }

        return normalizedApp == normalizedCandidate ||
            normalizedApp.contains(normalizedCandidate) ||
            normalizedCandidate.contains(normalizedApp)
    }

    companion object {
        private const val SFO_MAGIC = 0x46535000
        private const val SFO_HEADER_SIZE = 20
        private const val SFO_INDEX_ENTRY_SIZE = 16
        const val EXTRA_STORAGE_PATH = "org.vita3k.emulator.extra.STORAGE_PATH"
        const val EXTRA_INITIAL_ROUTE = "org.vita3k.emulator.extra.INITIAL_ROUTE"
        const val EXTRA_INSTALL_FIRMWARE_PATHS = "org.vita3k.emulator.extra.INSTALL_FIRMWARE_PATHS"
        const val EXTRA_INSTALL_ARCHIVE_PATH = "org.vita3k.emulator.extra.INSTALL_ARCHIVE_PATH"
        const val ROUTE_SETTINGS = "settings"
    }
}
