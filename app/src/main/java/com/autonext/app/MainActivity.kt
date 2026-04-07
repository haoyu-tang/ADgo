package com.autonext.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autonext.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadingUi = false
    private var lastKnownServiceEnabled: Boolean? = null

    companion object {
        private const val KEY_AUTO_SAVE_ENABLED = "key_auto_save_enabled"
        private const val DEFAULT_AUTO_SAVE_ENABLED = true
        private const val REQ_POST_NOTIFICATIONS = 2001
    }

    private data class AppItem(
        val label: String,
        val packageName: String
    )

    private class AppSelectorAdapter(
        private val inflater: LayoutInflater,
        private val selectedPackages: Set<String>
    ) : BaseAdapter() {
        private val items = mutableListOf<AppItem>()

        fun submitList(newItems: List<AppItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getItemAt(position: Int): AppItem = items[position]

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? CheckedTextView)
                ?: inflater.inflate(R.layout.item_app_selector, parent, false) as CheckedTextView
            val item = items[position]
            view.text = "${item.label}\n${item.packageName}"
            view.isChecked = selectedPackages.contains(item.packageName)
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnable.setOnClickListener {
            showCurrentServiceStatusToast()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                // Settings activity not available; no-op
            }
        }

        loadRules()
        binding.switchAllowlist.setOnCheckedChangeListener { _, _ ->
            maybeAutoSave()
        }
        binding.btnSelectApps.setOnClickListener {
            openAppSelectorDialog()
        }
        binding.btnSelectAllApps.setOnClickListener {
            selectAllApps()
        }
        binding.btnInvertApps.setOnClickListener {
            invertSelectedApps()
        }
        binding.btnClearApps.setOnClickListener {
            clearSelectedApps()
        }
        binding.btnEditPackages.setOnClickListener {
            openPackageEditorDialog()
        }
        binding.btnSaveRules.setOnClickListener {
            saveRules()
        }

        requestNotificationPermissionIfNeeded()
        refreshServiceStatus(showChangeToast = false)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus(showChangeToast = true)
    }

    private fun selectAllApps() {
        val apps = loadInstalledApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }
        binding.switchAllowlist.isChecked = true
        setPackagePreview(apps.map { it.packageName }.joinToString("\n"))
        maybeAutoSave()
    }

    private fun invertSelectedApps() {
        val apps = loadInstalledApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }
        val current = parsePackageInput(getPackageText())
        val inverted = apps.map { it.packageName }.filterNot { current.contains(it) }
        binding.switchAllowlist.isChecked = true
        setPackagePreview(inverted.joinToString("\n"))
        maybeAutoSave()
    }

    private fun clearSelectedApps() {
        setPackagePreview("")
        maybeAutoSave()
    }

    private fun openAppSelectorDialog() {
        val apps = loadInstalledApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPackages = parsePackageInput(getPackageText()).toMutableSet()
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_selector, null)
        val searchEditText = dialogView.findViewById<EditText>(R.id.et_search_apps)
        val listView = dialogView.findViewById<ListView>(R.id.list_apps)
        val adapter = AppSelectorAdapter(layoutInflater, selectedPackages)
        listView.adapter = adapter
        listView.isVerticalScrollBarEnabled = true
        listView.isScrollbarFadingEnabled = false

        fun updateFilter(query: String) {
            val keyword = query.trim().lowercase()
            val filtered = if (keyword.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.label.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword)
                }
            }
            val sorted = filtered.sortedWith(
                compareBy<AppItem> { !selectedPackages.contains(it.packageName) }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
            adapter.submitList(sorted)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItemAt(position)
            if (selectedPackages.contains(item.packageName)) {
                selectedPackages.remove(item.packageName)
            } else {
                selectedPackages.add(item.packageName)
            }
            updateFilter(searchEditText.text?.toString().orEmpty())
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateFilter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        updateFilter("")

        AlertDialog.Builder(this)
            .setTitle(R.string.select_apps_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.switchAllowlist.isChecked = true
                setPackagePreview(selectedPackages.sorted().joinToString("\n"))
                maybeAutoSave()
            }
            .show()
    }

    private fun openPackageEditorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_package_editor, null)
        val editor = dialogView.findViewById<EditText>(R.id.et_package_editor)
        val normalizeButton = dialogView.findViewById<View>(R.id.btn_normalize_packages)
        val clearButton = dialogView.findViewById<View>(R.id.btn_clear_packages)
        val currentText = getPackageText()
        editor.setText(currentText)
        editor.setSelection(editor.text?.length ?: 0)

        normalizeButton.setOnClickListener {
            val normalized = normalizePackageText(editor.text?.toString().orEmpty())
            editor.setText(normalized)
            editor.setSelection(editor.text?.length ?: 0)
        }
        clearButton.setOnClickListener {
            editor.setText("")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_packages_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setPackagePreview(normalizePackageText(editor.text?.toString().orEmpty()))
                maybeAutoSave()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun loadInstalledApps(): List<AppItem> {
        return packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null || it.enabled }
            .map { info ->
                val label = packageManager.getApplicationLabel(info)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { info.packageName }
                AppItem(label = label, packageName = info.packageName)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AppItem> { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }

    private fun loadRules() {
        loadingUi = true
        val prefs = getSharedPreferences(AutoNextService.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(
            AutoNextService.KEY_ALLOWLIST_ENABLED,
            AutoNextService.DEFAULT_ENABLE_PACKAGE_ALLOWLIST
        )
        val text = prefs.getString(
            AutoNextService.KEY_ALLOWLIST_TEXT,
            AutoNextService.DEFAULT_ALLOWLIST.joinToString("\n")
        ).orEmpty()
        val autoSave = prefs.getBoolean(KEY_AUTO_SAVE_ENABLED, DEFAULT_AUTO_SAVE_ENABLED)

        binding.switchAllowlist.isChecked = enabled
        binding.switchAutoSave.isChecked = autoSave
        setPackagePreview(text)
        loadingUi = false

        binding.switchAutoSave.setOnCheckedChangeListener { _, _ ->
            maybeAutoSave()
        }
    }

    private fun saveRules(showToast: Boolean = true) {
        val prefs = getSharedPreferences(AutoNextService.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(AutoNextService.KEY_ALLOWLIST_ENABLED, binding.switchAllowlist.isChecked)
            .putBoolean(KEY_AUTO_SAVE_ENABLED, binding.switchAutoSave.isChecked)
            .putString(AutoNextService.KEY_ALLOWLIST_TEXT, getPackageText())
            .apply()

        if (showToast) {
            Toast.makeText(this, R.string.save_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAutoSave() {
        if (loadingUi) return
        if (binding.switchAutoSave.isChecked) {
            saveRules(showToast = false)
        }
    }

    private fun refreshServiceStatus(showChangeToast: Boolean) {
        val enabled = isAccessibilityServiceEnabled()
        val statusText = if (enabled) {
            getString(R.string.service_status_enabled)
        } else {
            getString(R.string.service_status_disabled)
        }
        binding.btnEnable.setText(if (enabled) R.string.btn_manage else R.string.btn_enable)
        binding.tvServiceStatus.text = getString(R.string.service_status_label, statusText)

        val previous = lastKnownServiceEnabled
        if (showChangeToast && previous != null && previous != enabled) {
            showCurrentServiceStatusToast(enabled)
        }
        lastKnownServiceEnabled = enabled
    }

    private fun showCurrentServiceStatusToast(enabled: Boolean = isAccessibilityServiceEnabled()) {
        val messageRes = if (enabled) {
            R.string.service_status_toast_enabled
        } else {
            R.string.service_status_toast_disabled
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabledServices.isBlank()) return false

        val componentName = ComponentName(this, AutoNextService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any { it.equals(componentName, ignoreCase = true) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

    private fun getPackageText(): String {
        val current = binding.etAllowlistPackages.text?.toString().orEmpty()
        return if (current == getString(R.string.allowlist_preview_empty)) "" else current
    }

    private fun setPackagePreview(value: String) {
        val normalized = normalizePackageText(value)
        binding.etAllowlistPackages.text = if (normalized.isBlank()) {
            getString(R.string.allowlist_preview_empty)
        } else {
            normalized
        }
    }

    private fun normalizePackageText(raw: String): String =
        parsePackageInput(raw)
            .sorted()
            .joinToString("\n")

    private fun parsePackageInput(raw: String): Set<String> =
        raw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
