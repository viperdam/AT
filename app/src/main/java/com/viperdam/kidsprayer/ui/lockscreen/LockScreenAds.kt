package com.viperdam.kidsprayer.ui.lockscreen

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.LoadAdError
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ads.AdManager
import com.viperdam.kidsprayer.databinding.FragmentLockScreenAdsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LockScreenAds : Fragment() {

    @Inject
    lateinit var adManager: AdManager

    private var _binding: FragmentLockScreenAdsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLockScreenAdsBinding.inflate(inflater, container, false)
        setupObservers()
        return binding.root
    }

    private fun setupObservers() {
        adManager.setRewardedAdCallback(object : AdManager.RewardedAdCallback {
            override fun onAdLoaded() {
                Log.d("LockScreenAds", "Ad loaded")
            }

            override fun onAdFailedToLoad(errorMessage: String?) {
                Log.d("LockScreenAds", "Ad failed to load: $errorMessage")
            }

            override fun onAdShown() {
                Log.d("LockScreenAds", "Ad shown")
            }

            override fun onAdDismissed() {
                Log.d("LockScreenAds", "Ad dismissed")
            }

            override fun onAdFailedToShow(errorMessage: String?) {
                Log.d("LockScreenAds", "Ad failed to show: $errorMessage")
            }

            override fun onAdClicked() {
                Log.d("LockScreenAds", "Ad clicked")
            }

            override fun onUserEarnedReward(amount: Int) {
                Log.d("LockScreenAds", "User earned reward: $amount")
            }
        })

        // Observe ad loading state
        lifecycleScope.launch {
            adManager.isLoading.collect { isLoading ->
                binding.adLoadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Observe ad errors or other states if needed
        lifecycleScope.launch {
            adManager.isRewardedAdLoaded.collect { isLoaded ->
                binding.adErrorLayout.visibility = View.GONE
                if (isLoaded) {
                    Log.d("LockScreenAds", "Ad Loaded")
                    // You can show the ad here if needed
                    // adManager.showRewardedAd(requireActivity())
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.d("LockScreenAds", "LockScreenAds onDestroyView, binding set to null")
    }
} 