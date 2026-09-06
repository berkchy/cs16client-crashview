package com.pickle.patcher.patcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickle.patcher.CrashLog
import com.pickle.patcher.data.BundleProvider
import com.pickle.patcher.data.ReleaseRepository
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.lib.Bundle
import com.pickle.patcher.lib.SigningKeystore
import com.pickle.patcher.lib.ZipAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder

data class SourceInfo(
    val name: String,
    val sizeBytes: Long,
    val entryCount: Int,
)

sealed interface BundleState {
    data object None : BundleState
    data class Ready(val bundleName: String, val entries: Int, val version: String) : BundleState
    data class Downloading(val percent: Float) : BundleState
    data class DownloadError(val message: String) : BundleState
}

sealed interface AddonsState {
    data object None : AddonsState
    data class Downloading(val percent: Float, val step: String) : AddonsState
    data class Done(val message: String) : AddonsState
    data class Error(val message: String) : AddonsState
}

sealed interface PatchUiState {
    data object Idle : PatchUiState
    data class Running(val step: ApkPatcher.Step, val progress: Float) : PatchUiState
    data class Done(val report: ApkPatcher.PatchReport) : PatchUiState
    data class Failed(val message: String) : PatchUiState
}

data class SmaSource(val path: String, val name: String, val hasInclude: Boolean) {
    val scriptDir: String get() = File(path).parentFile?.absolutePath.orEmpty()
}

sealed interface CompileState {
    data object Idle : CompileState
    data class Compiling(val source: String) : CompileState
    data class Done(val log: String) : CompileState
    data class Failed(val message: String) : CompileState
}

class PatcherViewModel(app: Application) : AndroidViewModel(app) {

    private val bundleProvider = BundleProvider(app)

    private val _source = MutableStateFlow<SourceInfo?>(null)
    val source: StateFlow<SourceInfo?> = _source.asStateFlow()

    private val _receivedSource: MutableStateFlow<File?> = MutableStateFlow(null)

    private val _bundle = MutableStateFlow<BundleState>(BundleState.None)
    val bundle: StateFlow<BundleState> = _bundle.asStateFlow()

    private val _addons = MutableStateFlow<AddonsState>(AddonsState.None)
    val addons: StateFlow<AddonsState> = _addons.asStateFlow()

    private val _scripts = MutableStateFlow<List<SmaSource>>(emptyList())
    val scripts: StateFlow<List<SmaSource>> = _scripts.asStateFlow()

    /** Root folder the user picked via SAF; null until a folder is selected. */
    private val _scriptRoot = MutableStateFlow<String?>(null)
    val scriptRoot: StateFlow<String?> = _scriptRoot.asStateFlow()

    /** .amxx output folder picked by the user; null = "<scripts>/compiled". */
    private val _outputRoot = MutableStateFlow<String?>(null)
    val outputRoot: StateFlow<String?> = _outputRoot.asStateFlow()

    private val compilerPrefs by lazy {
        getApplication<Application>().getSharedPreferences("compiler_prefs", Context.MODE_PRIVATE)
    }

    init {
        // Restore persisted compiler folders so the user does not have to
        // re-pick them on every app start.
        val savedScripts = compilerPrefs.getString("script_root", null)
        if (!savedScripts.isNullOrEmpty() && File(savedScripts).isDirectory) {
            _scriptRoot.value = savedScripts
            refreshScripts()
        }
        val savedOutput = compilerPrefs.getString("output_root", null)
        if (!savedOutput.isNullOrEmpty() && File(savedOutput).isDirectory) {
            _outputRoot.value = savedOutput
        }
    }

    private val _compile = MutableStateFlow<CompileState>(CompileState.Idle)
    val compile: StateFlow<CompileState> = _compile.asStateFlow()

    private val _releaseNote = MutableStateFlow<String?>(null)
    val releaseNote: StateFlow<String?> = _releaseNote.asStateFlow()

    private val _patch = MutableStateFlow<PatchUiState>(PatchUiState.Idle)
    val patch: StateFlow<PatchUiState> = _patch.asStateFlow()

    data class CrashLogEntry(
        val fileName: String,
        val modified: String,
        val sizeBytes: Long,
        val content: String,
    )

    private val _crashLog = MutableStateFlow<CrashLogEntry?>(null)
    val crashLog: StateFlow<CrashLogEntry?> = _crashLog.asStateFlow()

