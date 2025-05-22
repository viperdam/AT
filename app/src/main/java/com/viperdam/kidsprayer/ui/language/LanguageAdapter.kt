package com.viperdam.kidsprayer.ui.language

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.viperdam.kidsprayer.R

class LanguageAdapter(
    private val languages: List<LanguageModel>,
    private val onLanguageSelected: (LanguageModel) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    inner class LanguageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val languageName: TextView = itemView.findViewById(R.id.languageName)
        private val languageCheckmark: ImageView = itemView.findViewById(R.id.languageCheckmark)

        fun bind(language: LanguageModel) {
            languageName.text = language.displayName
            languageCheckmark.visibility = if (language.isSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                // Update selected state
                val previousSelectedIndex = languages.indexOfFirst { it.isSelected }
                if (previousSelectedIndex != -1) {
                    languages[previousSelectedIndex].isSelected = false
                    notifyItemChanged(previousSelectedIndex)
                }

                val adapterPosition = adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    languages[adapterPosition].isSelected = true
                    notifyItemChanged(adapterPosition)
                    onLanguageSelected(languages[adapterPosition])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_language, parent, false)
        return LanguageViewHolder(view)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        holder.bind(languages[position])
    }

    override fun getItemCount(): Int = languages.size
} 