@file:Suppress("DEPRECATION")

package com.viperdam.kidsprayer.utils

import android.Manifest
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.admin.DevicePolicyManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.viperdam.kidsprayer.R
import android.app.AppOpsManager
import android.util.Log
import com.viperdam.kidsprayer.MyAdminReceiver

object PermissionHelper {
    private const val PREFS_NAME = "permission_prefs"
    private const val KEY_PERMISSIONS_REQUESTED = "permissions_requested"
    private const val KEY_SYSTEM_SETTINGS_REQUESTED = "system_settings_requested"
    private const val TAG = "PermissionHelper"
    private const val REQUEST_CODE_ADMIN = 1001
    
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.VIBRATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.DISABLE_KEYGUARD
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun checkAndRequestPermissions(
        activity: Activity,
        permissionLauncher: ActivityResultLauncher<Array<String>>,
        onAllGranted: () -> Unit
    ) {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            !isPermissionGranted(activity, it)
        }

        when {
            permissionsToRequest.isEmpty() && areSystemSettingsGranted(activity) -> {
                markPermissionsRequested(activity)
                onAllGranted()
            }
            !getPrefs(activity).getBoolean(KEY_PERMISSIONS_REQUESTED, false) -> {
                showInitialPermissionDialog(activity, permissionsToRequest.toTypedArray(), permissionLauncher)
            }
            permissionsToRequest.any { shouldShowRequestRationaleFor(activity, it) } -> {
                showPermissionRationaleDialog(activity, permissionsToRequest.toTypedArray(), permissionLauncher)
            }
            else -> {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch(e: Exception) {
            Log.e("PermissionHelper", "Error checking permission: $permission", e)
            false
        }
    }

    private fun areSystemSettingsGranted(activity: Activity): Boolean {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        val batteryOptimizationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // For Android 14+, check app standby bucket
            try {
                // First check if we have usage stats permission
                val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        activity.packageName
                    )
                } else {
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        activity.packageName
                    )
                }

