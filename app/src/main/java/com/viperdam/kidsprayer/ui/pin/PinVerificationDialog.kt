package com.viperdam.kidsprayer.ui.pin

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AnimationUtils
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.databinding.DialogPinVerificationBinding
import com.viperdam.kidsprayer.security.PinManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class PinVerificationDialog : DialogFragment() {
    private var _binding: DialogPinVerificationBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var pinManager: PinManager

    private var currentPin = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isAnimating = false
    private var isVerifying = false
    var listener: (() -> Unit)? = null
    private var stateCheckJob: Job? = null
    private var cooldownJob: Job? = null

    companion object {
        const val TAG = "PinVerificationDialog"
        private const val MAX_PIN_LENGTH = 4
        private const val ANIMATION_DURATION = 500L
        private const val STATE_CHECK_INTERVAL = 100L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_KidsPrayer_Dialog)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPinVerificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupNumPad()
        startStateCheck()
        dialog?.setCanceledOnTouchOutside(true)
    }

    private fun startStateCheck() {
        stateCheckJob?.cancel()
        stateCheckJob = lifecycleScope.launch {
            while (true) {
                updateVerificationState()
                delay(STATE_CHECK_INTERVAL)
            }
        }
    }

    private suspend fun updateVerificationState() {
        try {
            val state = pinManager.getVerificationState()

            // Update UI based on state
            setInputEnabled(!isVerifying && state.canVerify)

            // Handle lockout state first
            if (state.isLocked) {
                startCooldownTimer(state.cooldownTimeMs)
            } else {
                cooldownJob?.cancel()
                // Only show attempts remaining if not locked
                if (state.attemptsRemaining < PinManager.MAX_VERIFICATIONS) {
                    binding.pinError.apply {
                        visibility = View.VISIBLE
                        text = getString(R.string.pin_attempts_remaining, state.attemptsRemaining)
                    }
                }
                if (!isVerifying && !isAnimating) {
                    clearError()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating verification state: ${e.message}")
        }
    }

    private fun startCooldownTimer(initialTimeMs: Long) {
        cooldownJob?.cancel()
        cooldownJob = lifecycleScope.launch {
            var remainingTime = initialTimeMs
            while (remainingTime > 0) {
                val seconds = (remainingTime / 1000.0).roundToInt()
                // Show locked message in pinError
                binding.pinError.apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.pin_locked_out)
                }
                // Show countdown in timeoutCounter
                binding.timeoutCounter.apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.pin_timeout_remaining, seconds)
                }
                delay(100)
                remainingTime -= 100
            }
            // When timer expires
            binding.pinError.text = getString(R.string.pin_timeout_expired)
            binding.timeoutCounter.visibility = View.GONE
        }
    }

    private fun setupNumPad() {
        with(binding) {
            // Number buttons
            btn0.setOnClickListener { onNumberClick("0") }
            btn1.setOnClickListener { onNumberClick("1") }
            btn2.setOnClickListener { onNumberClick("2") }
            btn3.setOnClickListener { onNumberClick("3") }
            btn4.setOnClickListener { onNumberClick("4") }
            btn5.setOnClickListener { onNumberClick("5") }
            btn6.setOnClickListener { onNumberClick("6") }
            btn7.setOnClickListener { onNumberClick("7") }
            btn8.setOnClickListener { onNumberClick("8") }
            btn9.setOnClickListener { onNumberClick("9") }

            btnClear.setOnClickListener {
                if (!isVerifying) {
                    currentPin = ""
                    updatePinDisplay()
                    clearError()
                }
            }

            btnDelete.setOnClickListener {
                if (!isVerifying && currentPin.isNotEmpty()) {
                    currentPin = currentPin.dropLast(1)
                    updatePinDisplay()
                    clearError()
                }
            }
        }
    }

    private fun onNumberClick(number: String) {
        if (!isAnimating && !isVerifying && currentPin.length < MAX_PIN_LENGTH) {
            currentPin += number
            updatePinDisplay()
            clearError()

            if (currentPin.length == MAX_PIN_LENGTH) {
                verifyPin()
            }
        }
    }

    private fun verifyPin() {
        if (isAnimating || isVerifying) return

        isVerifying = true
        setInputEnabled(false)

        lifecycleScope.launch {
            try {
                val isValid = pinManager.verifyPin(currentPin)
                if (isValid) {
                    // PIN is correct
                    listener?.invoke()
                    dismiss()
                } else {
                    // PIN is incorrect
                    val state = pinManager.getVerificationState()
                    if (state.isLocked) {
                        showError(getString(R.string.pin_locked_out))
                        startCooldownTimer(state.cooldownTimeMs)
                    } else {
                        showError(getString(R.string.pin_attempts_remaining, state.attemptsRemaining))
                    }
                    currentPin = ""
                    updatePinDisplay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying PIN: ${e.message}")
                showError(getString(R.string.pin_disabled))
                currentPin = ""
                updatePinDisplay()
            } finally {
                isVerifying = false
                updateVerificationState()
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.apply {
            btn0.isEnabled = enabled
            btn1.isEnabled = enabled
            btn2.isEnabled = enabled
            btn3.isEnabled = enabled
            btn4.isEnabled = enabled
            btn5.isEnabled = enabled
            btn6.isEnabled = enabled
            btn7.isEnabled = enabled
            btn8.isEnabled = enabled
            btn9.isEnabled = enabled
            btnClear.isEnabled = enabled
            btnDelete.isEnabled = enabled
        }
    }

    private fun updatePinDisplay() {
        binding.pinDisplay.text = "•".repeat(currentPin.length)
    }

    private fun showError(message: String) {
        if (!isAnimating) {
            isAnimating = true
            binding.timeoutCounter.visibility = View.GONE
            binding.pinError.apply {
                text = message
                visibility = View.VISIBLE
                startAnimation(AnimationUtils.loadAnimation(context, R.anim.shake))
            }
            handler.postDelayed({ 
                isAnimating = false 
            }, ANIMATION_DURATION)
        }
    }

    private fun clearError() {
        if (!isVerifying) {
            binding.pinError.visibility = View.GONE
            binding.timeoutCounter.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stateCheckJob?.cancel()
        cooldownJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stateCheckJob?.cancel()
        cooldownJob?.cancel()
    }
}
