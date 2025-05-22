package com.viperdam.kidsprayer.ui.prayers

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.adapters.PrayerTimesAdapter
import com.viperdam.kidsprayer.databinding.DailyPrayersLayoutBinding
import com.viperdam.kidsprayer.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DailyPrayersActivity : AppCompatActivity() {

    private lateinit var binding: DailyPrayersLayoutBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: PrayerTimesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DailyPrayersLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupObservers()
        setupClickListeners()
        
        // Immediately refresh prayer times when activity starts
        viewModel.refreshPrayerTimes()
    }

    private fun setupRecyclerView() {
        adapter = PrayerTimesAdapter()
        binding.prayerTimesList.layoutManager = LinearLayoutManager(this)
        binding.prayerTimesList.adapter = adapter
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                binding.errorMessage.visibility = if (state.error != null) View.VISIBLE else View.GONE
                binding.swipeRefresh.isRefreshing = false
                
                if (state.error != null) {
                    binding.errorMessage.text = state.error
                }
                
                adapter.submitList(state.prayers)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshPrayerTimes()
        }
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }
} 