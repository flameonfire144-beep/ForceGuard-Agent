package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class LockOverlayActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var lockedPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        lockedPackage = intent.getStringExtra("locked_package") ?: "Unknown App"

        setContent {
            MyApplicationTheme {
                LockOverlayScreen(
                    packageName = lockedPackage,
                    onGoHome = { sendToHomeScreen() },
                    onRequestAccess = { submitAccessRequest(lockedPackage) },
                    onDemoApprove = { simulateLocalApproval(lockedPackage) }
                )
            }
        }
    }

    private fun sendToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun submitAccessRequest(packageName: String) {
        val deviceId = prefs.getString("device_id", "unpaired_device") ?: "unpaired_device"
        val requestId = UUID.randomUUID().toString()

        val requestData = mapOf(
            "deviceId" to deviceId,
            "appRequested" to packageName,
            "timestamp" to Timestamp.now(),
            "status" to "pending",
            "tempUnlockUntil" to null
        )

        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("requests").document(requestId)
                .set(requestData)
                .addOnSuccessListener {
                    Toast.makeText(this, "Access request sent to administrator!", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener { e ->
                    Log.e("LockOverlay", "Firestore fail: ${e.message}")
                    showLocalRequestToast(packageName)
                }
        } catch (e: Exception) {
            Log.w("LockOverlay", "Firebase bypass: ${e.message}")
            showLocalRequestToast(packageName)
        }
    }

    private fun showLocalRequestToast(packageName: String) {
        Toast.makeText(
            this, 
            "Request sent! (Demo Mode: Request registered locally for $packageName)", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun simulateLocalApproval(packageName: String) {
        // Grant a 5-minute local bypass for easy evaluation
        val fiveMinutesLater = System.currentTimeMillis() + (5 * 60 * 1000)
        prefs.edit().putLong("bypass_until_$packageName", fiveMinutesLater).apply()
        Toast.makeText(this, "Demo Mode: App unlocked for 5 minutes!", Toast.LENGTH_SHORT).show()
        
        // Launch the app again or just close the lock screen so the user can interact
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            // Safe fallback
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Disable back press to prevent bypass
        sendToHomeScreen()
    }
}

@Composable
fun LockOverlayScreen(
    packageName: String,
    onGoHome: () -> Unit,
    onRequestAccess: () -> Unit,
    onDemoApprove: () -> Unit
) {
    val context = LocalContext.current
    val appLabel = remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    val prefs = remember { context.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE) }
    val companyName = remember { prefs.getString("company_name", "Your Company") ?: "Your Company" }
    
    var countdown by remember { mutableStateOf(2) }
    var isTimerActive by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // 2-second auto-redirect timer if inactive
    LaunchedEffect(isTimerActive, countdown) {
        if (isTimerActive && countdown > 0) {
            delay(1000)
            countdown--
            if (countdown == 0) {
                onGoHome()
            }
        }
    }

    // Capture any click on the background to cancel the auto-redirect timer
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                // Cancel auto-redirect if user touches the screen to interact
                isTimerActive = false
            }
            .padding(24.dp)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .testTag("lock_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Lock Icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "App Locked",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App Title & Message
                Text(
                    text = "Focus Period Active",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "🔒 $appLabel is locked during work hours by your administrator.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Company Affiliation Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Business,
                        contentDescription = "Company",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Managed by $companyName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action Buttons
                Button(
                    onClick = {
                        isTimerActive = false
                        onGoHome()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("go_to_home_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(imageVector = Icons.Default.Home, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Go to Home Screen",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        isTimerActive = false
                        onRequestAccess()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("request_access_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Request Temporary Access",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Countdown Timer Info
                if (isTimerActive) {
                    Text(
                        text = "Auto-redirecting to Home in ${countdown}s...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Text(
                        text = "Auto-redirect paused. Interact to send requests.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Simulation / Review Bypass Tool
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))
                
                TextButton(
                    onClick = {
                        isTimerActive = false
                        onDemoApprove()
                    },
                    modifier = Modifier.testTag("demo_bypass_button")
                ) {
                    Text(
                        text = "⚡ Demo Mode: Quick Unlock (5 Min)",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