                if (mode == AppOpsManager.MODE_ALLOWED) {
                    val usageStatsManager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val bucket = usageStatsManager.getAppStandbyBucket()
                    // Consider only ACTIVE or WORKING_SET as unrestricted
                    bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET
                } else {
                    false
                }
            } catch (e: Exception) {
                // If we can't check bucket status, assume restricted
                Log.e(TAG, "Error checking app standby bucket: ${e.message}")
                false
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 6-13, use traditional battery optimization check
            powerManager.isIgnoringBatteryOptimizations(activity.packageName)
        } else {
            true // No battery optimization before Android 6.0
        }

        return Settings.canDrawOverlays(activity) &&
               notificationManager.isNotificationPolicyAccessGranted &&
               (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) &&
               batteryOptimizationGranted
    }

    private fun shouldShowRequestRationaleFor(activity: Activity, permission: String): Boolean {
        return when (permission) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> !Settings.canDrawOverlays(activity)
            else -> ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    private fun showInitialPermissionDialog(
        activity: Activity,
        permissions: Array<String>,
        permissionLauncher: ActivityResultLauncher<Array<String>>
    ) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.initial_permission_message)
            .setPositiveButton(R.string.continue_text) { _, _ ->
                markPermissionsRequested(activity)
                permissionLauncher.launch(permissions)
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionRationaleDialog(
        activity: Activity,
        permissions: Array<String>,
        permissionLauncher: ActivityResultLauncher<Array<String>>
    ) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(R.string.grant) { _, _ ->
                permissionLauncher.launch(permissions)
            }
            .setNegativeButton(R.string.settings) { _, _ ->
                openAppSettings(activity)
            }
            .setCancelable(false)
            .show()
    }

    fun checkSystemSettings(activity: Activity, forceCheck: Boolean = false) {
        if (!getPrefs(activity).getBoolean(KEY_SYSTEM_SETTINGS_REQUESTED, false) && !forceCheck) {
            showSystemSettingsDialog(activity)
            return
        }

        val settingsToRequest = mutableListOf<String>()
        
        // Check overlay permission
        if (!Settings.canDrawOverlays(activity)) {
            settingsToRequest.add("Display over other apps")
        }

        // Check battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // For Android 14+, check app standby bucket
            val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    activity.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    activity.packageName
                )
            }

            if (mode != AppOpsManager.MODE_ALLOWED) {
                settingsToRequest.add("Usage access")
            } else {
                val usageStatsManager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val bucket = usageStatsManager.getAppStandbyBucket()
                if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
                    settingsToRequest.add("Unrestricted battery usage")
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 6-13, check traditional battery optimization
            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
                settingsToRequest.add("Unrestricted battery usage")
            }
        }

        // Check exact alarm permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                settingsToRequest.add("Exact alarms")
            }
        }

        // Check DND access
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            !notificationManager.isNotificationPolicyAccessGranted) {
            settingsToRequest.add("Do Not Disturb access")
        }

        if (settingsToRequest.isNotEmpty()) {
            showMissingSettingsDialog(activity, settingsToRequest)
        } else if (forceCheck) {
            // If we're forcing a check and all settings are granted, proceed
            markSystemSettingsRequested(activity)
        }
    }

    private fun showSystemSettingsDialog(activity: Activity) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.system_settings_required)
            .setMessage(R.string.system_settings_message)
            .setPositiveButton(R.string.continue_text) { _, _ ->
                markSystemSettingsRequested(activity)
                requestSystemSettings(activity)
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showMissingSettingsDialog(activity: Activity, missingSettings: List<String>) {
        val message = buildString {
            append(activity.getString(R.string.missing_settings_message))
            append("\n\n")
            missingSettings.forEachIndexed { index, setting ->
                append("${index + 1}. $setting")
                if (index < missingSettings.size - 1) append("\n")
            }
        }

        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.missing_settings)
            .setMessage(message)
            .setPositiveButton(R.string.settings) { _, _ ->
                requestSystemSettings(activity)
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun requestSystemSettings(activity: Activity) {
        // Request overlay permission first if needed
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            return
        }

        // For Android 14+, request usage stats permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    activity.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    activity.packageName
                )
            }

            if (mode != AppOpsManager.MODE_ALLOWED) {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                activity.startActivity(intent)
                return
            }

            // If we have usage stats permission but app is in restricted bucket,
            // use the battery optimization intent like older Android versions
            val usageStatsManager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val bucket = usageStatsManager.getAppStandbyBucket()
            if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For Android 6-13, request battery optimization directly
            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        // Request notification policy access if needed
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }

        // Request exact alarm permission if needed (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return
            }
        }
    }

    fun handlePermissionResult(
        activity: Activity,
        permissions: Map<String, Boolean>,
        onAllGranted: () -> Unit
    ) {
        when {
            permissions.all { it.value } -> {
                checkSystemSettings(activity)
                if (areSystemSettingsGranted(activity)) {
                    onAllGranted()
                }
            }
            permissions.any { shouldShowRequestRationaleFor(activity, it.key) } -> {
                showPermissionSettingsDialog(activity)
            }
            else -> {
                showPermissionDeniedDialog(activity)
            }
        }
    }

    private fun showPermissionSettingsDialog(activity: Activity) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_settings_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog(activity: Activity) {
        AlertDialog.Builder(activity, R.style.Theme_KidsPrayer_Dialog)
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.permission_denied_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings(activity: Activity) {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(this)
        }
    }

    private fun markPermissionsRequested(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply()
    }

    private fun markSystemSettingsRequested(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_SYSTEM_SETTINGS_REQUESTED, true).apply()
    }

    // Added function to ensure device admin permission is active
    fun ensureDeviceAdmin(activity: Activity) {
        val devicePolicyManager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(activity, MyAdminReceiver::class.java)
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                // Cast getString result to CharSequence to resolve ambiguity
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, activity.getString(R.string.admin_explanation) as CharSequence)
            }
            activity.startActivityForResult(intent, REQUEST_CODE_ADMIN)
        } else {
            Log.d("PermissionHelper", "Device admin already active")
        }
    }
}
