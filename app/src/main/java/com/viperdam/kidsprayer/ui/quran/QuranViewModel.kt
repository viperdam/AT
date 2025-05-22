package com.viperdam.kidsprayer.ui.quran

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdelhakim.holyquran.HolyQuran
import com.abdelhakim.holyquran.Surah
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "QuranViewModel"

data class QuranUiState(
    val surahs: List<Surah> = emptyList(),
    val selectedSurah: Surah? = null,
    val verses: List<String> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuranViewModel @Inject constructor() : ViewModel() {

    private val holyQuran = HolyQuran()

    private val _uiState = MutableStateFlow(QuranUiState(isLoading = true))
    val uiState: StateFlow<QuranUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "QuranViewModel initialized. Loading Surah List from Library...")
        loadSurahs()
    }

    private fun loadSurahs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val allSurahs = holyQuran.hafsVersion
                _uiState.update {
                    it.copy(
                        surahs = allSurahs,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load Surahs: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun searchSurahs(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val filteredSurahs = if (query.isBlank()) {
                holyQuran.hafsVersion
            } else {
                holyQuran.hafsVersion.filter { surah: Surah ->
                    surah.englishName.contains(query, ignoreCase = true) ||
                    surah.name.contains(query, ignoreCase = true) ||
                    surah.number.toString().contains(query)
                }
            }
            _uiState.update { it.copy(surahs = filteredSurahs) }
        }
    }

    fun selectSurah(surah: Surah) {
        _uiState.update {
            it.copy(
                selectedSurah = surah,
                verses = surah.verses.toList()
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedSurah = null,
                verses = emptyList()
            )
        }
    }

    fun getSurahByNumber(number: Int): Surah? {
        return holyQuran.hafsVersion.find { surah: Surah -> surah.number == number }
    }

    fun getSurahDetails(surahNumber: Int): Surah? {
        return holyQuran.hafsVersion.getOrNull(surahNumber - 1)
    }

    fun getVersesForSurah(surahNumber: Int): List<String> {
        val surah = getSurahDetails(surahNumber)
        return surah?.verses?.toList() ?: emptyList()
    }

    fun searchVerses(surahNumber: Int, query: String) {
        val surah = getSurahDetails(surahNumber)
        if (surah != null) {
            val filteredVerses = if (query.isBlank()) {
                surah.verses.toList()
            } else {
                surah.verses.filter { verse: String ->
                    verse.contains(query, ignoreCase = true)
                }
            }
            _uiState.update { it.copy(verses = filteredVerses) }
        }
    }
} 