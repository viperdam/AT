package com.viperdam.kidsprayer.ui.language

import java.util.Locale

data class LanguageModel(
    val code: String,
    val displayName: String,
    var isSelected: Boolean = false
) {
    companion object {
        // Map of language codes that need special handling
        private val LANGUAGE_RESOURCE_MAPPING = mapOf(
            "in" to "id",  // Handle legacy Indonesian code
            "iw" to "he"   // Handle legacy Hebrew code
        )

        /**
         * Get the correct resource directory name for a language code
         */
        fun getResourceDirectoryName(code: String): String {
            // Check if we need to map this language code to a different one for resources
            return LANGUAGE_RESOURCE_MAPPING[code] ?: code
        }
        
        fun getAvailableLanguages(currentLanguageCode: String): List<LanguageModel> {
            return listOf(
                LanguageModel("en", "English", currentLanguageCode == "en"),
                LanguageModel("de", "German", currentLanguageCode == "de"),
                LanguageModel("fr", "French", currentLanguageCode == "fr"),
                LanguageModel("nl", "Dutch", currentLanguageCode == "nl"),
                LanguageModel("ar", "Arabic", currentLanguageCode == "ar"),
                LanguageModel("hi", "Hindi", currentLanguageCode == "hi"),
                LanguageModel("id", "Indonesian", currentLanguageCode == "id"),
                LanguageModel("tr", "Turkish", currentLanguageCode == "tr"),
                LanguageModel("bn", "Bengali", currentLanguageCode == "bn"),
                LanguageModel("it", "Italian", currentLanguageCode == "it"),
                LanguageModel("kn", "Kannada", currentLanguageCode == "kn"),
                LanguageModel("ms", "Malay", currentLanguageCode == "ms"),
                LanguageModel("ml", "Malayalam", currentLanguageCode == "ml"),
                LanguageModel("mr", "Marathi", currentLanguageCode == "mr"),
                LanguageModel("fa", "Persian", currentLanguageCode == "fa"),
                LanguageModel("pa", "Punjabi", currentLanguageCode == "pa"),
                LanguageModel("ru", "Russian", currentLanguageCode == "ru"),
                LanguageModel("es", "Spanish", currentLanguageCode == "es"),
                LanguageModel("ta", "Tamil", currentLanguageCode == "ta"),
                LanguageModel("th", "Thai", currentLanguageCode == "th"),
                LanguageModel("vi", "Vietnamese", currentLanguageCode == "vi")
            )
        }
    }
} 