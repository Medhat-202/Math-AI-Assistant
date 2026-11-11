
package com.example.mathai

object MathSolver {

    fun solve(input: String): Boolean? {
        return try {
            val text = input
                .replace("×", "*")
                .replace("÷", "/")
                .replace("−", "-")
                .replace("＝", "=")
                .replace(",", ".")
                .replace(" ", "")

            val operators = listOf(">=", "<=", ">", "<", "=", "==")

            for (op in operators) {
                val index = text.indexOf(op)
                if (index > 0) {
                    val left = text.substring(0, index)
                    val right = text.substring(index + op.length)

                    val a = calculate(left) ?: return null
                    val b = calculate(right) ?: return null

                    return when (op) {
                        ">" -> a > b
                        "<" -> a < b
                        ">=" -> a >= b
                        "<=" -> a <= b
                        "=", "==" -> a == b
                        else -> null
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculate(expression: String): Double? {
        return try {
            val parser = Parser(expression)
            parser.parse()
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(private val input: String) {

        private var position = 0

        fun parse(): Double {
            val result = expression()
            if (position != input.length) {
                throw IllegalArgumentException("Invalid expression")
            }
            return result
        }

        private fun expression(): Double {
            var value = term()

            while (position < input.length) {
                when (input[position]) {
                    '+' -> {
                        position++
                        value += term()
                    }
                    '-' -> {
                        position++
                        value -= term()
                    }
                    else -> return value
                }
            }

            return value
        }

        private fun term(): Double {
            var value = factor()

            while (position < input.length) {
                when (input[position]) {
                    '*' -> {
                        position++
                        value *= factor()
                    }
                    '/' -> {
                        position++
                        val divisor = factor()
                        if (divisor == 0.0) {
                            throw ArithmeticException("Division by zero")
                        }
                        value /= divisor
                    }
                    else -> return value
                }
            }

            return value
        }

        private fun factor(): Double {
            if (position >= input.length) {
                throw IllegalArgumentException("Missing number")
            }

            if (input[position] == '+') {
                position++
                return factor()
            }

            if (input[position] == '-') {
                position++
                return -factor()
            }

            if (input[position] == '(') {
                position++
                val value = expression()

                if (position >= input.length || input[position] != ')') {
                    throw IllegalArgumentException("Missing closing bracket")
                }

                position++
                return value
            }

            val start = position

            while (position < input.length &&
                (input[position].isDigit() || input[position] == '.')
            ) {
                position++
            }

            if (start == position) {
                throw IllegalArgumentException("Invalid number")
            }

            return input.substring(start, position).toDouble()
        }
    }
}
