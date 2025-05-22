package com.viperdam.kidsprayer.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import com.viperdam.kidsprayer.databinding.LayoutPrayerSettingsBinding
import android.widget.AutoCompleteTextView

class PrayerSettingsAdapter(
    private val onPrayerSettingChanged: (String, SettingType, Any) -> Unit
) : RecyclerView.Adapter<PrayerSettingsAdapter.ViewHolder>() {

    private val prayers = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    private val settings = mutableMapOf<String, PrayerSetting>()

    enum class SettingType {
        ENABLED, ADHAN, NOTIFICATION, LOCK, ADHAN_VOLUME
    }

    data class PrayerSetting(
        var enabled: Boolean = true,
        var adhanEnabled: Boolean = false,
        var notificationEnabled: Boolean = false,
        var lockEnabled: Boolean = false,
        var adhanVolume: Float = 1.0f
    )

    class ViewHolder(
        private val binding: LayoutPrayerSettingsBinding,
        private val onSettingChanged: (String, SettingType, Any) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(prayerName: String, setting: PrayerSetting) {
            binding.apply {
                prayerNameText.text = prayerName
                
                // Basic prayer settings
                enableSwitch.isChecked = setting.enabled
                adhanSwitch.isChecked = setting.adhanEnabled
                notificationSwitch.isChecked = setting.notificationEnabled
                lockSwitch.isChecked = setting.lockEnabled

                // Volume slider
                adhanVolumeSlider.value = setting.adhanVolume * 100

                // Set up listeners
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onSettingChanged(prayerName, SettingType.ENABLED, isChecked)
                }

                adhanSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onSettingChanged(prayerName, SettingType.ADHAN, isChecked)
                }

                notificationSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onSettingChanged(prayerName, SettingType.NOTIFICATION, isChecked)
                }

                lockSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onSettingChanged(prayerName, SettingType.LOCK, isChecked)
                }

                adhanVolumeSlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        onSettingChanged(prayerName, SettingType.ADHAN_VOLUME, value / 100)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = LayoutPrayerSettingsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onPrayerSettingChanged)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val prayerName = prayers[position]
        holder.bind(prayerName, settings[prayerName] ?: PrayerSetting())
    }

    override fun getItemCount() = prayers.size

    fun updateSettings(newSettings: Map<String, PrayerSetting>) {
        settings.clear()
        settings.putAll(newSettings)
        notifyDataSetChanged()
    }
}
