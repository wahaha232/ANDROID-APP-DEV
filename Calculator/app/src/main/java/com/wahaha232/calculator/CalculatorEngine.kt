// [Handled & Written by Cloud Agent]
package com.wahaha232.calculator

import java.util.Locale

enum class CalculatorOperator(val symbol: Char) {
    PLUS('+'),
    MINUS('-'),
    MULTIPLY('×'),
    DIVIDE('÷')
}

sealed interface CalculatorToken

data class Operand(val value: Double) : CalculatorToken

data class OperatorToken(val operator: CalculatorOperator) : CalculatorToken

/**
 * Pure calculator state machine. It has no Android dependencies so the whole
 * input/evaluation behaviour can be covered by JVM unit tests.
 */
class CalculatorEngine {

    private val tokens = mutableListOf<CalculatorToken>()
    private var input = "0"
    private var justEvaluated = false
    private var awaitingOperand = false
    private var error = false

    val display: String
        get() = if (error) "0" else input

    val expression: String
        get() {
            if (tokens.isEmpty()) return ""
            val pending = tokens.joinToString(" ") { token ->
                when (token) {
                    is Operand -> formatNumber(token.value)
                    is OperatorToken -> token.operator.symbol.toString()
                }
            }
            return if (!awaitingOperand && !justEvaluated) "$pending $input" else pending
        }

    val isError: Boolean
        get() = error

    fun inputDigit(digit: Char) {
        if (!digit.isDigit()) return
        startFreshInputIfNeeded()
        input = if (input == "0") digit.toString() else input + digit
        error = false
    }

    fun inputDot() {
        startFreshInputIfNeeded()
        if (!input.contains('.')) {
            input += "."
        }
        error = false
    }

    fun clear() {
        input = "0"
        tokens.clear()
        justEvaluated = false
        awaitingOperand = false
        error = false
    }

    fun backspace() {
        if (justEvaluated || awaitingOperand) {
            clear()
            return
        }
        input = if (input.length > 1) input.dropLast(1) else "0"
        if (input == "-" || input.isEmpty()) {
            input = "0"
        }
        error = false
    }

    fun toggleSign() {
        input = when {
            input.startsWith("-") -> input.substring(1)
            input != "0" -> "-$input"
            else -> input
        }
        error = false
    }

    fun percent() {
        val value = input.toDoubleOrNull() ?: return
        input = formatNumber(value / 100.0)
        error = false
    }

    fun applyOperator(operator: CalculatorOperator) {
        val value = input.toDoubleOrNull() ?: return

        when {
            justEvaluated -> {
                tokens.clear()
                tokens.add(Operand(value))
                tokens.add(OperatorToken(operator))
            }
            awaitingOperand && tokens.isNotEmpty() -> {
                tokens[tokens.lastIndex] = OperatorToken(operator)
            }
            else -> {
                tokens.add(Operand(value))
                tokens.add(OperatorToken(operator))
            }
        }

        awaitingOperand = true
        justEvaluated = false
        input = "0"
        error = false
    }

    fun equals() {
        if (tokens.isEmpty()) return
        val value = input.toDoubleOrNull() ?: return

        val result = evaluate(tokens + Operand(value))
        if (result == null) {
            input = "0"
            tokens.clear()
            awaitingOperand = false
            justEvaluated = true
            error = true
            return
        }

        input = formatNumber(result)
        tokens.clear()
        awaitingOperand = false
        justEvaluated = true
        error = false
    }

    private fun startFreshInputIfNeeded() {
        if (justEvaluated || awaitingOperand) {
            input = "0"
            justEvaluated = false
            awaitingOperand = false
        }
    }

    /** Resolves × and ÷ before + and -, applying equal precedence left to right. */
    private fun evaluate(expression: List<CalculatorToken>): Double? {
        if (expression.isEmpty() || expression.size % 2 == 0) return null

        val reduced = mutableListOf<CalculatorToken>()
        reduced.add(expression[0] as? Operand ?: return null)

        var index = 1
        while (index < expression.size) {
            val operator = (expression[index] as? OperatorToken)?.operator ?: return null
            val operand = (expression.getOrNull(index + 1) as? Operand)?.value ?: return null

            if (operator == CalculatorOperator.MULTIPLY || operator == CalculatorOperator.DIVIDE) {
                val left = (reduced.lastOrNull() as? Operand)?.value ?: return null
                if (operator == CalculatorOperator.DIVIDE && operand == 0.0) return null
                val value = if (operator == CalculatorOperator.MULTIPLY) {
                    left * operand
                } else {
                    left / operand
                }
                reduced[reduced.lastIndex] = Operand(value)
            } else {
                reduced.add(OperatorToken(operator))
                reduced.add(Operand(operand))
            }
            index += 2
        }

        var result = (reduced.getOrNull(0) as? Operand)?.value ?: return null
        index = 1
        while (index < reduced.size) {
            val operator = (reduced[index] as? OperatorToken)?.operator ?: return null
            val operand = (reduced.getOrNull(index + 1) as? Operand)?.value ?: return null
            result = when (operator) {
                CalculatorOperator.PLUS -> result + operand
                CalculatorOperator.MINUS -> result - operand
                else -> return null
            }
            index += 2
        }

        return result.takeIf { it.isFinite() }
    }

    companion object {
        fun formatNumber(value: Double): String {
            if (!value.isFinite()) return "0"
            if (value == value.toLong().toDouble()) {
                return value.toLong().toString()
            }
            return String.format(Locale.US, "%.10f", value)
                .trimEnd('0')
                .trimEnd('.')
        }
    }
}
