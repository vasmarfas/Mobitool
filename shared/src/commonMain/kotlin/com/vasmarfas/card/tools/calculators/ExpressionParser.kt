package com.vasmarfas.card.tools.calculators

import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import org.jetbrains.compose.resources.StringResource

class ExpressionException(val error: StringResource, vararg val args: String) : RuntimeException(error.key)

private sealed class Token {
    class Num(val value: Double) : Token()
    class Ident(val name: String) : Token()
    class Op(val char: Char) : Token()
    object End : Token()
}

object ExpressionParser {
    val functions = listOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "sqrt", "cbrt", "ln", "log", "log2", "exp", "abs", "floor", "ceil", "round",
    )

    val constants = mapOf("pi" to PI, "e" to E)

    fun evaluate(expression: String, degrees: Boolean = false): Double {
        val tokens = tokenize(expression)
        if (tokens.size == 1) throw ExpressionException(Res.string.calc_empty_expression)
        val parser = Parser(tokens, degrees)
        val value = parser.expression()
        if (parser.peek() != Token.End) throw ExpressionException(Res.string.calc_unexpected_token)
        if (value.isNaN()) throw ExpressionException(Res.string.calc_result_undefined)
        return value
    }

    private fun tokenize(expression: String): List<Token> {
        val src = expression
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace("π", " pi ")
            .replace("√", " sqrt ")
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' || c == ',' -> {
                    val start = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == ',')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        var j = i + 1
                        if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
                        if (j < src.length && src[j].isDigit()) {
                            while (j < src.length && src[j].isDigit()) j++
                            i = j
                        }
                    }
                    val text = src.substring(start, i)
                    val value = text.toDoubleLenient() ?: throw ExpressionException(Res.string.calc_invalid_number, text)
                    tokens.add(Token.Num(value))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < src.length && src[i].isLetter()) i++
                    // a constant does not swallow digits, so e2 is e × 2 while log2 stays one name
                    if (src.substring(start, i).lowercase() !in constants) while (i < src.length && src[i].isDigit()) i++
                    tokens.add(Token.Ident(src.substring(start, i).lowercase()))
                }
                c in "+-*/%^()!" -> {
                    tokens.add(Token.Op(c))
                    i++
                }
                else -> throw ExpressionException(Res.string.calc_unexpected_character, c.toString())
            }
        }
        tokens.add(Token.End)
        return tokens
    }
}

private class Parser(private val tokens: List<Token>, private val degrees: Boolean) {
    private var pos = 0

    fun peek(): Token = tokens[pos]

    private fun isOp(c: Char): Boolean = (peek() as? Token.Op)?.char == c

    fun expression(): Double {
        var value = term()
        while (true) {
            value = when {
                isOp('+') -> {
                    pos++
                    value + term()
                }
                isOp('-') -> {
                    pos++
                    value - term()
                }
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = unary()
        while (true) {
            value = when {
                isOp('*') -> {
                    pos++
                    value * unary()
                }
                isOp('/') -> {
                    pos++
                    value / divisor()
                }
                isOp('%') -> {
                    pos++
                    value % divisor()
                }
                startsOperand() -> value * power()
                else -> return value
            }
        }
    }

    private fun divisor(): Double {
        val value = unary()
        if (value == 0.0) throw ExpressionException(Res.string.division_by_zero)
        return value
    }

    private fun startsOperand(): Boolean = peek() is Token.Num || peek() is Token.Ident || isOp('(')

    private fun unary(): Double = when {
        isOp('-') -> {
            pos++
            -unary()
        }
        isOp('+') -> {
            pos++
            unary()
        }
        else -> power()
    }

    private fun power(): Double {
        val base = postfix()
        if (isOp('^')) {
            pos++
            return base.pow(unary())
        }
        return base
    }

    private fun postfix(): Double {
        var value = primary()
        while (isOp('!')) {
            pos++
            value = factorial(value)
        }
        return value
    }

    private fun primary(): Double {
        val token = peek()
        if (token is Token.Num) {
            pos++
            return token.value
        }
        if (token is Token.Ident) {
            pos++
            return identifier(token.name)
        }
        if (token is Token.Op && token.char == '(') {
            pos++
            val value = expression()
            expectClose()
            return value
        }
        throw ExpressionException(if (token == Token.End) Res.string.calc_incomplete_expression else Res.string.calc_unexpected_token)
    }

    private fun expectClose() {
        if (!isOp(')')) throw ExpressionException(Res.string.calc_missing_closing_parenthesis)
        pos++
    }

    private fun identifier(name: String): Double {
        ExpressionParser.constants[name]?.let { return it }
        if (name !in ExpressionParser.functions) throw ExpressionException(Res.string.calc_unknown_function, name)
        val argument = if (isOp('(')) {
            pos++
            val value = expression()
            expectClose()
            value
        } else {
            unary()
        }
        return applyFunction(name, argument, degrees)
    }
}

private fun applyFunction(name: String, x: Double, degrees: Boolean): Double = when (name) {
    "sin" -> if (degrees) sinDeg(x) else sin(x)
    "cos" -> if (degrees) sinDeg(x + 90) else cos(x)
    "tan" -> if (degrees) tanDeg(x) else tan(x)
    "asin" -> fromRadians(asin(x), degrees)
    "acos" -> fromRadians(acos(x), degrees)
    "atan" -> fromRadians(atan(x), degrees)
    "sinh" -> sinh(x)
    "cosh" -> cosh(x)
    "tanh" -> tanh(x)
    "sqrt" -> sqrt(x)
    "cbrt" -> cbrt(x)
    "ln" -> ln(x)
    "log" -> log10(x)
    "log2" -> log2(x)
    "exp" -> exp(x)
    "abs" -> abs(x)
    "floor" -> floor(x)
    "ceil" -> ceil(x)
    "round" -> if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)
    else -> throw ExpressionException(Res.string.calc_unknown_function, name)
}

private fun fromRadians(value: Double, degrees: Boolean): Double = if (degrees) value * 180 / PI else value

private fun sinDeg(x: Double): Double {
    val r = x.mod(360.0)
    return when (r) {
        0.0, 180.0 -> 0.0
        90.0 -> 1.0
        270.0 -> -1.0
        else -> sin(r * PI / 180)
    }
}

private fun tanDeg(x: Double): Double {
    val r = x.mod(180.0)
    return when (r) {
        0.0 -> 0.0
        45.0 -> 1.0
        135.0 -> -1.0
        90.0 -> throw ExpressionException(Res.string.calc_tan_undefined)
        else -> tan(r * PI / 180)
    }
}

private fun factorial(x: Double): Double {
    if (x < 0 || x != floor(x)) {
        throw ExpressionException(Res.string.calc_factorial_domain)
    }
    if (x > 170) return Double.POSITIVE_INFINITY
    var result = 1.0
    var i = 2.0
    while (i <= x) {
        result *= i
        i++
    }
    return result
}
