package com.viperdam.kidsprayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class NetworkPermissionActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "NetworkPermissionActivity"
        private const val PERMISSION_REQUEST_CODE = 2001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val requiredPermissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE
        )
        
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            Log.d(TAG, "Network permissions granted: $allGranted")
            
            // Notify Unity Ads that permissions might have changed
            try {
                // Re-initialize Unity Ads or trigger connectivity refresh
                val unityMetadata = com.unity3d.ads.metadata.MetaData(this)
                unityMetadata.set("privacy.consent", true)
                unityMetadata.commit()
                
                // Force skip the permission check in Unity Ads
                val configMeta = com.unity3d.ads.metadata.MetaData(this)
                configMeta.set("config.connectivity_monitor.skip_permission_check", true)
                configMeta.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing Unity Ads after permission changes", e)
            }
        }
        
        // Close the activity
        finish()
    }
} 