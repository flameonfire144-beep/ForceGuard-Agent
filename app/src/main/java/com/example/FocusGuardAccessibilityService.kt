package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.Calendar

class FocusGuardAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "FocusGuardAccess"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        Log.d(TAG, "Accessibility Service Created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        
        // Don't block our own app or the Android launcher/system UI
        if (packageName == this.packageName || 
            packageName == "com.android.launcher" || 
            packageName == "com.android.systemui" || 
            packageName == "com.google.android.apps.nexuslauncher" || 
            packageName.contains("launcher")) {
            return
        }

        // Check if the package is in the locked list
        val lockedApps = prefs.getStringSet("locked_apps", emptySet()) ?: emptySet()
        if (lockedApps.contains(packageName)) {
            Log.d(TAG, "Locked app detected: $packageName")
            
            // Check if schedule is active
            if (isScheduleActive()) {
                // Check if temporarily bypassed
                val bypassUntil = prefs.getLong("bypass_until_$packageName", 0L)
                val isBypassed = System.currentTimeMillis() < bypassUntil
                
                // Check if device has been fully released
                val isReleased = prefs.getBoolean("is_released", false)

                if (!isBypassed && !isReleased) {
                    Log.d(TAG, "Blocking app: $packageName. Showing Overlay!")
                    launchOverlay(packageName)
                } else {
                    Log.d(TAG, "App $packageName is temporarily bypassed or device is released.")
                }
            }
        }
    }

    private fun isScheduleActive(): Boolean {
        val scheduleEnabled = prefs.getBoolean("schedule_enabled", false)
        if (!scheduleEnabled) return true // Enforce full time if schedule is disabled
        
        val startStr = prefs.getString("schedule_start", "09:00") ?: "09:00"
        val endStr = prefs.getString("schedule_end", "18:00") ?: "18:00"
        
        return try {
            val now = Calendar.getInstance()
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)
            val currentTimeInMinutes = currentHour * 60 + currentMinute
            
            val startParts = startStr.split(":")
            val startInMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
            
            val endParts = endStr.split(":")
            val endInMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()
            
            if (startInMinutes <= endInMinutes) {
                currentTimeInMinutes in startInMinutes..endInMinutes
            } else {
                // Overlapping midnight (e.g. 22:00 to 06:00)
                currentTimeInMinutes >= startInMinutes || currentTimeInMinutes <= endInMinutes
            }
        } catch (e: Exception) {
            true // Fallback to locking on parsing failures
        }
    }

    private fun launchOverlay(packageName: String) {
        val intent = Intent(this, LockOverlayActivity::class.java).apply {
            putExtra("locked_package", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                      Intent.FLAG_ACTIVITY_CLEAR_TASK or 
                      Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }
}
