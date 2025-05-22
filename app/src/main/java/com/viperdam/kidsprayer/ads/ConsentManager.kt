package com.viperdam.kidsprayer.ads

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.unity3d.ads.metadata.MetaData

@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adManager: AdManager
) {
    private var consentInformation: ConsentInformation? = null
    private var consentForm: ConsentForm? = null
    
    companion object {
        private const val TAG = "ConsentManager"
        private const val DEBUG_GEOGRAPHY = ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
        private const val DEBUG_MODE = false // Set to false for production
    }
    
    /**
     * Initialize the consent manager and check for consent
     * Call this during app startup
     */
    fun initialize(activity: Activity, onComplete: (Boolean) -> Unit) {
        // Log the current consent status first
        if (DEBUG_MODE) {
            findTestDeviceId(context)
            // Always reset consent in debug mode to ensure form is shown
            consentInformation?.reset()
            Log.d(TAG, "Debug mode: Consent reset to ensure form is shown")
        }
        
        // Set up the UMP SDK
        val params = buildConsentRequestParameters()
        
        consentInformation = UserMessagingPlatform.getConsentInformation(context).apply {
            requestConsentInfoUpdate(
                activity,
                params,
                {
                    // Consent request successful
                    Log.d(TAG, "Consent info updated")
                    
                    if (DEBUG_MODE || this.isConsentFormAvailable) {
                        // In debug mode, always show the form
                        loadAndShowConsentFormIfAvailable(activity, onComplete)
                    } else {
                        Log.d(TAG, "Consent form not available")
                        onComplete(true) // Continue without consent form
                    }
                },
                { formError ->
                    // Consent request failed
                    Log.e(TAG, "Error updating consent info: ${formError.message}")
                    onComplete(true) // Continue without consent
                }
            )
        }
    }
    
    /**
     * Check if consent is required and load the form if needed
     * In debug mode, always show the form
     */
    private fun loadAndShowConsentFormIfAvailable(activity: Activity, onComplete: (Boolean) -> Unit) {
        val consentInfo = consentInformation ?: return
        
        // In debug mode or if consent status is required, load and show the form
        if (DEBUG_MODE || consentInfo.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
            UserMessagingPlatform.loadConsentForm(
                context,
                { form ->
                    Log.d(TAG, "Consent form loaded successfully")
                    consentForm = form
                    showConsentForm(activity, onComplete)
                },
                { formError ->
                    Log.e(TAG, "Error loading consent form: ${formError.message}")
                    // Fallback to custom dialog if no form can be built (not set up in AdMob console)
                    if (formError.message?.contains("No available form can be built") == true) {
                        Log.d(TAG, "Using fallback consent dialog")
                        showCustomConsentDialog(activity, onComplete)
                    } else {
                        onComplete(true) // Continue without consent
                    }
                }
            )
        } else {
            Log.d(TAG, "Consent not required, consent status: ${consentInfo.consentStatus}")
            // Apply consent updates to the AdManager
            updateAdManagerConsent()
            // Consent not required or already given
            onComplete(true)
        }
    }
    
    /**
     * Show the consent form to the user
     */
    private fun showConsentForm(activity: Activity, onComplete: (Boolean) -> Unit) {
        val form = consentForm ?: return
        
        form.show(
            activity
        ) { formError ->
            formError?.let {
                Log.e(TAG, "Error showing consent form: ${it.message}")
                onComplete(false)
            } ?: run {
                Log.d(TAG, "Consent form shown, status: ${consentInformation?.consentStatus}")
                updateAdManagerConsent()
                onComplete(true)
            }
        }
    }
    
    /**
     * Update the AdManager with the latest consent status
     */
    private fun updateAdManagerConsent() {
        val consentInfo = consentInformation ?: return
        
        // Determine if consent is obtained based on the consent status
        val hasConsent = consentInfo.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
        
        // Apply consent to both GDPR and CCPA for simplicity
        adManager.setGDPRConsent(hasConsent)
        adManager.setCCPAConsent(hasConsent)
        
        // --- Pass consent status to Unity Ads SDK ---
        try {
            // For GDPR
            val gdprMetaData = MetaData(context)
            gdprMetaData.set("gdpr.consent", hasConsent) // Use actual consent value
            gdprMetaData.commit()
            Log.d(TAG, "Passed GDPR consent status ($hasConsent) to Unity Ads SDK.")

            // For CCPA / US Privacy (Unity uses 'privacy.consent')
            // Note: You might need more granular logic if you distinguish between GDPR/CCPA opt-outs.
            // This example assumes a single 'hasConsent' flag applies similarly.
            val ccpaMetaData = MetaData(context)
            // Unity Ads documentation uses "privacy.consent" for CCPA/US states.
            // The value should be Boolean: true (opt-out) or false (did not opt-out).
            // Assuming 'hasConsent' maps inversely for "Do Not Sell" (true = opt-out, false = allow)
            // If hasConsent=true means user consented (didn't opt-out), then privacy.consent should be false.
            // If hasConsent=false means user did NOT consent (opted-out), then privacy.consent should be true.
            val privacyConsentValue = !hasConsent
            ccpaMetaData.set("privacy.consent", privacyConsentValue)
            ccpaMetaData.commit()
            Log.d(TAG, "Passed CCPA/US Privacy consent status ($privacyConsentValue) to Unity Ads SDK.")

        } catch (e: Exception) {
            Log.e(TAG, "Error passing consent metadata to Unity Ads SDK", e)
        }
        // --- End of Unity Ads consent passing ---

        Log.d(TAG, "Updated ad consent status: $hasConsent")
    }
    
    /**
     * Helper method to find and log the test device ID
     * This will print the test device ID to Logcat so you can copy it
     */
    private fun findTestDeviceId(context: Context) {
        try {
            val advertisingIdInfo = com.google.android.gms.ads.identifier.AdvertisingIdClient.getAdvertisingIdInfo(context)
            val deviceId = advertisingIdInfo.id
            Log.d(TAG, "TEST DEVICE ID: $deviceId")
            Log.d(TAG, "👉 Add this test device ID to your ConsentDebugSettings.Builder: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting advertising ID", e)
        }
    }
    
    /**
     * Build consent request parameters, including debug settings if needed
     */
    private fun buildConsentRequestParameters(): ConsentRequestParameters {
        return if (DEBUG_MODE) {
            // Debug settings for testing
            val debugSettings = ConsentDebugSettings.Builder(context)
                // Force testing as if device is in EEA
                .setDebugGeography(DEBUG_GEOGRAPHY)
                // Add the actual test device ID from logs
                .addTestDeviceHashedId("1C83226BA4A44BE491288DBA6C2CF015")
                .build()
            
            ConsentRequestParameters.Builder()
                // This app is for all ages, not just children
                .setConsentDebugSettings(debugSettings)
                .build()
        } else {
            // Production settings
            ConsentRequestParameters.Builder()
                // This app is for all ages, not just children
                .build()
        }
    }
    
    /**
     * Show the privacy options form, typically triggered from a privacy/settings menu
     */
    fun showPrivacyOptionsForm(activity: Activity, onComplete: (Boolean) -> Unit) {
        // In debug mode, always show the consent form instead of privacy options
        if (DEBUG_MODE) {
            // First reset consent to ensure form is shown
            consentInformation?.reset()
            
            // Request consent info update to trigger form
            val params = buildConsentRequestParameters()
            consentInformation?.requestConsentInfoUpdate(
                activity,
                params,
                {
                    // Load and show the consent form directly
                    UserMessagingPlatform.loadConsentForm(
                        context,
                        { form ->
                            form.show(
                                activity
                            ) { formError ->
                                formError?.let {
                                    Log.e(TAG, "Error showing debug consent form: ${it.message}")
                                    // Fallback to our own consent dialog if UMP can't build a form
                                    if (it.message?.contains("No available form can be built") == true) {
                                        showCustomConsentDialog(activity, onComplete)
                                    } else {
                                        onComplete(false)
                                    }
                                } ?: run {
                                    Log.d(TAG, "Debug consent form shown successfully")
                                    updateAdManagerConsent()
                                    onComplete(true)
                                }
                            }
                        },
                        { formError ->
                            Log.e(TAG, "Error loading debug consent form: ${formError.message}")
                            // Fallback to our own consent dialog if UMP can't build a form
                            if (formError.message?.contains("No available form can be built") == true) {
                                showCustomConsentDialog(activity, onComplete)
                            } else {
                                onComplete(false)
                            }
                        }
                    )
                },
                { updateError ->
                    Log.e(TAG, "Error updating consent info for debug: ${updateError.message}")
                    onComplete(false)
                }
            )
            return
        }
        
        // Normal production flow
        UserMessagingPlatform.showPrivacyOptionsForm(
            activity
        ) { formError ->
            formError?.let {
                Log.e(TAG, "Error showing privacy options: ${it.message}")
                // Fallback to our own consent dialog if UMP can't build a form
                if (it.message?.contains("No available form can be built") == true) {
                    showCustomConsentDialog(activity, onComplete)
                } else {
                    onComplete(false)
                }
            } ?: run {
                Log.d(TAG, "Privacy options shown, updating consent status")
                updateAdManagerConsent()
                onComplete(true)
            }
        }
    }
    
    /**
     * Show a custom consent dialog as a fallback when Google's UMP SDK can't build a form
     * This happens when you haven't created a message in the AdMob console yet
     */
    private fun showCustomConsentDialog(activity: Activity, onComplete: (Boolean) -> Unit) {
        try {
            val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            builder.setTitle("Privacy Consent Required")
            builder.setMessage(
                "This app uses advertising to support development. " +
                "We request your consent to personalize ads and improve your experience. " +
                "You can change your preference anytime in the app settings."
            )
            builder.setPositiveButton("Consent") { dialog, _ ->
                dialog.dismiss()
                // Update consent status
                adManager.setGDPRConsent(true)
                adManager.setCCPAConsent(true)
                onComplete(true)
            }
            builder.setNegativeButton("Decline") { dialog, _ ->
                dialog.dismiss()
                // Still update but with non-personalized ads
                adManager.setGDPRConsent(false)
                adManager.setCCPAConsent(false)
                onComplete(true)
            }
            builder.setCancelable(false)
            builder.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing custom consent dialog", e)
            onComplete(false)
        }
    }
    
    /**
     * Check if the privacy options form is available
     */
    fun canShowPrivacyOptionsForm(): Boolean {
        return consentInformation?.privacyOptionsRequirementStatus == 
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
    
    /**
     * Reset the consent information (typically for debugging)
     */
    fun resetConsent() {
        consentInformation?.reset()
    }
    
    /**
     * Configure Unity Ads for mediation with proper consent settings
     * This method should be called after MobileAds initialization
     */
    fun configureUnityAdsMediation() {
        try {
            // Check consent information
            val hasConsent = consentInformation?.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
            
            // Set GDPR consent based on actual user consent
            val gdprMetaData = MetaData(context)
            gdprMetaData.set("gdpr.consent", hasConsent)
            gdprMetaData.commit()
            
            // Set privacy consent based on actual user consent
            val privacyMetaData = MetaData(context)
            privacyMetaData.set("privacy.consent", hasConsent)
            privacyMetaData.commit()
            
            Log.d(TAG, "Unity Ads mediation consent configured with user consent: $hasConsent")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Unity Ads mediation consent: ${e.message}", e)
        }
    }
} 