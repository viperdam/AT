package com.viperdam.kidsprayer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.ads.ConsentManager
import com.viperdam.kidsprayer.databinding.ActivitySettingsBinding
import com.viperdam.kidsprayer.security.PinManager
import com.viperdam.kidsprayer.ui.pin.PinSetupDialog
import com.viperdam.kidsprayer.ui.pin.PinVerificationDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()
    
    @Inject
    lateinit var adManager: AdManager
    
    @Inject
    lateinit var consentManager: ConsentManager
    
    @Inject
    lateinit var pinManager: PinManager
    
    private lateinit var prayerSettingsAdapter: PrayerSettingsAdapter
    private var settingsJob: Job? = null
    private var messageJob: Job? = null
    private var loadingJob: Job? = null

    companion object {
        private const val TAG = "SettingsActivity"
        fun createIntent(context: Context) = Intent(context, SettingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            setupToolbar()
            setupBackHandler()
            setupPrayerSettings()
            setupPinSettings()
            setupGeneralSettings()
            setupAds()
            setupSettingsOptions()
            observeViewModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            showToast("Error initializing settings: ${e.message}")
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings)
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.hasUnsavedChanges() -> showUnsavedChangesDialog()
                    else -> finish()
                }
            }
        })
    }

    private fun setupAds() {
        try {
            Log.d(TAG, "Setting up ads")
            binding.adView.apply {
                adManager.loadBannerAd(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up ads: ${e.message}")
            binding.adView.visibility = View.GONE
            e.printStackTrace()
        }
    }

    private fun setupPrayerSettings() {
        try {
            prayerSettingsAdapter = PrayerSettingsAdapter { prayerName, settingType, value ->
                when (settingType) {
                    PrayerSettingsAdapter.SettingType.ENABLED -> viewModel.updatePrayerEnabled(prayerName, value as Boolean)
                    PrayerSettingsAdapter.SettingType.ADHAN -> viewModel.updateAdhanEnabled(prayerName, value as Boolean)
                    PrayerSettingsAdapter.SettingType.NOTIFICATION -> viewModel.updateNotificationEnabled(prayerName, value as Boolean)
                    PrayerSettingsAdapter.SettingType.LOCK -> viewModel.updateLockEnabled(prayerName, value as Boolean)
                    PrayerSettingsAdapter.SettingType.ADHAN_VOLUME -> viewModel.updateAdhanVolume(prayerName, value as Float)
                }
            }
            binding.prayerSettingsRecyclerView.adapter = prayerSettingsAdapter
            binding.prayerSettingsRecyclerView.layoutManager = LinearLayoutManager(this)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error setting up prayer settings: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupPinSettings() {
        try {
            binding.securityCard.apply {
                findViewById<View>(R.id.changePinButton).setOnClickListener {
                    showPinVerificationForChange()
                }

                findViewById<View>(R.id.resetPinButton).setOnClickListener {
                    showResetPinConfirmation()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up PIN settings: ${e.message}")
            showToast("Error setting up PIN settings: ${e.message}")
        }
    }

    private fun setupGeneralSettings() {
        try {
            binding.generalCard.apply {
                // Notification advance time
                findViewById<View>(R.id.notificationTimeSpinner).apply {
                    this as android.widget.Spinner
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val time = when(position) {
                                0 -> 15 // 15 minutes
                                1 -> 30 // 30 minutes
                                2 -> 45 // 45 minutes
                                else -> 15
                            }
                            viewModel.updateNotificationAdvanceTime(time)
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }

                // Calculation method
                findViewById<View>(R.id.calculationMethodSpinner).apply {
                    this as android.widget.Spinner
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            viewModel.updateCalculationMethod(position)
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }

                findViewById<View>(R.id.vibrationSwitch).apply {
                    this as com.google.android.material.switchmaterial.SwitchMaterial
                    setOnCheckedChangeListener { _, isChecked ->
                        viewModel.updateVibrationEnabled(isChecked)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up general settings: ${e.message}")
            showToast("Error setting up general settings: ${e.message}")
        }
    }

    private fun observeViewModel() {
        settingsJob?.cancel()
        settingsJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.settings.collect { settings ->
                        updateSettingsUI(settings)
                    }
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> throw e
                        else -> showToast("Error updating settings: ${e.message}")
                    }
                }
            }
        }

        messageJob?.cancel()
        messageJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.message.collect { message ->
                        message?.let { showToast(it) }
                    }
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> throw e
                        else -> showToast("Error showing message: ${e.message}")
                    }
                }
            }
        }

        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.isLoading.collect { isLoading ->
                        updateLoadingState(isLoading)
                    }
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> throw e
                        else -> showToast("Error updating loading state: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        binding.apply {
            progressIndicator.visibility = when {
                isLoading -> View.VISIBLE
                else -> View.GONE
            }
            prayerSettingsProgress.visibility = when {
                isLoading -> View.VISIBLE
                else -> View.GONE
            }
            prayerSettingsRecyclerView.visibility = when {
                isLoading -> View.INVISIBLE
                else -> View.VISIBLE
            }
            securityCard.alpha = when {
                isLoading -> 0.5f
                else -> 1f
            }
            generalCard.alpha = when {
                isLoading -> 0.5f
                else -> 1f
            }
            securityCard.isEnabled = !isLoading
            generalCard.isEnabled = !isLoading
        }
    }

    private fun updateSettingsUI(settings: PrayerSettings?) {
        if (settings == null) return
        
        val adapterSettings = mapOf(
            "Fajr" to PrayerSettingsAdapter.PrayerSetting(
                enabled = settings.fajr.enabled,
                adhanEnabled = settings.fajr.adhanEnabled,
                notificationEnabled = settings.fajr.notificationEnabled,
                lockEnabled = settings.fajr.lockEnabled,
                adhanVolume = settings.fajr.adhanVolume
            ),
            "Dhuhr" to PrayerSettingsAdapter.PrayerSetting(
                enabled = settings.dhuhr.enabled,
                adhanEnabled = settings.dhuhr.adhanEnabled,
                notificationEnabled = settings.dhuhr.notificationEnabled,
                lockEnabled = settings.dhuhr.lockEnabled,
                adhanVolume = settings.dhuhr.adhanVolume
            ),
            "Asr" to PrayerSettingsAdapter.PrayerSetting(
                enabled = settings.asr.enabled,
                adhanEnabled = settings.asr.adhanEnabled,
                notificationEnabled = settings.asr.notificationEnabled,
                lockEnabled = settings.asr.lockEnabled,
                adhanVolume = settings.asr.adhanVolume
            ),
            "Maghrib" to PrayerSettingsAdapter.PrayerSetting(
                enabled = settings.maghrib.enabled,
                adhanEnabled = settings.maghrib.adhanEnabled,
                notificationEnabled = settings.maghrib.notificationEnabled,
                lockEnabled = settings.maghrib.lockEnabled,
                adhanVolume = settings.maghrib.adhanVolume
            ),
            "Isha" to PrayerSettingsAdapter.PrayerSetting(
                enabled = settings.isha.enabled,
                adhanEnabled = settings.isha.adhanEnabled,
                notificationEnabled = settings.isha.notificationEnabled,
                lockEnabled = settings.isha.lockEnabled,
                adhanVolume = settings.isha.adhanVolume
            )
        )
        prayerSettingsAdapter.updateSettings(adapterSettings)
    }

    private fun showPinVerificationForChange() {
        try {
            PinVerificationDialog().apply {
                listener = {
                    showPinChangeDialog()
                }
                show(supportFragmentManager, "pin_verification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing PIN verification: ${e.message}")
            showToast(getString(R.string.error_showing_pin_dialog))
        }
    }

    private fun showPinChangeDialog() {
        try {
            PinSetupDialog().apply {
                setOnPinConfirmedListener { newPin ->
                    lifecycleScope.launch {
                        try {
                            pinManager.setPin(newPin)
                            showToast(getString(R.string.pin_changed_successfully))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting new PIN: ${e.message}")
                            showToast(getString(R.string.pin_disabled))
                        }
                    }
                }
                show(supportFragmentManager, "pin_change")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing PIN change dialog: ${e.message}")
            showToast(getString(R.string.error_showing_pin_dialog))
        }
    }

    private fun showResetPinConfirmation() {
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.reset_pin))
                .setMessage(getString(R.string.reset_pin_confirmation))
                .setPositiveButton(getString(R.string.reset)) { _, _ ->
                    showPinVerificationForReset()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing reset confirmation: ${e.message}")
            showToast(getString(R.string.error_showing_pin_dialog))
        }
    }

    private fun showPinVerificationForReset() {
        try {
            PinVerificationDialog().apply {
                listener = {
                    lifecycleScope.launch {
                        try {
                            pinManager.clearPin()
                            showFirstTimePinSetup()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing PIN: ${e.message}")
                            showToast(getString(R.string.pin_disabled))
                        }
                    }
                }
                show(supportFragmentManager, "pin_verification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing PIN verification for reset: ${e.message}")
            showToast(getString(R.string.error_showing_pin_dialog))
        }
    }

    private fun showFirstTimePinSetup() {
        try {
            PinSetupDialog().apply {
                isCancelable = false
                setOnPinConfirmedListener { pin ->
                    lifecycleScope.launch {
                        try {
                            pinManager.setPin(pin)
                            showToast(getString(R.string.new_pin_set))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting new PIN: ${e.message}")
                            showToast(getString(R.string.pin_disabled))
                        }
                    }
                }
                show(supportFragmentManager, "pin_setup")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing first-time PIN setup: ${e.message}")
            showToast(getString(R.string.error_showing_pin_dialog))
        }
    }

    private fun showUnsavedChangesDialog() {
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.unsaved_changes))
                .setMessage(getString(R.string.save_changes_message))
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    viewModel.saveChanges()
                    finish()
                }
                .setNegativeButton(getString(R.string.discard)) { _, _ ->
                    finish()
                }
                .setNeutralButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing unsaved changes dialog: ${e.message}")
            showToast("Error showing unsaved changes dialog: ${e.message}")
            finish()
        }
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        setupAds()
    }

    override fun onDestroy() {
        try {
            settingsJob?.cancel()
            messageJob?.cancel()
            loadingJob?.cancel()
            binding.adView.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
            e.printStackTrace()
        }
        super.onDestroy()
    }

    // Show privacy options form
    private fun showPrivacyOptions() {
        consentManager.showPrivacyOptionsForm(this) { success ->
            if (success) {
                Toast.makeText(this, "Privacy settings updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not update privacy settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // For debugging only - resets consent state
    private fun resetConsent() {
        consentManager.resetConsent()
        Toast.makeText(this, "Consent reset for debugging", Toast.LENGTH_SHORT).show()
        // Show consent dialog again after reset
        Handler(Looper.getMainLooper()).postDelayed({
            showPrivacyOptions()
        }, 1000)
    }
    
    // Setup settings options
    private fun setupSettingsOptions() {
        // Add privacy settings option
        binding.btnPrivacySettings.setOnClickListener {
            showPrivacyOptions()
        }
        
        // Hidden debug feature - tap the toolbar 5 times to reset consent
        var tapCount = 0
        binding.toolbar.setOnClickListener {
            tapCount++
            if (tapCount >= 5) {
                resetConsent()
                tapCount = 0
            }
        }
    }
}
