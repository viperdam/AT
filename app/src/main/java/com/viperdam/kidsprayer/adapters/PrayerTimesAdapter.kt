package com.viperdam.kidsprayer.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.prayer.PrayerCompletionManager
import com.viperdam.kidsprayer.ui.main.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PrayerTimesAdapter : ListAdapter<MainViewModel.PrayerUiModel, PrayerTimesAdapter.PrayerViewHolder>(PrayerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.prayer_display, parent, false)
        return PrayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PrayerViewHolder, position: Int) {
        val prayerUiModel = getItem(position)
        holder.bind(prayerUiModel)
    }

    class PrayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val prayerName: TextView = itemView.findViewById(R.id.prayerName)
        private val prayerTime: TextView = itemView.findViewById(R.id.prayerTime)
        private val prayerStatusBadge: TextView = itemView.findViewById(R.id.prayerStatusBadge)
        private val prayerCard: MaterialCardView = itemView.findViewById(R.id.prayerCard)
        
        fun bind(prayerUiModel: MainViewModel.PrayerUiModel) {
            val context = itemView.context
            
            // Set prayer name and time
            prayerName.text = prayerUiModel.prayer.name
            prayerTime.text = formatTime(prayerUiModel.prayer.time)
            
            // Set status badge and card styling based on prayer status
            when {
                prayerUiModel.isComplete -> {
                    // Check completion type for different styling
                    when (prayerUiModel.completionType) {
                        PrayerCompletionManager.CompletionType.PIN_VERIFIED -> {
                            // Completed by parent PIN verification - Orange
                            prayerStatusBadge.text = context.getString(R.string.prayer_status_completed_by_parent)
                            prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_completed_by_parent))
                            prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_completed_by_parent)
                        }
                        PrayerCompletionManager.CompletionType.PRAYER_PERFORMED -> {
                            // Completed by performing prayer - Green
                            prayerStatusBadge.text = context.getString(R.string.prayer_status_completed_by_prayer)
                            prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_completed_by_prayer))
                            prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_completed_by_prayer)
                        }
                        PrayerCompletionManager.CompletionType.PRAYER_MISSED -> {
                            // Missed prayer - Red
                            prayerStatusBadge.text = context.getString(R.string.prayer_status_missed)
                            prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_missed))
                            prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_missed)
                        }
                        else -> {
                            // Default completion - Green
                            prayerStatusBadge.text = context.getString(R.string.prayer_status_completed)
                            prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_completed_by_prayer))
                            prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_completed_by_prayer)
                        }
                    }
                }
                prayerUiModel.isCurrentPrayer -> {
                    // Current prayer - Blue
                    prayerStatusBadge.text = context.getString(R.string.prayer_status_now)
                    prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_current))
                    prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_current)
                }
                prayerUiModel.isMissed -> {
                    // Missed prayer - Red
                    prayerStatusBadge.text = context.getString(R.string.prayer_status_missed)
                    prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_missed))
                    prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_missed)
                }
                else -> {
                    // Upcoming prayer - Purple
                    prayerStatusBadge.text = context.getString(R.string.prayer_status_upcoming)
                    prayerStatusBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.prayer_upcoming))
                    prayerCard.strokeColor = ContextCompat.getColor(context, R.color.prayer_upcoming)
                }
            }
            
            // Make status badge visible
            prayerStatusBadge.visibility = View.VISIBLE
        }
        
        private fun formatTime(timeInMillis: Long): String {
            return SimpleDateFormat("hh:mm a", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date(timeInMillis))
        }
    }
    
    class PrayerDiffCallback : DiffUtil.ItemCallback<MainViewModel.PrayerUiModel>() {
        override fun areItemsTheSame(oldItem: MainViewModel.PrayerUiModel, newItem: MainViewModel.PrayerUiModel): Boolean {
            return oldItem.prayer.name == newItem.prayer.name
        }
        
        override fun areContentsTheSame(oldItem: MainViewModel.PrayerUiModel, newItem: MainViewModel.PrayerUiModel): Boolean {
            return oldItem == newItem
        }
    }
} 