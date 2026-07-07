package com.example

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class FocusGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)

    // UI States
    private val _consentGranted = MutableStateFlow(prefs.getBoolean("consent_granted", false))
    val consentGranted: StateFlow<Boolean> = _consentGranted.asStateFlow()

    private val _isPaired = MutableStateFlow(!prefs.getString("device_id", null).isNullOrEmpty())
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    private val _employeeName = MutableStateFlow(prefs.getString("employee_name", "") ?: "")
    val employeeName: StateFlow<String> = _employeeName.asStateFlow()

    private val _companyName = MutableStateFlow(prefs.getString("company_name", "Acme Corporation") ?: "Acme Corporation")
    val companyName: StateFlow<String> = _companyName.asStateFlow()

    private val _deviceId = MutableStateFlow(prefs.getString("device_id", "") ?: "")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _syncStatus = MutableStateFlow(prefs.getString("sync_status", "Unpaired") ?: "Unpaired")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _lastSynced = MutableStateFlow(prefs.getString("last_synced", "Never") ?: "Never")
    val lastSynced: StateFlow<String> = _lastSynced.asStateFlow()

    // Permissions States
    private val _isAccessibilityGranted = MutableStateFlow(false)
    val isAccessibilityGranted: StateFlow<Boolean> = _isAccessibilityGranted.asStateFlow()

    private val _isOverlayGranted = MutableStateFlow(false)
    val isOverlayGranted: StateFlow<Boolean> = _isOverlayGranted.asStateFlow()

    private val _isDeviceAdminGranted = MutableStateFlow(false)
    val isDeviceAdminGranted: StateFlow<Boolean> = _isDeviceAdminGranted.asStateFlow()

    private val _isBatteryExempt = MutableStateFlow(false)
    val isBatteryExempt: StateFlow<Boolean> = _isBatteryExempt.asStateFlow()

    // Locked Apps
    private val _lockedApps = MutableStateFlow<List<LockedAppInfo>>(emptyList())
    val lockedApps: StateFlow<List<LockedAppInfo>> = _lockedApps.asStateFlow()

    // Default package lists
    val defaultPackagesToLock = listOf(
        LockedAppInfo("Instagram", "com.instagram.android"),
        LockedAppInfo("YouTube", "com.google.android.youtube"),
        LockedAppInfo("Facebook", "com.facebook.katana"),
        LockedAppInfo("Snapchat", "com.snapchat.android"),
        LockedAppInfo("TikTok", "com.zhiliaoapp.musically"),
        LockedAppInfo("Twitter/X", "com.twitter.android")
    )

    init {
        checkPermissions()
        loadLockedAppsFromCache()
        
        // Start foreground service if already paired and permissions look good
        if (_isPaired.value) {
            FocusGuardForegroundService.start(context)
        }
    }

    fun grantConsent() {
        prefs.edit().putBoolean("consent_granted", true).apply()
        _consentGranted.value = true
    }

    fun pairDevice(name: String, code: String, company: String) {
        val autoDeviceId = "device_" + UUID.randomUUID().toString().take(6)
        val selectedCompany = company.ifEmpty { "Acme Corporation" }

        viewModelScope.launch(Dispatchers.IO) {
            // Save locally first
            prefs.edit().apply {
                putString("device_id", autoDeviceId)
                putString("employee_name", name)
                putString("company_name", selectedCompany)
                putString("employee_code", code)
                putBoolean("is_released", false)
                apply()
            }

            _deviceId.value = autoDeviceId
            _employeeName.value = name
            _companyName.value = selectedCompany
            _isPaired.value = true

            // Try sending to Firestore, but handle gracefully if offline or Firebase missing
            try {
                val db = FirebaseFirestore.getInstance()
                val deviceData = mapOf(
                    "deviceId" to autoDeviceId,
                    "employeeName" to name,
                    "companyName" to selectedCompany,
                    "employeeCode" to code,
                    "status" to "online",
                    "lockedApps" to defaultPackagesToLock.map { it.packageName },
                    "lastSeen" to com.google.firebase.Timestamp.now(),
                    "scheduleEnabled" to false,
                    "scheduleStart" to "09:00",
                    "scheduleEnd" to "18:00",
                    "isReleased" to false
                )

                db.collection("devices").document(autoDeviceId)
                    .set(deviceData)
                    .addOnSuccessListener {
                        Log.d("Pairing", "Device registered in Firestore")
                        _syncStatus.value = "Synced"
                        prefs.edit().putString("sync_status", "Synced").apply()
                    }
                    .addOnFailureListener { e ->
                        Log.w("Pairing", "Firestore pairing failed - running in local fallback: ${e.message}")
                        setupLocalDemoMode()
                    }
            } catch (e: Exception) {
                Log.w("Pairing", "Firebase not available: ${e.message}. Running in local mode.")
                setupLocalDemoMode()
            }

            // Fire up background service
            viewModelScope.launch(Dispatchers.Main) {
                FocusGuardForegroundService.start(context)
            }
        }
    }

    private fun setupLocalDemoMode() {
        prefs.edit().apply {
            putStringSet("locked_apps", defaultPackagesToLock.map { it.packageName }.toSet())
            putBoolean("schedule_enabled", false)
            putString("sync_status", "Demo Mode (Local Sync)")
            apply()
        }
        _syncStatus.value = "Demo Mode (Local Sync)"
        loadLockedAppsFromCache()
    }

    fun loadLockedAppsFromCache() {
        val cachedPackages = prefs.getStringSet("locked_apps", emptySet()) ?: emptySet()
        val mappedList = defaultPackagesToLock.filter { cachedPackages.contains(it.packageName) }
        _lockedApps.value = mappedList.ifEmpty { 
            // In case of newly paired or fresh demo, load default set
            if (_isPaired.value) defaultPackagesToLock else emptyList()
        }
        
        _lastSynced.value = prefs.getString("last_synced", "Just now") ?: "Just now"
    }

    fun refreshCache() {
        loadLockedAppsFromCache()
        _syncStatus.value = prefs.getString("sync_status", "Synced") ?: "Synced"
    }

    fun submitRequest(packageName: String) {
        val autoRequestId = UUID.randomUUID().toString()
        val devId = _deviceId.value.ifEmpty { "unpaired_device" }

        val requestData = mapOf(
            "deviceId" to devId,
            "appRequested" to packageName,
            "timestamp" to com.google.firebase.Timestamp.now(),
            "status" to "pending",
            "tempUnlockUntil" to null
        )

        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("requests").document(autoRequestId)
                .set(requestData)
                .addOnSuccessListener {
                    Log.d("Request", "Access request submitted to Firestore")
                }
                .addOnFailureListener { e ->
                    Log.w("Request", "Firestore submit request failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w("Request", "Bypassing request submit to Firestore.")
        }
    }

    // Permission Check Utilities
    fun checkPermissions() {
        _isAccessibilityGranted.value = isAccessibilityServiceEnabled(context, FocusGuardAccessibilityService::class.java)
        _isOverlayGranted.value = Settings.canDrawOverlays(context)
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, FocusGuardDeviceAdminReceiver::class.java)
        _isDeviceAdminGranted.value = dpm.isAdminActive(adminComponent)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _isBatteryExempt.value = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = enabledServicesSetting.split(":")
        for (componentNameString in colonSplitter) {
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    // Permission Intents
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openDeviceAdminSettings(context: Context) {
        val adminComponent = ComponentName(context, FocusGuardDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "FocusGuard uses Device Administration to prevent uninstallation or disable commands during work shifts.")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

data class LockedAppInfo(
    val name: String,
    val packageName: String
)
