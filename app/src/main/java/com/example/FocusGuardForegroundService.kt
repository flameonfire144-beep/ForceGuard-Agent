package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class FocusGuardForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var firestoreListener: ListenerRegistration? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "FocusGuardService"
        private const val NOTIFICATION_ID = 404
        
        fun start(context: Context) {
            val intent = Intent(context, FocusGuardForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FocusGuardForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        Log.d(TAG, "FocusGuard Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "FocusGuard Service Started")
        
        // Start Foreground Notification
        startForeground(NOTIFICATION_ID, createNotification("Active & Enforcing rules"))

        // Set status to online
        prefs.edit().putString("sync_status", "Active").apply()

        // Initialize real-time synchronization from Firebase
        setupFirestoreSync()

        // Heartbeat / LastSeen updates
        startHeartbeat()

        return START_STICKY
    }

    private fun createNotification(statusText: String): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setContentTitle("FocusGuard is active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_secure) // Fallback icon, will update with app-icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun setupFirestoreSync() {
        val deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            Log.d(TAG, "No paired Device ID. Running in local fallback mode.")
            return
        }

        try {
            val db = FirebaseFirestore.getInstance()
            firestoreListener?.remove()
            
            firestoreListener = db.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Firestore sync error: ${error.message}. Maintaining offline mode.")
                        prefs.edit().putString("sync_status", "Offline (Error: ${error.localizedMessage})").apply()
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        Log.d(TAG, "Remote config received: ${snapshot.data}")
                        
                        val employeeName = snapshot.getString("employeeName") ?: "Employee"
                        val lockedAppsList = snapshot.get("lockedApps") as? List<*>
                        val lockedSet = lockedAppsList?.filterIsInstance<String>()?.toSet() ?: emptySet()
                        
                        val scheduleEnabled = snapshot.getBoolean("scheduleEnabled") ?: false
                        val scheduleStart = snapshot.getString("scheduleStart") ?: "09:00"
                        val scheduleEnd = snapshot.getString("scheduleEnd") ?: "18:00"
                        val isReleased = snapshot.getBoolean("isReleased") ?: false

                        // Persist to local cache (SharedPreferences)
                        prefs.edit().apply {
                            putString("employee_name", employeeName)
                            putStringSet("locked_apps", lockedSet)
                            putBoolean("schedule_enabled", scheduleEnabled)
                            putString("schedule_start", scheduleStart)
                            putString("schedule_end", scheduleEnd)
                            putBoolean("is_released", isReleased)
                            putString("last_synced", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                            putString("sync_status", "Synced")
                            apply()
                        }
                    } else {
                        Log.d(TAG, "Device configuration does not exist in Firestore yet.")
                        prefs.edit().putString("sync_status", "Awaiting configuration").apply()
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase unavailable or missing: ${e.message}")
            prefs.edit().putString("sync_status", "Local / No Firebase config").apply()
        }
    }

    private fun startHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                val deviceId = prefs.getString("device_id", null)
                if (!deviceId.isNullOrEmpty()) {
                    try {
                        val db = FirebaseFirestore.getInstance()
                        db.collection("devices").document(deviceId)
                            .update(
                                mapOf(
                                    "lastSeen" to com.google.firebase.Timestamp.now(),
                                    "status" to "online"
                                )
                            )
                            .addOnSuccessListener {
                                Log.d(TAG, "Heartbeat updated successfully")
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Heartbeat update failed: ${e.message}")
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat bypassed - offline or Firestore missing.")
                    }
                }
                delay(5 * 60 * 1000) // Every 5 minutes
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()
        serviceJob.cancel()
        Log.d(TAG, "FocusGuard Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
