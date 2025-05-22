package com.viperdam.kidsprayer.ui.language

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viperdam.kidsprayer.R
import javax.inject.Inject

class LanguageSelectionDialog : DialogFragment() {

    @Inject
    lateinit var languageManager: LanguageManager

    private lateinit var adapter: LanguageAdapter
    private lateinit var recyclerView: RecyclerView
    private var onLanguageSelectedListener: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_language_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Get language manager from activity
        languageManager = (requireActivity() as LanguageSelectionProvider).getLanguageManager()
        
        recyclerView = view.findViewById(R.id.languageRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        // Get current language code
        val currentLanguageCode = languageManager.getCurrentLanguage()
        
        // Setup adapter with languages
        val languages = LanguageModel.getAvailableLanguages(currentLanguageCode)
        adapter = LanguageAdapter(languages) { selectedLanguage ->
            // Handle language selection
            // Don't set the language here - let the MainActivity handle it
            Log.d("LanguageSelectionDialog", "Language selected: ${selectedLanguage.code}")
            
            // Just notify the listener - MainActivity will handle the actual change
            onLanguageSelectedListener?.invoke(selectedLanguage.code)
            
            // Dismiss dialog
            dismiss()
        }
        
        recyclerView.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun setOnLanguageSelectedListener(listener: (String) -> Unit) {
        onLanguageSelectedListener = listener
    }

    interface LanguageSelectionProvider {
        fun getLanguageManager(): LanguageManager
    }
} 