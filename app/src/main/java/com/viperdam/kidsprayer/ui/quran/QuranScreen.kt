package com.viperdam.kidsprayer.ui.quran

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.abdelhakim.holyquran.Surah
import com.viperdam.kidsprayer.R
import android.util.Log

private const val TAG = "QuranScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranScreen(
    modifier: Modifier = Modifier,
    viewModel: QuranViewModel = hiltViewModel(),
    onNavigateToSurahDetail: (Int) -> Unit
) {
    Log.d(TAG, "QuranScreen Composable executing.")

    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.searchSurahs(it) },
            label = { Text(stringResource(R.string.search_surah)) },
            modifier = Modifier
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.error_loading_data_message, uiState.error ?: "Unknown error"))
            }
        } else {
            SurahList(surahs = uiState.surahs, onSurahClick = { surah ->
                viewModel.selectSurah(surah)
                onNavigateToSurahDetail(surah.number)
            })
        }
    }
}

@Composable
fun SurahList(surahs: List<Surah>, onSurahClick: (Surah) -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
        items(surahs, key = { surah -> surah.number }) { surah ->
            SurahItem(surah = surah, onClick = { onSurahClick(surah) })
            HorizontalDivider()
        }
    }
}

@Composable
fun SurahItem(surah: Surah, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("${surah.number}. ${surah.englishName}", style = MaterialTheme.typography.bodyLarge)
        Text(surah.name, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- Surah Detail Screen (Example Structure) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurahDetailScreen(
    surahNumber: Int,
    viewModel: QuranViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedSurah = uiState.selectedSurah

    LaunchedEffect(surahNumber) {
        if (selectedSurah?.number != surahNumber) {
            viewModel.getSurahDetails(surahNumber)?.let {
                viewModel.selectSurah(it)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedSurah?.englishName ?: stringResource(R.string.surah_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            if (selectedSurah?.number != surahNumber || uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.searchVerses(surahNumber, it) },
                    label = { Text(stringResource(R.string.search_verses)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )

                VerseList(verses = uiState.verses)
            }
        }
    }
}

@Composable
fun VerseList(verses: List<String>) {
    LazyColumn {
        items(verses.size) { index ->
            VerseItem(verseNumber = index + 1, verseText = verses[index])
            HorizontalDivider()
        }
    }
}

@Composable
fun VerseItem(verseNumber: Int, verseText: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("$verseNumber. $verseText", style = MaterialTheme.typography.bodyMedium)
    }
}