package app.sokyiptv.diagnostic

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** A completely separate package from Soky; runs no player, cloud sync or Room database. */
class DiagnosticActivity : Activity() {
    private val pickCode = 7002
    private var sources: List<ProviderImport.Provider> = emptyList()
    private lateinit var passphrase: EditText
    private lateinit var manualServer: EditText
    private lateinit var manualUsername: EditText
    private lateinit var manualPassword: EditText
    private lateinit var manualUserAgent: EditText
    private lateinit var providerSelect: Spinner
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var htmlButton: Button
    private lateinit var browserButton: Button
    private lateinit var rememberOnDevice: CheckBox
    private var lastHtml: String? = null
    private val output = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(10, 16, 35)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 26, 26, 45)
        }
        scroll.addView(root)
        setContentView(scroll)

        fun heading(s: String) = TextView(this).apply {
            text = s
            setTextColor(Color.WHITE)
            textSize = 23f
            setPadding(0, 10, 0, 16)
        }.also(root::addView)
        fun note(s: String) = TextView(this).apply {
            text = s
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(0, 5, 0, 14)
        }.also(root::addView)
        fun button(text: String, click: (View) -> Unit) =
            Button(this).apply { this.text = text; setOnClickListener(click) }.also(root::addView)

        heading("Soky API Diagnostics")
        note("Independent, read-only Android 14 Xtream response inspector. " +
            "Original Soky IPTV Pro remains installed and unchanged.")
        note("Import a backup from Documents/SokyIPTVPro. " +
            "The automatic device vault may be unavailable to a differently signed app; " +
            "use manual-logins.kspc and its passphrase if needed.")
        passphrase = EditText(this).apply {
            hint = "Manual backup passphrase (only if needed)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }.also(root::addView)

        button("Import encrypted backup from shared folder") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                startActivityForResult(intent, pickCode)
            } catch (_: Exception) {
                setStatus("No compatible system file picker. Use Android's Files app to find " +
                    "Documents/SokyIPTVPro/manual-logins.kspc.")
            }
        }
        heading("Or enter Xtream provider manually")
        note("No import needed. Credentials are never embedded in APKs or uploaded to GitHub. " +
            "Optional device-local encryption lets future diagnostic updates reuse this account.")
        fun manualField(hintText: String, passwordField: Boolean = false): EditText =
            EditText(this).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_TEXT or if (passwordField) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_URI
                }
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }.also(root::addView)

        manualServer = manualField("Server URL (http://host:port)")
        manualUsername = manualField("Xtream username")
        manualPassword = manualField("Xtream password", passwordField = true)
        manualUserAgent = manualField("User-Agent (optional; defaults to Soky)")
        rememberOnDevice = CheckBox(this).apply {
            text = "Remember this provider privately on this device for future test versions"
            isChecked = true
            setTextColor(Color.WHITE)
        }.also(root::addView)
        button("Use manually entered provider") {
            val provider = try {
                ManualProviderInput.create(
                    manualServer.text.toString(),
                    manualUsername.text.toString(),
                    manualPassword.text.toString(),
                    manualUserAgent.text.toString(),
                )
            } catch (e: IllegalArgumentException) {
                // Only our own validation messages are displayed; never display input or URLs.
                setStatus(e.message ?: "Invalid provider fields.")
                null
            }
            if (provider != null) {
                selectSources(listOf(provider))
                if (rememberOnDevice.isChecked) {
                    val saved = runCatching { SavedTestProvider.save(this, provider) }.isSuccess
                    setStatus(if (saved)
                        "Provider encrypted locally. It will be available after future " +
                            "diagnostic app updates on this emulator."
                    else
                        "Provider selected for this session; Android could not save it securely.")
                }
                // Remove cleartext credentials from on-screen widgets immediately.
                manualServer.text.clear()
                manualUsername.text.clear()
                manualPassword.text.clear()
                manualUserAgent.text.clear()
                setStatus("Manual Xtream account loaded; choose Run to inspect API responses.")
            }
        }
        note("Or select a provider imported from encrypted backup:")
        providerSelect = Spinner(this).also(root::addView)
        runButton = button("Run read-only Xtream API probes") { runProbe() }
        runButton.isEnabled = false
        htmlButton = button("View sanitized HTML source") { showHtmlSource() }
        htmlButton.isEnabled = false
        browserButton = button("Open sanitized HTML in browser") { openHtmlInBrowser() }
        browserButton.isEnabled = false
        button("Forget saved diagnostic provider (this app only)") {
            runCatching { SavedTestProvider.clear(this) }
                .onSuccess {
                    sources = emptyList()
                    providerSelect.adapter = null
                    runButton.isEnabled = false
                    setStatus("Saved diagnostic login cleared. Original Soky account unchanged.")
                }.onFailure {
                    setStatus("Could not clear local diagnostic login; clear app storage in Android Settings.")
                }
        }
        button("Copy safe diagnostic report") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Soky diagnostics", output.toString()))
            setStatus("Safe report copied. It excludes credentials, URL query strings and raw HTML.")
        }
        status = TextView(this).apply {
            text = "No provider selected. Import a backup or enter provider details manually."
            setTextColor(Color.WHITE)
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 20, 0, 16)
        }.also(root::addView)

        // The SAME diagnostic package ID retains private encrypted preferences on
        // ordinary APK upgrades. The first D5 installation may require one manual entry.
        runCatching { SavedTestProvider.load(this) }
            .onSuccess { saved ->
                if (saved != null) {
                    selectSources(listOf(saved))
                    setStatus("Encrypted provider loaded privately from this emulator. " +
                        "Choose Run; credentials are not displayed or exported.")
                }
            }.onFailure {
                setStatus("Saved credentials could not be decrypted on this emulator. " +
                    "Enter or import them once to replace the local encrypted copy.")
            }
    }

    private fun selectSources(found: List<ProviderImport.Provider>) {
        sources = found
        providerSelect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            found.mapIndexed { index, source ->
                "${index + 1}. ${if (source.name.startsWith("Manual")) "Saved/manual Xtream" else "Imported Xtream provider"}"
            }
        )
        runButton.isEnabled = sources.isNotEmpty()
    }

    private fun setStatus(message: String) {
        output.append(message).append('\n')
        status.text = output.toString()
    }

    @Deprecated("Use ActivityResultContracts when changing the project; retained for API23 simplicity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // Read only; no broad file permissions, no writes to the original shared vault.
        val blob = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val limit = 128 * 1024 + 1
                val buf = ByteArray(limit)
                var used = 0
                while (used < buf.size) {
                    val n = stream.read(buf, used, buf.size - used)
                    if (n < 0) break
                    used += n
                }
                require(used <= 128 * 1024) { "Encrypted backup exceeds size limit." }
                buf.copyOf(used)
            } ?: error("Selected document is empty.")
        } catch (_: Exception) {
            setStatus("Could not open the encrypted document; select a local .kspc file.")
            return
        }
        val filename = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
        }.getOrNull().orEmpty()
        val isDevice = filename.contains("device-logins", ignoreCase = true)
        val password = passphrase.text.toString()
        // Different packages cannot use one another's Android-ID-derived vault
        // when installed with different signing certificates. Never bypass it.
        val parsed = runCatching {
            if (isDevice) ProviderImport.fromDeviceVault(this, blob)
            else ProviderImport.fromManualBackup(blob, password)
        }.getOrElse {
            setStatus(
                if (isDevice) "Device vault couldn't be decrypted by this separate app. " +
                    "Use Soky Settings -> Provider backup -> Save manual backup to shared folder, " +
                    "then import manual-logins.kspc here with that passphrase."
                else "Manual backup could not be decrypted. Check the passphrase and file."
            )
            return
        }
        selectSources(parsed.filter { it.kind == "XTREAM" })
        // Clear passphrase from UI immediately.
        passphrase.text.clear()
        if (sources.size == 1 && rememberOnDevice.isChecked) {
            val saved = runCatching { SavedTestProvider.save(this, sources.single()) }.isSuccess
            setStatus(if (saved) "Imported account also encrypted privately for future diagnostic updates."
                else "Imported account loaded; secure device storage unavailable.")
        }
        setStatus("Imported ${sources.size} Xtream provider(s) into memory; original account files unchanged.")
    }

    private fun runProbe() {
        if (sources.isEmpty()) return
        val source = sources.getOrNull(providerSelect.selectedItemPosition) ?: return
        runButton.isEnabled = false
        lastHtml = null
        htmlButton.isEnabled = false
        browserButton.isEnabled = false
        output.clear()
        setStatus("Soky Android API probe D4: 7 endpoints with full validation (64MiB cap each); bad VOD responses retried once.\n" +
            "Provider identity, credentials and full URLs intentionally omitted.\n")
        Thread {
            XtreamProbe().run(source) { result ->
                runOnUiThread {
                    setStatus("${result.action}: ${result.summary}")
                    if (result.html != null) {
                        lastHtml = result.html
                        htmlButton.isEnabled = true
                        browserButton.isEnabled = true
                    }
                }
            }
            runOnUiThread {
                setStatus("Probe finished. Compare with physical TV and share only the safe report.")
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun safeViewerHtml(raw: String): String =
        "<html><head><meta charset=\"utf-8\"></head><body style=\"background:#0a1023;color:white\">" +
            "<h3>Response HTML source — escaped; scripts cannot execute</h3><pre style=\"white-space:pre-wrap\">" +
            TextUtils.htmlEncode(raw) + "</pre></body></html>"

    private fun showHtmlSource() {
        val html = lastHtml ?: return
        val view = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            loadDataWithBaseURL(null, safeViewerHtml(html), "text/html", "UTF-8", null)
        }
        AlertDialog.Builder(this).setTitle("Sanitized HTML response")
            .setView(view).setPositiveButton("Close") { _, _ -> view.destroy() }.show()
    }

    private fun openHtmlInBrowser() {
        val html = lastHtml ?: return
        try {
            val folder = File(cacheDir, "diagnostic-html")
            check(folder.isDirectory || folder.mkdirs())
            val file = File(folder, "sanitized-api-response.html")
            file.writeText(safeViewerHtml(html), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "Sanitized response", uri)
            }
            startActivity(intent)
        } catch (_: Exception) {
            setStatus("Browser couldn't open local sanitized HTML. Showing it inside the app.")
            showHtmlSource()
        }
    }
}