    fun refreshCrashLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = CrashLog.latestFile(getApplication())
            _crashLog.value = file?.let {
                CrashLogEntry(
                    fileName = it.name,
                    modified = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(it.lastModified()),
                    sizeBytes = it.length(),
                    content = it.readText().take(1 shl 20),
                )
            }
        }
    }

    private var loadedBundle: Bundle? = null
    private var lastReport: ApkPatcher.PatchReport? = null

    val hasCachedBundle: Boolean get() = bundleProvider.hasCachedBundle()

    val repo = "berkchy/cs16client-amxx"

    private val workDir = File(app.getExternalFilesDir(null) ?: app.cacheDir, "patcher")

    /**
     * Loads the bundled signing key. Prefers the PEM pair (PKCS#8 key + X.509 cert)
     * because [SigningKeystore.loadPem] uses only [KeyFactory]/[CertificateFactory],
     * which are always available on Android; falls back to the PKCS12 container.
     */
    private fun loadSigningKeystore(): SigningKeystore {
        val assets = getApplication<Application>().assets
        val keyPem = runCatching {
            assets.open("keystore/debug_key.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        val certPem = runCatching {
            assets.open("keystore/debug_cert.pem").use { it.readBytes().decodeToString() }
        }.getOrNull()
        return if (keyPem != null && certPem != null) {
            SigningKeystore.loadPem(keyPem, certPem)
        } else {
            val p12 = assets.open("keystore/debug.p12").use { it.readBytes() }
            SigningKeystore.loadBytes(p12)
        }
    }

    /** Copies a SAF-picked source APK into app storage. */
    fun pickSource(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = queryName(uri) ?: "source.apk"
                val out = File(workDir, name)
                out.parentFile?.mkdirs()
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                val info = ZipAnalyzer.analyze(out)
                if (info.archAbi != "arm64-v8a") {
                    _patch.value = PatchUiState.Failed(
                        "This APK has no arm64-v8a libraries (found: ${info.archAbi ?: "none"}). " +
                            "The patcher only supports arm64 (arm64-v8a) CS16Client builds."
                    )
                    _receivedSource.value = null
                    return@launch
                }
                _source.value = SourceInfo(name, out.length(), info.entryCount)
                _receivedSource.value = out
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed("Could not copy source APK: ${t.message}")
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun useEmbeddedBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            val b = bundleProvider.loadEmbedded()
            withContext(Dispatchers.Main) {
                if (b != null) applyBundle("Embedded (offline)", b)
                else _bundle.value =
                    BundleState.DownloadError("No bundle is available on this device — download it online.")
            }
        }
    }

    fun useCachedBundle() {
        val b = bundleProvider.loadCachedBundle()
        if (b != null) applyBundle("Downloaded ($CACHE_TAG)", b)
    }

    fun fetchAndDownloadBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            _bundle.value = BundleState.Downloading(0.04f)
            try {
                val rel = ReleaseRepository.latest(repo)
                val asset = rel.bundleAsset()
                    ?: throw IOException("No bundle found in the latest release")
                _bundle.update {
                    BundleState.Downloading(0.1f)
                }
                val dest = bundleProvider.cachedBundleFile()
                ReleaseRepository.download(asset, dest) { p ->
                    // thread-safe; MutableStateFlow.value is atomic
                    _bundle.value = BundleState.Downloading(p)
                }
                val b = Bundle.fromZip(dest.readBytes())
                    ?: throw IOException("Bundle file is corrupted")
                _releaseNote.value = rel.name.ifBlank { rel.tag_name }
                applyBundle(asset.name, b)
            } catch (t: Throwable) {
                _bundle.value = BundleState.DownloadError(t.message ?: "Unknown error")
            }
        }
    }

    private fun applyBundle(label: String, b: Bundle) {
        loadedBundle = b
        _bundle.value = BundleState.Ready(label, b.manifest.entries.size, b.manifest.version)
    }

    /**
     * Downloads only the addons package (plugins + modules + configs) from the
     * latest release and extracts it into the device's cstrike folder. The full
     * mod bundle itself is fetched during patching, not here.
     */
    fun fetchAndInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            _addons.value = AddonsState.Downloading(0f, "Resolving latest release…")
            try {
                val rel = ReleaseRepository.latest(repo)
                val asset = rel.addonsAsset()
                    ?: throw IOException("No addons asset in the latest release")
                val zip = File(bundleProvider.cacheDir(), "amxx-addons.zip")
                _addons.value = AddonsState.Downloading(0f, "Downloading addons…")
                ReleaseRepository.download(asset, zip) { p ->
                    _addons.value = AddonsState.Downloading(p, "Downloading addons…")
                }
                _addons.value = AddonsState.Downloading(1f, "Extracting into cstrike…")
                val target = File(_installPath.value)
                val count = unzipInto(zip, target)
                _addons.value = AddonsState.Done(
                    "Installed ${count} addons files into ${target.path}"
                )
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
            }
        }
    }

    fun installAddonsFromBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            _addons.value = AddonsState.Downloading(0f, "Preparing addons…")
            try {
                val target = File(_installPath.value)
                val bundle = loadedBundle ?: bundleProvider.loadEmbedded() ?: bundleProvider.loadCachedBundle()
                    ?: throw IOException("No bundle available — load or download a bundle first.")
                var installed = 0
                var updated = 0
                var skipped = 0
                for (entry in bundle.manifest.entries) {
                    if (!entry.target.startsWith("addons/")) continue
                    val content = bundle.resolveEntry(entry) ?: continue
                    val outFile = File(target, entry.target)
                    if (outFile.exists()) {
                        val same = try {
                            outFile.length() == content.size.toLong() &&
                                outFile.readBytes().contentEquals(content)
                        } catch (_: Throwable) {
                            false
                        }
                        if (same) {
                            skipped++
                            continue
                        }
                        outFile.writeBytes(content)
                        updated++
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.writeBytes(content)
                        installed++
                    }
                }
                _addons.value = AddonsState.Done(
                    "Installed: $installed  ·  Updated: $updated  ·  Up to date: $skipped"
                )
                scanAddonsStatus()
            } catch (t: Throwable) {
                _addons.value = AddonsState.Error(t.message ?: "Unknown error")
            }
        }
    }

    private fun unzipInto(zip: File, target: File): Int {
        var count = 0
        java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val name = entry.name
                if (name.isBlank()) return@forEach
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw IOException("Unsafe path in addons zip: $name")
                }
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    fun startPatch() {
        val src = _receivedSource.value ?: return
        val b = loadedBundle ?: return
        val keystore = runCatching { loadSigningKeystore() }
            .getOrElse {
                _patch.value = PatchUiState.Failed("Signing key could not be loaded: ${it.message}")
                return
            }
        val out = File(workDir, "patched.apk")

        viewModelScope.launch(Dispatchers.IO) {
            _patch.value = PatchUiState.Running(ApkPatcher.Step.ANALYZE, 0f)
            try {
                val report = ApkPatcher.patch(
                    ApkPatcher.PatchRequest(src, out, b, keystore, keepAbi = "arm64-v8a"),
                    onStep = { step, p ->
                        _patch.value = PatchUiState.Running(step, p)
                    },
                )
                lastReport = report
                _patch.value = PatchUiState.Done(report)
            } catch (t: Throwable) {
                _patch.value = PatchUiState.Failed(t.message ?: "Unknown error")
            }
        }
    }

    fun outputApk(): File? = lastReport?.let { File(workDir, "patched.apk") }

    fun installIntent(): Intent? {
        val out = outputApk() ?: return null
        return installIntentFor(out)
    }

    fun installIntentFor(apk: File): Intent? {
        if (!apk.exists()) return null
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun reset() {
        _patch.value = PatchUiState.Idle
        lastReport = null
    }

    // ------------------------------------------------------------------ updater
    // Checks the crashview releases for a newer patcher APK and downloads it
    // with progress (downloaded/total/speed), then hands it to the package
    // installer automatically when done.

    sealed interface AppUpdate {
        data object Idle : AppUpdate
        data object Checking : AppUpdate
        data class Available(
            val tag: String,
            val notes: String,
            val size: Long,
            val url: String,
        ) : AppUpdate
        data class Downloading(
            val tag: String,
            val downloaded: Long,
            val total: Long,
            val bytesPerSec: Long,
        ) : AppUpdate
        data class Downloaded(val tag: String, val file: File) : AppUpdate
        data class UpToDate(val tag: String) : AppUpdate
        data class Failed(val message: String) : AppUpdate
    }

    private val _appUpdate = MutableStateFlow<AppUpdate>(AppUpdate.Idle)
    val appUpdate: StateFlow<AppUpdate> = _appUpdate.asStateFlow()

    /** Set to false once the popup shows or the user interacts — polling stops. */
    private val _pollEnabled = MutableStateFlow(true)
    val pollEnabled: StateFlow<Boolean> = _pollEnabled.asStateFlow()

    private val updatePrefs by lazy {
        getApplication<Application>().getSharedPreferences("updater_prefs", Context.MODE_PRIVATE)
    }

    private var nextPollAt: Long = 0L

    fun checkAppUpdate(silent: Boolean = true) {
        val cur = _appUpdate.value
        if (cur is AppUpdate.Checking || cur is AppUpdate.Downloading || cur is AppUpdate.Downloaded) return
        if (silent && SystemClock.elapsedRealtime() < nextPollAt) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!silent) _appUpdate.value = AppUpdate.Checking
            try {
                // Quota-free tag resolve (api.github.com is 60 req/hour shared).
                val tag = ReleaseRepository.latestTagRedirect(APP_RELEASE_REPO)
                if (tag.isNullOrEmpty()) {
                    nextPollAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000L
                    if (!silent) _appUpdate.value = AppUpdate.Failed("Update check failed (network).")
                    else _appUpdate.value = AppUpdate.Idle
                    return@launch
                }
                val ours = try {
                    getApplication<Application>().packageManager
                        .getPackageInfo(getApplication<Application>().packageName, 0).versionName
                } catch (_: Throwable) {
                    null
                }
                val known = updatePrefs.getString("known_tag", null)
                // Manual checks bypass the dismissed-tag memory so the popup
                // can be brought back from the menu any time.
                val isNew = (tag != known || !silent) &&
                    (ours == null || !ours.startsWith("v") || tag != ours)
                if (!isNew) {
                    _appUpdate.value = if (silent) AppUpdate.Idle else AppUpdate.UpToDate(tag)
                    return@launch
                }
                // Tag is new: one API call for notes + exact asset (rare).
                // If the API is rate-limited, fall back to the deterministic URL.
                var notes = ""
                var url = "https://github.com/$APP_RELEASE_REPO/releases/download/$tag/CS16-Meta-Patcher-release.apk"
                var size = 0L
                try {
                    val rel = ReleaseRepository.latest(APP_RELEASE_REPO)
                    if (rel.tag_name == tag) {
                        notes = rel.body.orEmpty()
                        rel.assets.firstOrNull { it.name.endsWith(".apk") }?.let {
                            url = it.browser_download_url
                            size = it.size
                        }
                    }
                } catch (_: Throwable) {
                    nextPollAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000L
                }
                _appUpdate.value = AppUpdate.Available(tag, notes, size, url)
            } catch (t: Throwable) {
                nextPollAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000L
                _appUpdate.value = if (silent) AppUpdate.Idle else AppUpdate.Failed(t.message ?: "Update check failed")
            }
        }
    }

    fun downloadAppUpdate() {
        val cur = _appUpdate.value as? AppUpdate.Available ?: return
        _pollEnabled.value = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(workDir, "update.apk")
                if (dest.exists()) dest.delete()
                val t0 = SystemClock.elapsedRealtime()
                ReleaseRepository.downloadUrl(cur.url, dest, cur.size) { done, total ->
                    val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    _appUpdate.value = AppUpdate.Downloading(cur.tag, done, total, done * 1000L / dt)
                }
                updatePrefs.edit().putString("known_tag", cur.tag).apply()
                _appUpdate.value = AppUpdate.Downloaded(cur.tag, dest)
            } catch (t: Throwable) {
                _appUpdate.value = AppUpdate.Failed(t.message ?: "Download failed")
            }
        }
    }

    fun dismissUpdate() {
        (_appUpdate.value as? AppUpdate.Available)?.let {
            updatePrefs.edit().putString("known_tag", it.tag).apply()
        }
        _pollEnabled.value = false
        _appUpdate.value = AppUpdate.Idle
    }

    fun consumeDownloaded() {
        _appUpdate.value = AppUpdate.Idle
    }

    // ------------------------------------------------------- plugins editor
    // Reads plugins-*.ini files and toggles lines with ';' (AMXX skips those).

    data class PluginLine(val text: String, val enabled: Boolean, val editable: Boolean, val isPlugin: Boolean)
    data class PluginIniFile(val name: String, val file: File, val lines: List<PluginLine>)

    private val _pluginInis = MutableStateFlow<List<PluginIniFile>>(emptyList())
    val pluginInis: StateFlow<List<PluginIniFile>> = _pluginInis.asStateFlow()

    fun loadPluginInis() {
        viewModelScope.launch(Dispatchers.IO) {
            val configs = File(File(_installPath.value), "addons/amxmodx/configs")
            val files = configs.listFiles { f ->
                f.isFile && f.name.startsWith("plugins") && f.name.endsWith(".ini")
            }?.sortedBy { it.name } ?: emptyList()
            _pluginInis.value = files.map { f ->
                PluginIniFile(
                    name = f.name,
                    file = f,
                    lines = f.readLines().map { line ->
                        val t = line.trim()
                        val stripped = t.removePrefix(";").trim()
                        val first = stripped.split(Regex("\\s+")).firstOrNull().orEmpty()
                        if (first.endsWith(".amxx", ignoreCase = true)) {
                            // Real plugin line (enabled) or disabled one (';').
                            PluginLine(line, enabled = !t.startsWith(";"), editable = true, isPlugin = true)
                        } else {
                            // Blank line, comment or header — not a plugin, never listed.
                            PluginLine(line, enabled = true, editable = false, isPlugin = false)
                        }
                    }
                )
            }
        }
    }

    fun togglePluginLine(iniName: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _pluginInis.value
            val ini = current.firstOrNull { it.name == iniName } ?: return@launch
            if (index !in ini.lines.indices) return@launch
            val line = ini.lines[index]
            if (!line.editable) return@launch
            val updated = ini.lines.toMutableList()
            updated[index] = if (line.enabled) {
                line.copy(text = ";" + line.text, enabled = false)
            } else {
                line.copy(text = line.text.replaceFirst(Regex("^\\s*;"), ""), enabled = true)
            }
            try {
                ini.file.writeText(updated.joinToString("\n") { it.text })
            } catch (_: Throwable) {
                return@launch
            }
            _pluginInis.value = current.map {
                if (it.name == iniName) it.copy(lines = updated) else it
            }
        }
    }

    // ------------------------------------------------------------ share logs
    // Collects versions + log tails into one text for sharing (bug reports).

    suspend fun buildLogShareText(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val pm = getApplication<Application>().packageManager
        val pkg = getApplication<Application>().packageName
        val ver = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).versionName
            }
        } catch (_: Throwable) {
            "unknown"
        }
        sb.append("CS16-Meta Patcher ").append(ver).append("\n")
        sb.append("Game dir: ").append(_installPath.value).append("\n\n")
        fun tail(f: File, max: Int = 60): List<String> = try {
            if (!f.exists()) return listOf("(missing: ${f.path})")
            val lines = f.readLines()
            lines.takeLast(max)
        } catch (t: Throwable) {
            listOf("(unreadable: ${t.message})")
        }
        val base = File(_installPath.value)
        sb.append("=== crash.log ===\n")
        tail(File(base, "crash.log"), 40).forEach { sb.append(it).append("\n") }
        sb.append("\n=== engine.log (tail) ===\n")
        tail(File(File(base, "").parent ?: "", "engine.log"), 40).forEach { sb.append(it).append("\n") }
        val logDir = File(base, "addons/amxmodx/logs")
        val latest = logDir.listFiles { f -> f.isFile && f.name.startsWith("L") }
            ?.maxByOrNull { it.lastModified() }
        if (latest != null) {
            sb.append("\n=== ${latest.name} (tail) ===\n")
            tail(latest, 60).forEach { sb.append(it).append("\n") }
        }
        sb.toString()
    }

    // ------------------------------------------------------ dismissed update
    // Brings back an update popup the user dismissed with "Later".

    fun showDismissedUpdate() {
        updatePrefs.edit().remove("known_tag").apply()
        _pollEnabled.value = true
        checkAppUpdate(silent = false)
    }

    /**
     * Called from the SAF folder picker. Persists the tree grant, resolves the picked
     * volume folder to a real disk path (MANAGE_EXTERNAL_STORAGE already grants raw
     * access) and lists the .sma files inside it.
     */
    fun setScriptRoot(uri: Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // grant may not be persistable (rare); listing still works this session
        }
        val dir = uriToDir(uri) ?: let {
            _compile.value = CompileState.Failed(
                "Could not resolve the picked folder to a disk path.\n" +
                    "Pick a folder on the device's internal storage (e.g. .../xash/cstrike/addons/amxmodx/scripting)."
            )
            return
        }
        _scriptRoot.value = dir.absolutePath
        compilerPrefs.edit().putString("script_root", dir.absolutePath).apply()
        refreshScripts()
    }

    /**
     * Called from the SAF folder picker for the .amxx output folder.
     * Persisted the same way as the scripts folder.
     */
    fun setOutputRoot(uri: Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // grant may not be persistable (rare); output still works this session
        }
        val dir = uriToDir(uri) ?: let {
            _compile.value = CompileState.Failed(
                "Could not resolve the picked folder to a disk path.\n" +
                    "Pick a folder on the device's internal storage."
            )
            return
        }
        _outputRoot.value = dir.absolutePath
        compilerPrefs.edit().putString("output_root", dir.absolutePath).apply()
    }

    /** Clears the custom output folder (back to "<scripts>/compiled"). */
    fun clearOutputRoot() {
        _outputRoot.value = null
        compilerPrefs.edit().remove("output_root").apply()
    }

    private fun uriToDir(uri: Uri): File? {
        // external storage (com.android.externalstorage.documents):
        // content://.../tree/primary%3Axash%2Fcstrike  -> /storage/emulated/0/xash/cstrike
        val path = uri.path ?: return null
        val marker = "/tree/"
        val idx = path.indexOf(marker)
        val doc = if (idx >= 0) path.substring(idx + marker.length) else path.trimStart('/')
        val decoded = URLDecoder.decode(doc, Charsets.UTF_8.name()) // primary:xash/cstrike
        val volumeSep = decoded.indexOf(':')
        if (volumeSep < 0) return null
        val volume = decoded.substring(0, volumeSep) // primary (or SD-card volume id)
        val rel = decoded.substring(volumeSep + 1).trimStart('/')
        if (volume == "primary") {
            return File(File(Environment.getExternalStorageDirectory(), ""), rel)
        }
        // secondary/custom volume: look it up on mounted volumes (API 30+ for a path)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val manager = getApplication<Application>().getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (v in manager.storageVolumes) {
                val dirPath = v.directory?.absolutePath ?: continue
                if (v.uuid == volume && File(dirPath).isDirectory) {
                    return File(File(dirPath, ""), rel)
                }
            }
        }
        return null
    }

    fun refreshScripts() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = _scriptRoot.value?.let { File(it) } ?: run {
                _scripts.value = emptyList()
                return@launch
            }
            _scripts.value = if (dir.isDirectory) {
                dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".sma", ignoreCase = true)
                }?.sortedBy { it.name }?.map { f ->
                    SmaSource(
                        path = f.absolutePath,
                        name = f.name,
                        hasInclude = File(f.parentFile, "include").isDirectory,
                    )
                }.orEmpty()
            } else {
                emptyList()
            }
        }
    }

    /**
     * Compiles the selected .sma on-device using the amxxpc bundled in the release
     * module. The driver + its libpc300 kernel (amxxpc32.so) are extracted from the
     * bundle into the app files dir so no separate install is required. Falls back
     * to an amxxpc sitting next to the script if no bundle compiler is available.
     */
    fun compile(source: SmaSource) {
        viewModelScope.launch(Dispatchers.IO) {
            _compile.value = CompileState.Compiling(source.name)
            try {
                val f = File(source.path)
                if (!f.exists()) error("Source not found: ${source.name}")
                val scriptDir = f.parentFile ?: error("Bad source path")
                val includeDir = File(scriptDir, "include")

                val amxxpc = prepareCompiler(scriptDir) ?: run {
                    _compile.value = CompileState.Failed(
                        "Compiler (amxxpc) unavailable.\n" +
                            "Pick the scripting folder, or install a patch with the embedded compiler first:\n" +
                            "• bundle compiler: $preparedCompilerPath\n" +
                            "• next to script: ${File(scriptDir, "amxxpc").absolutePath}"
                    )
                    return@launch
                }

                val cmd = mutableListOf(amxxpc.absolutePath)
                if (includeDir.isDirectory) {
                    cmd.add("-i${includeDir.absolutePath}")
                }
                val compiledDir = _outputRoot.value?.let { File(it) }?.takeIf { it.isDirectory }
                    ?: File(scriptDir, "compiled")
                compiledDir.mkdirs()
                val outPath = File(compiledDir, f.nameWithoutExtension + ".amxx").absolutePath
                cmd.add("-o$outPath")
                cmd.add(source.path)
                _compile.value = CompileState.Compiling(source.name)
                // Ensure compiler dir is in LD_LIBRARY_PATH so driver finds amxxpc32.so
                // (driver does dlopen("amxxpc32.so") / dlopen("./amxxpc32.so"))
                val compilerDir = amxxpc.parentFile
                val pb = ProcessBuilder(cmd).directory(scriptDir).redirectErrorStream(true)
                if (compilerDir != null && compilerDir.isDirectory) {
                    val oldLd = pb.environment()["LD_LIBRARY_PATH"]
                    pb.environment()["LD_LIBRARY_PATH"] = compilerDir.absolutePath + if (!oldLd.isNullOrEmpty()) ":$oldLd" else ""
                }
                // Last-chance chmod if file lost exec bit (e.g. after reboot)
                if (!amxxpc.canExecute()) {
                    try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
                    amxxpc.setExecutable(true, false)
                }
                val process = pb.start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                // Show only amxxpc's own output (no exec wrapper lines).
                val body = output.trim().ifEmpty {
                    if (exit == 0) "Done." else "Compile failed."
                }
                val log = buildString {
                    append(body)
                    val out = File(compiledDir, f.nameWithoutExtension + ".amxx")
                    if (exit != 0 || !out.exists()) {
                        append("\nCompile failed.")
                    }
                }
                _compile.value =
                    if (exit == 0) CompileState.Done(log) else CompileState.Failed(log)
            } catch (t: Throwable) {
                _compile.value = CompileState.Failed(t.message ?: "Compile error")
            }
        }
    }

    private val preparedCompilerPath: String
        get() = File(getApplication<Application>().filesDir, "compiler/amxxpc").absolutePath

    /**
     * Returns a runnable amxxpc: prefers the copy shipped as native lib
     * (lib/arm64-v8a/libamxxpc.so in the patcher APK → nativeLibraryDir, always
     * executable), then falls back to extracting from bundle into filesDir.
     */
    private fun prepareCompiler(fallbackDir: File): File? {
        // 1) nativeLibraryDir (APK lib, extractNativeLibs=true) — always exec-allowed
        try {
            val nativeDir = File(getApplication<Application>().applicationInfo.nativeLibraryDir)
            val nativeAmxxpc = File(nativeDir, "libamxxpc.so")
            val nativeKernel = File(nativeDir, "libamxxpc32.so")
            if (nativeAmxxpc.exists() && nativeAmxxpc.canExecute()) {
                // Driver dlopens "amxxpc32.so" (no lib prefix) — copy libamxxpc32.so to filesDir/amxxpc32.so so it is found
                try {
                    val compilerDir = File(getApplication<Application>().filesDir, "compiler")
                    compilerDir.mkdirs()
                    val kernelCopy = File(compilerDir, "amxxpc32.so")
                    if (nativeKernel.exists() && (!kernelCopy.exists() || kernelCopy.length() != nativeKernel.length())) {
                        kernelCopy.writeBytes(nativeKernel.readBytes())
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernelCopy.setReadable(true, false)
                    }
                } catch (_: Throwable) {}
                return nativeAmxxpc
            }
        } catch (_: Throwable) {}

        // Use the app's internal files dir (getFilesDir), not external storage:
        // the external/emulated dir is typically mounted noexec, so an ELF written
        // there cannot be exec'd ("permission denied" on ProcessBuilder.start()).
        val compilerDir = File(getApplication<Application>().filesDir, "compiler")
        val amxxpc = File(compilerDir, "amxxpc")
        val kernel = File(compilerDir, "amxxpc32.so")

        val bundleFiles = try {
            loadedBundle ?: bundleProvider.loadEmbedded() ?: bundleProvider.loadCachedBundle()
        } catch (_: Throwable) {
            null
        }
        val driverBytes = bundleFiles?.files?.get("compiler/amxxpc")
        if (driverBytes != null && driverBytes.isNotEmpty()) {
            try {
                compilerDir.mkdirs()
                amxxpc.writeBytes(driverBytes)
                // chmod 755 via shell is more reliable than File.setExecutable alone
                // (some OEMs / SELinux ignore the Java API). Do both.
                try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
                amxxpc.setExecutable(true, false)
                amxxpc.setReadable(true, false)
                bundleFiles.files["compiler/amxxpc32.so"]?.let {
                    if (it.isNotEmpty()) {
                        kernel.writeBytes(it)
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "755", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernel.setReadable(true, false)
                        // kernel is dlopened, not executed, but needs r+x for some loaders
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernel.absolutePath)).waitFor() } catch (_: Throwable) {}
                    }
                }
                return amxxpc
            } catch (_: Throwable) {
                // extraction failed; fall through to local
            }
        }
        // fallback: a compiler already present in the picked/script folder
        val root = _scriptRoot.value?.let { File(it) }
        return listOfNotNull(root, fallbackDir)
            .map { File(it, "amxxpc") }
            .firstOrNull { it.exists() && it.canExecute() }
    }

    private companion object {
        const val CACHE_TAG = "v2"
        const val GAME_DIR = "/storage/emulated/0/xash/cstrike"
        /** Releases (tags + patcher APK) are published here by CI. */
        const val APP_RELEASE_REPO = "berkchy/cs16client-crashview"
    }

    /**
     * Auto-install addons from the embedded bundle into the game directory.
     * Checks storage permission first; if not granted, silently skips.
     * Only writes files that are missing or have different sizes (no overwrite of user edits).
     */
    fun autoInstallAddons() {
        viewModelScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@launch
            }
            val gameDir = File(GAME_DIR)
            if (!gameDir.exists()) return@launch

            val bundle = bundleProvider.loadEmbedded() ?: return@launch
            var installed = 0
            for (entry in bundle.manifest.entries) {
                if (!entry.target.startsWith("addons/")) continue
                val target = File(gameDir, entry.target)
                if (target.exists()) continue
                val content = bundle.resolveEntry(entry) ?: continue
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                installed++
            }
            if (installed > 0) {
                _addons.value = AddonsState.Done("Auto-installed $installed addon files")
            }
            scanAddonsStatus()
        }
    }

    data class AddonFileStatus(
        val path: String,
        val expected: Boolean,
        val installed: Boolean,
        val outdated: Boolean = false,
    )

    private val _addonFiles = MutableStateFlow<List<AddonFileStatus>>(emptyList())
    val addonFiles: StateFlow<List<AddonFileStatus>> = _addonFiles.asStateFlow()

    private val _installPath = MutableStateFlow(GAME_DIR)
    val installPath: StateFlow<String> = _installPath.asStateFlow()

    fun setInstallPath(path: String) {
        _installPath.value = path
        scanAddonsStatus()
    }

    fun scanAddonsStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val gameDir = File(_installPath.value)
            val addonsDir = File(gameDir, "addons")

            val bundle = loadedBundle ?: bundleProvider.loadEmbedded() ?: bundleProvider.loadCachedBundle()
            val expected = mutableSetOf<String>()
            if (bundle != null) {
                for (entry in bundle.manifest.entries) {
                    if (!entry.target.startsWith("addons/")) continue
                    expected.add(entry.target)
                }
            }

            val result = mutableListOf<AddonFileStatus>()
            for (target in expected.sorted()) {
                val file = File(gameDir, target)
                if (!file.exists()) {
                    result.add(AddonFileStatus(target, expected = true, installed = false))
                    continue
                }
                // Byte-level compare: different size or different content (or a
                // newer bundle copy) means the installed file is outdated and
                // must be replaced on install.
                var outdated = false
                try {
                    val entry = bundle?.manifest?.entries?.firstOrNull { it.target == target }
                    val content = entry?.let { bundle?.resolveEntry(it) }
                    if (content != null) {
                        outdated = content.size.toLong() != file.length() ||
                            !content.contentEquals(file.readBytes())
                    }
                } catch (_: Throwable) {
                    outdated = false
                }
                result.add(AddonFileStatus(target, expected = true, installed = true, outdated = outdated))
            }
            _addonFiles.value = result
        }
    }
}