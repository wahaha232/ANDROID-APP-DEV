// [Handled & Written by Cloud Agent]
package com.wahaha232.calculator

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.wahaha232.calculator.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val calculator = CalculatorEngine()

    private val currencies = listOf("TWD", "USD", "JPY", "HKD", "CNY", "EUR", "GBP")
    private var ratesVsUsd: Map<String, Double>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        binding.adView.loadAd(AdRequest.Builder().build())

        setupCalculator()
        setupConvertPage()
        setupTabs()
        setupLanguageToggle()
        showTab(isCalculator = true)

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

    // ---------- Tabs ----------

    private fun setupTabs() {
        binding.tabCalculator.setOnClickListener { showTab(isCalculator = true) }
        binding.tabConvert.setOnClickListener { showTab(isCalculator = false) }
    }

    private fun showTab(isCalculator: Boolean) {
        binding.calculatorContainer.visibility = if (isCalculator) android.view.View.VISIBLE else android.view.View.GONE
        binding.convertContainer.visibility = if (isCalculator) android.view.View.GONE else android.view.View.VISIBLE

        binding.tabCalculator.setTextColor(
            getColor(if (isCalculator) R.color.colorDisplayText else R.color.colorExpressionText)
        )
        binding.tabConvert.setTextColor(
            getColor(if (isCalculator) R.color.colorExpressionText else R.color.colorDisplayText)
        )
        binding.tabCalculator.setTypeface(null, if (isCalculator) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabConvert.setTypeface(null, if (isCalculator) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
    }

    // ---------- Language toggle ----------

    private fun setupLanguageToggle() {
        binding.btnLangToggle.setOnClickListener {
            val current = AppCompatDelegate.getApplicationLocales()
            val currentTag = if (current.isEmpty) "zh-TW" else current[0]?.toLanguageTag() ?: "zh-TW"
            val next = when {
                currentTag.startsWith("en") -> "ja"
                currentTag.startsWith("ja") -> "zh-TW"
                else -> "en"
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        }
    }

    // ---------- Calculator ----------

    private fun setupCalculator() {
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
        binding.btnAdd.setOnClickListener { onOperator(CalculatorOperator.PLUS) }
        binding.btnSubtract.setOnClickListener { onOperator(CalculatorOperator.MINUS) }
        binding.btnMultiply.setOnClickListener { onOperator(CalculatorOperator.MULTIPLY) }
        binding.btnDivide.setOnClickListener { onOperator(CalculatorOperator.DIVIDE) }
        binding.btnEquals.setOnClickListener { onEquals() }
    }

    private fun onDigit(digit: String) {
        calculator.inputDigit(digit[0])
        updateDisplay()
    }

    private fun onDot() {
        calculator.inputDot()
        updateDisplay()
    }

    private fun onClear() {
        calculator.clear()
        updateDisplay()
    }

    private fun onBackspace() {
        calculator.backspace()
        updateDisplay()
    }

    private fun onToggleSign() {
        calculator.toggleSign()
        updateDisplay()
    }

    private fun onPercent() {
        calculator.percent()
        updateDisplay()
    }

    private fun onOperator(op: CalculatorOperator) {
        calculator.applyOperator(op)
        updateDisplay()
    }

    private fun onEquals() {
        calculator.equals()
        updateDisplay()
    }

    private fun updateDisplay() {
        binding.tvDisplay.text = if (calculator.isError) {
            getString(R.string.error_message)
        } else {
            calculator.display
        }
        binding.tvExpression.text = calculator.expression
    }

    // ---------- Currency convert ----------

    private fun setupConvertPage() {
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, currencies
        )
        binding.spinnerFrom.adapter = adapter
        binding.spinnerTo.adapter = adapter
        binding.spinnerFrom.setSelection(currencies.indexOf("USD"))
        binding.spinnerTo.setSelection(currencies.indexOf("TWD"))

        val onChange = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) = updateConvertResult()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerFrom.onItemSelectedListener = onChange
        binding.spinnerTo.onItemSelectedListener = onChange

        binding.btnSwap.setOnClickListener {
            val from = binding.spinnerFrom.selectedItemPosition
            val to = binding.spinnerTo.selectedItemPosition
            binding.spinnerFrom.setSelection(to)
            binding.spinnerTo.setSelection(from)
        }

        binding.etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateConvertResult()
        })

        loadRates()
    }

    private fun loadRates() {
        binding.tvStatus.text = getString(R.string.status_loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRates() }
            if (result == null) {
                binding.tvStatus.text = getString(R.string.status_error)
            } else {
                ratesVsUsd = result.first
                binding.tvStatus.text = getString(R.string.status_updated, result.second)
                updateConvertResult()
            }
        }
    }

    private fun fetchRates(): Pair<Map<String, Double>, String>? {
        return try {
            val connection = URL("https://open.er-api.com/v6/latest/USD")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(text)
            val ratesJson = json.getJSONObject("rates")
            val map = mutableMapOf<String, Double>()
            for (code in currencies) {
                map[code] = ratesJson.getDouble(code)
            }
            val updatedAt = json.optString("time_last_update_utc", "")
            map.toMap() to updatedAt
        } catch (e: Exception) {
            null
        }
    }

    private fun updateConvertResult() {
        val rates = ratesVsUsd ?: return
        val amount = binding.etAmount.text.toString().toDoubleOrNull()
        if (amount == null) {
            binding.tvResult.text = "0"
            return
        }
        val fromCode = currencies[binding.spinnerFrom.selectedItemPosition]
        val toCode = currencies[binding.spinnerTo.selectedItemPosition]
        val fromRate = rates[fromCode]
        val toRate = rates[toCode]
        if (fromRate == null || toRate == null) return

        val amountInUsd = amount / fromRate
        val result = amountInUsd * toRate
        binding.tvResult.text = formatConvertResult(result)
    }

    private fun formatConvertResult(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.4f", value).trimEnd('0').trimEnd('.')
        }
    }
}
