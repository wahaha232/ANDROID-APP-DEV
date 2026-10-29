// [Handled & Written by Cloud Agent]
package com.wahaha232.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEngineTest {

    private val engine = CalculatorEngine()

    private fun digits(text: String) {
        text.forEach { engine.inputDigit(it) }
    }

    private fun operator(operator: CalculatorOperator) {
        engine.applyOperator(operator)
    }

    @Test
    fun initialStateShowsZero() {
        assertEquals("0", engine.display)
        assertEquals("", engine.expression)
        assertFalse(engine.isError)
    }

    @Test
    fun digitsReplaceTheLeadingZero() {
        digits("0")
        assertEquals("0", engine.display)

        digits("5")
        assertEquals("5", engine.display)

        digits("6")
        assertEquals("56", engine.display)
    }

    @Test
    fun onlyOneDecimalPointIsAccepted() {
        digits("1")
        engine.inputDot()
        digits("5")
        engine.inputDot()
        assertEquals("1.5", engine.display)
    }

    @Test
    fun operatorShowsThePendingExpression() {
        digits("5")
        operator(CalculatorOperator.PLUS)
        digits("3")
        operator(CalculatorOperator.MULTIPLY)
        digits("2")

        assertEquals("2", engine.display)
        assertEquals("5 + 3 × 2", engine.expression)
    }

    @Test
    fun pendingOperatorDoesNotShowAnEmptyOperand() {
        digits("5")
        operator(CalculatorOperator.PLUS)

        assertEquals("5 +", engine.expression)
    }

    @Test
    fun multiplicationIsEvaluatedBeforeAddition() {
        digits("5")
        operator(CalculatorOperator.PLUS)
        digits("3")
        operator(CalculatorOperator.MULTIPLY)
        digits("2")
        engine.equals()

        assertEquals("11", engine.display)
        assertEquals("", engine.expression)
        assertFalse(engine.isError)
    }

    @Test
    fun divisionIsEvaluatedBeforeSubtraction() {
        digits("10")
        operator(CalculatorOperator.MINUS)
        digits("4")
        operator(CalculatorOperator.DIVIDE)
        digits("2")
        engine.equals()

        assertEquals("8", engine.display)
    }

    @Test
    fun equalPrecedenceIsEvaluatedLeftToRight() {
        digits("8")
        operator(CalculatorOperator.DIVIDE)
        digits("4")
        operator(CalculatorOperator.DIVIDE)
        digits("2")
        engine.equals()
        assertEquals("1", engine.display)

        val subtractive = CalculatorEngine()
        "10".forEach { subtractive.inputDigit(it) }
        subtractive.applyOperator(CalculatorOperator.MINUS)
        "3".forEach { subtractive.inputDigit(it) }
        subtractive.applyOperator(CalculatorOperator.MINUS)
        "2".forEach { subtractive.inputDigit(it) }
        subtractive.equals()
        assertEquals("5", subtractive.display)
    }

    @Test
    fun divisionByZeroEntersErrorState() {
        digits("8")
        operator(CalculatorOperator.DIVIDE)
        digits("0")
        engine.equals()

        assertTrue(engine.isError)
        assertEquals("0", engine.display)
        assertEquals("", engine.expression)
    }

    @Test
    fun digitClearsTheErrorState() {
        digits("8")
        operator(CalculatorOperator.DIVIDE)
        digits("0")
        engine.equals()
        assertTrue(engine.isError)

        digits("7")
        assertFalse(engine.isError)
        assertEquals("7", engine.display)
    }

    @Test
    fun operatorAfterEqualsContinuesFromTheResult() {
        digits("2")
        operator(CalculatorOperator.PLUS)
        digits("3")
        engine.equals()
        assertEquals("5", engine.display)

        operator(CalculatorOperator.PLUS)
        digits("4")
        engine.equals()
        assertEquals("9", engine.display)
    }

    @Test
    fun digitAfterEqualsStartsANewCalculation() {
        digits("2")
        operator(CalculatorOperator.PLUS)
        digits("3")
        engine.equals()
        assertEquals("5", engine.display)

        digits("7")
        assertEquals("7", engine.display)
        assertEquals("", engine.expression)
    }

    @Test
    fun changingTheOperatorReplacesThePendingOne() {
        digits("5")
        operator(CalculatorOperator.PLUS)
        operator(CalculatorOperator.MULTIPLY)
        digits("2")
        engine.equals()

        assertEquals("10", engine.display)
    }

    @Test
    fun backspaceRemovesOneCharacterAtATime() {
        digits("123")
        engine.backspace()
        assertEquals("12", engine.display)
        engine.backspace()
        assertEquals("1", engine.display)
        engine.backspace()
        assertEquals("0", engine.display)
    }

    @Test
    fun backspaceAfterEqualsClearsTheCalculation() {
        digits("2")
        operator(CalculatorOperator.PLUS)
        digits("3")
        engine.equals()

        engine.backspace()
        assertEquals("0", engine.display)
        assertEquals("", engine.expression)
    }

    @Test
    fun backspaceOnNegativeNumberFallsBackToZero() {
        digits("5")
        engine.toggleSign()
        assertEquals("-5", engine.display)

        engine.backspace()
        assertEquals("0", engine.display)
    }

    @Test
    fun signToggleSwitchesDirection() {
        digits("5")
        engine.toggleSign()
        assertEquals("-5", engine.display)

        engine.toggleSign()
        assertEquals("5", engine.display)

        engine.clear()
        engine.toggleSign()
        assertEquals("0", engine.display)
    }

    @Test
    fun percentDividesTheCurrentInputByOneHundred() {
        digits("50")
        engine.percent()
        assertEquals("0.5", engine.display)

        engine.clear()
        digits("200")
        engine.percent()
        assertEquals("2", engine.display)
    }

    @Test
    fun floatingPointNoiseIsHidden() {
        digits("0")
        engine.inputDot()
        digits("1")
        operator(CalculatorOperator.PLUS)
        digits("0")
        engine.inputDot()
        digits("2")
        engine.equals()

        assertEquals("0.3", engine.display)
    }

    @Test
    fun repeatingDecimalsAreTrimmedToTenPlaces() {
        digits("1")
        operator(CalculatorOperator.DIVIDE)
        digits("3")
        engine.equals()

        assertEquals("0.3333333333", engine.display)
    }

    @Test
    fun mixedExpressionIsEvaluatedCorrectly() {
        digits("2")
        operator(CalculatorOperator.PLUS)
        digits("3")
        operator(CalculatorOperator.MULTIPLY)
        digits("4")
        operator(CalculatorOperator.MINUS)
        digits("5")
        engine.equals()

        assertEquals("9", engine.display)
    }

    @Test
    fun clearResetsTheWholeState() {
        digits("5")
        operator(CalculatorOperator.PLUS)
        digits("3")
        operator(CalculatorOperator.MULTIPLY)
        digits("2")

        engine.clear()
        assertEquals("0", engine.display)
        assertEquals("", engine.expression)
        assertFalse(engine.isError)
    }

    @Test
    fun equalsWithoutPendingOperationIsANoOp() {
        engine.equals()

        assertEquals("0", engine.display)
        assertEquals("", engine.expression)
        assertFalse(engine.isError)
    }

    @Test
    fun formatNumberHidesNonFiniteValues() {
        assertEquals("0", CalculatorEngine.formatNumber(Double.NaN))
        assertEquals("0", CalculatorEngine.formatNumber(Double.POSITIVE_INFINITY))
        assertEquals("0", CalculatorEngine.formatNumber(Double.NEGATIVE_INFINITY))
        assertEquals("-3", CalculatorEngine.formatNumber(-3.0))
        assertEquals("1.25", CalculatorEngine.formatNumber(1.25))
    }
}
