package com.example

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class FocusGuardDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "FocusGuard Device Admin Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "FocusGuard Device Admin Disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val prefs = context.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val isReleased = prefs.getBoolean("is_released", false)
        return if (!isReleased) {
            "🔒 SECURITY WARNING: FocusGuard is actively managed by your company. Deactivating this Device Admin is forbidden during work hours and requires remote authorization from your administrator."
        } else {
            null
        }
    }
}
