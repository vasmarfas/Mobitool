package com.vasmarfas.card.tools.design

import com.vasmarfas.card.core.Tr
import io.github.alexzhirkevich.qrose.oned.BarcodeType

private const val CODE39_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. \$/+%"
private const val CODABAR_ALPHABET = "0123456789-\$:/.+"

private const val MAX_LENGTH = 80

val supportedBarcodes: List<BarcodeType> = listOf(
    BarcodeType.EAN13,
    BarcodeType.EAN8,
    BarcodeType.UPCA,
    BarcodeType.Code128,
    BarcodeType.Code39,
    BarcodeType.ITF,
    BarcodeType.Codabar,
)

fun BarcodeType.sampleData(): String = when (this) {
    BarcodeType.EAN13 -> "5901234123457"
    BarcodeType.EAN8 -> "96385074"
    BarcodeType.UPCA -> "036000291452"
    BarcodeType.Code39 -> "VASMARFAS-39"
    BarcodeType.ITF -> "12345678"
    BarcodeType.Codabar -> "A12345B"
    else -> "VASMARFAS 128"
}

fun BarcodeType.digitsRequired(): Int? = when (this) {
    BarcodeType.EAN13 -> 13
    BarcodeType.EAN8 -> 8
    BarcodeType.UPCA -> 12
    else -> null
}

fun validateBarcode(type: BarcodeType, data: String): Tr? {
    val value = data.trim()
    if (value.isEmpty()) return Tr("Enter the data to encode.", "Введите данные для кодирования.")
    val digits = type.digitsRequired()
    if (digits != null) {
        if (value.any { !it.isDigit() }) return Tr("Only digits are allowed.", "Допустимы только цифры.")
        if (value.length != digits) return Tr("$digits digits required, got ${value.length}.", "Нужно $digits цифр, введено ${value.length}.")
        if (!Ean13.verify(value)) {
            val expected = Ean13.checksum(value.dropLast(1))
            return Tr("Wrong check digit, expected $expected.", "Неверная контрольная цифра, ожидается $expected.")
        }
        return null
    }
    return when (type) {
        BarcodeType.ITF -> when {
            value.any { !it.isDigit() } -> Tr("Only digits are allowed.", "Допустимы только цифры.")
            value.length % 2 != 0 -> Tr("ITF needs an even number of digits.", "ITF требует чётное количество цифр.")
            value.length > MAX_LENGTH -> tooLong(value.length)
            else -> null
        }

        BarcodeType.Code39 -> when {
            value.uppercase().any { it !in CODE39_ALPHABET } ->
                Tr("Code 39 allows A–Z, 0–9 and - . space \$ / + %.", "Code 39 допускает A–Z, 0–9 и - . пробел \$ / + %.")

            value.length > MAX_LENGTH -> tooLong(value.length)
            else -> null
        }

        BarcodeType.Codabar -> when {
            value.length < 3 -> Tr("Codabar needs start and stop letters, e.g. A12345B.", "Codabar требует стартовую и стоповую буквы, например A12345B.")
            value.first().uppercaseChar() !in "ABCD" || value.last().uppercaseChar() !in "ABCD" ->
                Tr("Codabar must start and end with A, B, C or D.", "Codabar должен начинаться и заканчиваться буквой A, B, C или D.")

            value.drop(1).dropLast(1).any { it !in CODABAR_ALPHABET } ->
                Tr("Codabar body allows 0–9 and - \$ : / . +.", "В теле Codabar допустимы 0–9 и - \$ : / . +.")

            else -> null
        }

        else -> if (value.any { it.code > 127 }) {
            Tr("Code 128 supports ASCII characters only.", "Code 128 поддерживает только ASCII-символы.")
        } else {
            null
        }
    }
}

private fun tooLong(length: Int) = Tr("At most $MAX_LENGTH characters, got $length.", "Не больше $MAX_LENGTH символов, введено $length.")
