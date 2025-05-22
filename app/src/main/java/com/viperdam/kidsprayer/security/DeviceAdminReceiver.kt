package com.viperdam.kidsprayer.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.viperdam.kidsprayer.R

class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"

        // Lock task features for Android 12+ (S)
        val LOCK_TASK_FEATURES by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
            } else {
                0 // Default value for older versions
            }
        }

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, DeviceAdminReceiver::class.java)
        }

        fun isAdminActive(context: Context): Boolean {
            try {
                val devicePolicyManager = getDevicePolicyManager(context)
                return devicePolicyManager.isAdminActive(getComponentName(context))
            } catch (e: Exception) {
                Log.e(TAG, "Error checking admin status", e)
                return false
            }
        }

        fun requestAdminPrivileges(context: Context) {
            try {
                val componentName = getComponentName(context)
                val devicePolicyManager = getDevicePolicyManager(context)

                if (!devicePolicyManager.isAdminActive(componentName)) {
                    Log.d(TAG, "Requesting device admin privileges")
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_explanation))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } else {
                    Log.d(TAG, "Device admin already active")
                    Toast.makeText(context, R.string.device_admin_enabled, Toast.LENGTH_SHORT).show()
                    
                    // Set lock task features and packages if admin is already active
                    // Set lock task packages if admin is already active
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

                            // Set lock task packages
                            dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))

                            Log.d(TAG, "Lock task packages set successfully")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Failed to set lock task packages: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting device admin privileges", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun getDevicePolicyManager(context: Context): DevicePolicyManager {
            return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = getComponentName(context)

            // Set lock task packages for all supported Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))
                Log.d(TAG, "Lock task packages set successfully")
            }

            // Set additional features for Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.setLockTaskFeatures(componentName, LOCK_TASK_FEATURES)
                Log.d(TAG, "Lock task features set successfully")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure lock task: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
        try {
            Toast.makeText(context, R.string.device_admin_disabled, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDisabled", e)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_warning_disable)
    }
}
