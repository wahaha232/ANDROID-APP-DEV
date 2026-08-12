package com.example.calculator

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.calculator.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentInput = "0"
    private val tokens = mutableListOf<Any>() // alternating Double operand / Char operator
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
        binding.btnCurrency.setOnClickListener {
            startActivity(Intent(this, CurrencyActivity::class.java))
        }

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
        tokens.clear()
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
        val value = currentInput.toDoubleOrNull() ?: return

        if (justEvaluated) {
            tokens.clear()
            tokens.add(value)
            tokens.add(op)
        } else if (awaitingNewOperand && tokens.isNotEmpty()) {
            // user changed their mind about the operator; just swap it
            tokens[tokens.size - 1] = op
        } else {
            tokens.add(value)
            tokens.add(op)
        }

        awaitingNewOperand = true
        justEvaluated = false
        currentInput = "0"
        updateDisplay()
    }

    private fun onEquals() {
        if (tokens.isEmpty()) return
        val value = currentInput.toDoubleOrNull() ?: return

        val fullExpression = tokens.toMutableList()
        fullExpression.add(value)

        val result = evaluateExpression(fullExpression)
        if (result == null) {
            showError()
            return
        }

        currentInput = formatNumber(result)
        tokens.clear()
        awaitingNewOperand = false
        justEvaluated = true
        updateDisplay()
    }

    /** Evaluates a flat [operand, op, operand, op, ...] list with × and ÷ resolved before + and -. */
    private fun evaluateExpression(expression: List<Any>): Double? {
        val reduced = mutableListOf<Any>(expression[0] as Double)
        var i = 1
        while (i < expression.size) {
            val op = expression[i] as Char
            val operand = expression[i + 1] as Double
            if (op == '×' || op == '÷') {
                val left = reduced.removeAt(reduced.size - 1) as Double
                if (op == '÷' && operand == 0.0) return null
                reduced.add(if (op == '×') left * operand else left / operand)
            } else {
                reduced.add(op)
                reduced.add(operand)
            }
            i += 2
        }

        var result = reduced[0] as Double
        i = 1
        while (i < reduced.size) {
            val op = reduced[i] as Char
            val operand = reduced[i + 1] as Double
            result = if (op == '+') result + operand else result - operand
            i += 2
        }
        return result
    }

    private fun showError() {
        currentInput = "0"
        tokens.clear()
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
        binding.tvExpression.text = tokens.joinToString(" ") { token ->
            if (token is Double) formatNumber(token) else token.toString()
        }
    }
}
