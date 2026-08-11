package com.example.calculator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.calculator.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentInput = "0"
    private var previousValue: Double? = null
    private var pendingOperator: Char? = null
    private var justEvaluated = false
    private var awaitingNewOperand = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        binding.adView.loadAd(AdRequest.Builder().build())

        val digitButtons = listOf(
            binding.btn0 to "0", binding.btn1 to "1", binding.btn2 to "2",
            binding.btn3 to "3", binding.btn4 to "4", binding.btn5 to "5",
            binding.btn6 to "6", binding.btn7 to "7", binding.btn8 to "8",
            binding.btn9 to "9"
        )
        digitButtons.forEach { (button, digit) ->
            button.setOnClickListener { onDigit(digit) }
        }

        binding.btnDot.setOnClickListener { onDot() }
        binding.btnClear.setOnClickListener { onClear() }
        binding.btnBackspace.setOnClickListener { onBackspace() }
        binding.btnSign.setOnClickListener { onToggleSign() }
        binding.btnPercent.setOnClickListener { onPercent() }
        binding.btnAdd.setOnClickListener { onOperator('+') }
        binding.btnSubtract.setOnClickListener { onOperator('-') }
        binding.btnMultiply.setOnClickListener { onOperator('×') }
        binding.btnDivide.setOnClickListener { onOperator('÷') }
        binding.btnEquals.setOnClickListener { onEquals() }

        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        binding.adView.resume()
    }

    override fun onPause() {
        binding.adView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.adView.destroy()
        super.onDestroy()
    }

    private fun onDigit(digit: String) {
        if (justEvaluated || awaitingNewOperand) {
            currentInput = "0"
            justEvaluated = false
            awaitingNewOperand = false
        }
        currentInput = if (currentInput == "0") digit else currentInput + digit
        updateDisplay()
    }

    private fun onDot() {
        if (justEvaluated || awaitingNewOperand) {
            currentInput = "0"
            justEvaluated = false
            awaitingNewOperand = false
        }
        if (!currentInput.contains(".")) {
            currentInput += "."
            updateDisplay()
        }
    }

    private fun onClear() {
        currentInput = "0"
        previousValue = null
        pendingOperator = null
        justEvaluated = false
        awaitingNewOperand = false
        updateDisplay()
    }

    private fun onBackspace() {
        if (justEvaluated || awaitingNewOperand) {
            onClear()
            return
        }
        currentInput = if (currentInput.length > 1) currentInput.dropLast(1) else "0"
        updateDisplay()
    }

    private fun onToggleSign() {
        currentInput = when {
            currentInput.startsWith("-") -> currentInput.substring(1)
            currentInput != "0" -> "-$currentInput"
            else -> currentInput
        }
        updateDisplay()
    }

    private fun onPercent() {
        val value = currentInput.toDoubleOrNull() ?: return
        currentInput = formatNumber(value / 100.0)
        updateDisplay()
    }

    private fun onOperator(op: Char) {
        val currentValue = currentInput.toDoubleOrNull() ?: return

        if (pendingOperator != null && !awaitingNewOperand) {
            val result = applyOperator(previousValue ?: 0.0, currentValue, pendingOperator!!)
            if (result == null) {
                showError()
                return
            }
            previousValue = result
        } else if (previousValue == null) {
            previousValue = currentValue
        }

        pendingOperator = op
        awaitingNewOperand = true
        justEvaluated = false
        updateDisplay()
    }

    private fun onEquals() {
        val op = pendingOperator
        val prev = previousValue
        val currentValue = currentInput.toDoubleOrNull()
        if (op == null || prev == null || currentValue == null) return

        val result = applyOperator(prev, currentValue, op)
        if (result == null) {
            showError()
            return
        }

        currentInput = formatNumber(result)
        previousValue = null
        pendingOperator = null
        awaitingNewOperand = false
        justEvaluated = true
        updateDisplay()
    }

    private fun applyOperator(a: Double, b: Double, op: Char): Double? {
        return when (op) {
            '+' -> a + b
            '-' -> a - b
            '×' -> a * b
            '÷' -> if (b == 0.0) null else a / b
            else -> null
        }
    }

    private fun showError() {
        currentInput = "0"
        previousValue = null
        pendingOperator = null
        awaitingNewOperand = false
        justEvaluated = true
        binding.tvExpression.text = ""
        binding.tvDisplay.text = getString(R.string.error_message)
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.10f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun updateDisplay() {
        binding.tvDisplay.text = currentInput
        binding.tvExpression.text = if (pendingOperator != null && previousValue != null) {
            "${formatNumber(previousValue!!)} $pendingOperator"
        } else {
            ""
        }
    }
}
