package com.viperdam.kidsprayer.ui.pin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.viperdam.kidsprayer.databinding.DialogPinSetupBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.viperdam.kidsprayer.R

class PinSetupDialog : BottomSheetDialogFragment() {
    private var _binding: DialogPinSetupBinding? = null
    private val binding get() = _binding!!

    private var onPinConfirmed: ((String) -> Unit)? = null

    private var currentPin = ""
    private var confirmPin = ""
    private var isConfirmationMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_KidsPrayer_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPinSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set up bottom sheet behavior
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
        
        setupNumPad()
        binding.pinTitle.text = getString(R.string.pin_setup)
    }

    private fun setupNumPad() {
        with(binding) {
            // Set up number buttons
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

            // Set up clear button
            btnClear.setOnClickListener { 
                if (!isConfirmationMode) {
                    currentPin = ""
                } else {
                    confirmPin = ""
                }
                updatePinDisplay()
            }

            // Set up delete button
            btnDelete.setOnClickListener {
                if (!isConfirmationMode && currentPin.isNotEmpty()) {
                    currentPin = currentPin.dropLast(1)
                } else if (isConfirmationMode && confirmPin.isNotEmpty()) {
                    confirmPin = confirmPin.dropLast(1)
                }
                updatePinDisplay()
            }
        }
    }

    private fun onNumberClick(number: String) {
        if (!isConfirmationMode) {
            if (currentPin.length < 4) {
                currentPin += number
                updatePinDisplay()
                if (currentPin.length == 4) {
                    isConfirmationMode = true
                    binding.pinTitle.text = getString(R.string.confirm_pin)
                    binding.pinDisplay.text = ""
                }
            }
        } else {
            if (confirmPin.length < 4) {
                confirmPin += number
                updatePinDisplay()
                if (confirmPin.length == 4) {
                    verifyPins()
                }
            }
        }
    }

    private fun updatePinDisplay() {
        val pin = if (!isConfirmationMode) currentPin else confirmPin
        binding.pinDisplay.text = "•".repeat(pin.length)
        binding.pinError.visibility = View.GONE
    }

    private fun verifyPins() {
        if (currentPin == confirmPin) {
            onPinConfirmed?.invoke(currentPin)
            dismiss()
        } else {
            // Reset and show error
            isConfirmationMode = false
            currentPin = ""
            confirmPin = ""
            binding.pinTitle.text = getString(R.string.pin_setup)
            binding.pinError.visibility = View.VISIBLE
            binding.pinError.text = getString(R.string.pin_mismatch)
            updatePinDisplay()
        }
    }

    fun setOnPinConfirmedListener(listener: (String) -> Unit) {
        onPinConfirmed = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
