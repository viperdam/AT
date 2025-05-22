package com.viperdam.kidsprayer.ui.lock.pin

import android.view.View
import com.viperdam.kidsprayer.databinding.ActivityLockScreenBinding
import com.viperdam.kidsprayer.ui.lock.LockScreenViewModel
import android.os.SystemClock

class LockScreenPin(
    private val binding: ActivityLockScreenBinding,
    private val viewModel: LockScreenViewModel
) {
    companion object {
        private const val MAX_PIN_LENGTH = 4 // Changed to match PinManager's requirement
    }

    private val pinBuilder = StringBuilder()
    private var lastStateUpdate = 0L
    private val MIN_STATE_UPDATE_INTERVAL = 300L // Prevent rapid state changes

    fun setupPinUI() {
        binding.apply {
            val handleButtonClick = { digit: Char ->
                val currentTime = SystemClock.elapsedRealtime()
                // Prevent rapid state changes and ensure proper state
                if (!viewModel.uiState.value.isLockedOut && 
                    viewModel.uiState.value.pinEnabled &&
                    currentTime - lastStateUpdate >= MIN_STATE_UPDATE_INTERVAL) {
                    
                    lastStateUpdate = currentTime
                    pinEntryCard.requestFocus()
                    handlePinInput(digit)
                }
            }

            // Setup button click listeners with state validation
            listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9).forEach { button ->
                button.setOnClickListener { 
                    if (button.isEnabled) {
                        handleButtonClick(button.tag.toString().first())
                    }
                }
            }
            
            btnClear.setOnClickListener { 
                val currentTime = SystemClock.elapsedRealtime()
                if (!viewModel.uiState.value.isLockedOut && 
                    viewModel.uiState.value.pinEnabled &&
                    currentTime - lastStateUpdate >= MIN_STATE_UPDATE_INTERVAL) {
                    
                    lastStateUpdate = currentTime
                    pinEntryCard.requestFocus()
                    clearPin()
                }
            }
            
            updatePinDisplay()
        }
    }

    private fun handlePinInput(digit: Char) {
        if (pinBuilder.length < MAX_PIN_LENGTH) {
            pinBuilder.append(digit)
            updatePinDisplay()

            if (pinBuilder.length == MAX_PIN_LENGTH) {
                verifyPin()
            }
        }
    }

    private fun clearPin() {
        pinBuilder.clear()
        updatePinDisplay()
        binding.pinError.visibility = View.GONE
    }

    private fun updatePinDisplay() {
        binding.apply {
            pinDisplay.text = "*".repeat(pinBuilder.length)
            pinError.visibility = View.GONE
        }
    }

    private fun verifyPin() {
        val pin = pinBuilder.toString()
        if (pin.length == MAX_PIN_LENGTH && pin.all { it.isDigit() }) {
            // Get context from binding's root view
            val context = binding.root.context
            val activity = context as? android.app.Activity
            viewModel.verifyPin(pin)
        } else {
            binding.pinError.apply {
                text = "PIN must be 4 digits"
                visibility = View.VISIBLE
            }
        }
        clearPin()
    }

    fun updatePinVisibility(shouldShow: Boolean) {
        binding.apply {
            pinEntryCard.visibility = if (shouldShow) View.VISIBLE else View.GONE
            
            // Reset state when showing PIN entry
            if (shouldShow) {
                clearPin()
                pinError.visibility = View.GONE
                updateButtonsState(true)
            }
        }
    }

    fun updateButtonsState(enabled: Boolean) {
        binding.apply {
            listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9, btnClear).forEach { button ->
                button.isEnabled = enabled && !viewModel.uiState.value.isLockedOut
            }

            // Show cooldown message if locked out
            if (viewModel.uiState.value.isLockedOut) {
                pinError.apply {
                    text = "Please wait ${viewModel.uiState.value.pinCooldownSeconds} seconds"
                    visibility = View.VISIBLE
                }
            } else {
                pinError.visibility = if (viewModel.uiState.value.errorMessage != null) View.VISIBLE else View.GONE
                pinError.text = viewModel.uiState.value.errorMessage
            }
        }
    }

    fun updateErrorMessage(message: String?) {
        binding.apply {
            pinError.apply {
                if (message != null) {
                    text = message
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            
            // Ensure buttons are in correct state
            val buttonsEnabled = !viewModel.uiState.value.isLockedOut && 
                               viewModel.uiState.value.pinEnabled
            
            listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9, btnClear)
                .forEach { it.isEnabled = buttonsEnabled }
        }
    }
}
