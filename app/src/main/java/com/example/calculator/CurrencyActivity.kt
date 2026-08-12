package com.example.calculator

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calculator.databinding.ActivityCurrencyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CurrencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCurrencyBinding

    private val currencies = listOf("TWD", "USD", "JPY", "HKD", "CNY", "EUR", "GBP")
    private var ratesVsUsd: Map<String, Double>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCurrencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

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
            ) = updateResult()

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
            override fun afterTextChanged(s: Editable?) = updateResult()
        })

        loadRates()
    }

    private fun loadRates() {
        binding.tvStatus.text = "讀取匯率中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRates() }
            if (result == null) {
                binding.tvStatus.text = "匯率讀取失敗，請檢查網路連線後重新開啟此頁面"
            } else {
                ratesVsUsd = result.first
                binding.tvStatus.text = "匯率更新時間：${result.second}（資料來源：exchangerate-api.com）"
                updateResult()
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

    private fun updateResult() {
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
        binding.tvResult.text = formatResult(result)
    }

    private fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.4f", value).trimEnd('0').trimEnd('.')
        }
    }
}
