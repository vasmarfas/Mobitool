package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.converters.fmtSig
import org.jetbrains.compose.resources.StringResource

enum class ModbusTable(val function: Int, val maxQuantity: Int, val title: StringResource) {
    COILS(1, 2000, Res.string.modbus_coils),
    DISCRETE_INPUTS(2, 2000, Res.string.modbus_discrete_inputs),
    HOLDING_REGISTERS(3, 125, Res.string.modbus_holding_registers),
    INPUT_REGISTERS(4, 125, Res.string.modbus_input_registers);

    val bits: Boolean get() = function <= 2
}

enum class ModbusFormat(val words: Int, val label: String) {
    INT16(1, "int16"),
    UINT16(1, "uint16"),
    INT32(2, "int32"),
    FLOAT32(2, "float"),
}

// Modbus Application Protocol V1.1b3, MBAP header from Modbus Messaging on TCP/IP V1.0b
object Modbus {
    private const val WRITE_COIL = 5
    private const val WRITE_REGISTER = 6
    private const val MBAP_SIZE = 7

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun u16(bytes: ByteArray, at: Int): Int = (u8(bytes, at) shl 8) or u8(bytes, at + 1)

    private fun pdu(function: Int, first: Int, second: Int): ByteArray =
        byteArrayOf(function.toByte(), (first shr 8).toByte(), first.toByte(), (second shr 8).toByte(), second.toByte())

    fun readRequest(table: ModbusTable, address: Int, quantity: Int): ByteArray = pdu(table.function, address, quantity)

    fun writeCoil(address: Int, on: Boolean): ByteArray = pdu(WRITE_COIL, address, if (on) 0xFF00 else 0)

    fun writeRegister(address: Int, value: Int): ByteArray = pdu(WRITE_REGISTER, address, value)

    fun frame(transaction: Int, unit: Int, pdu: ByteArray): ByteArray {
        val length = pdu.size + 1
        return byteArrayOf((transaction shr 8).toByte(), transaction.toByte(), 0, 0, (length shr 8).toByte(), length.toByte(), unit.toByte()) + pdu
    }

    fun exceptionText(code: Int): StringResource = when (code) {
        1 -> Res.string.modbus_exception_1
        2 -> Res.string.modbus_exception_2
        3 -> Res.string.modbus_exception_3
        4 -> Res.string.modbus_exception_4
        5 -> Res.string.modbus_exception_5
        6 -> Res.string.modbus_exception_6
        8 -> Res.string.modbus_exception_8
        10 -> Res.string.modbus_exception_10
        11 -> Res.string.modbus_exception_11
        else -> Res.string.modbus_exception_other
    }

    fun answer(frame: ByteArray, transaction: Int, function: Int): ByteArray {
        if (frame.isEmpty()) throw NetException(Res.string.net_error_timeout)
        if (frame.size < MBAP_SIZE + 2) throw NetException(Res.string.modbus_bad_reply)
        val end = 6 + u16(frame, 4)
        if (u16(frame, 0) != transaction || u16(frame, 2) != 0 || end < MBAP_SIZE + 2 || frame.size < end) throw NetException(Res.string.modbus_bad_reply)
        val pdu = frame.copyOfRange(MBAP_SIZE, end)
        val code = u8(pdu, 0)
        if (code == function or 0x80) {
            val reason = exceptionText(u8(pdu, 1))
            throw NetException(reason, if (reason == Res.string.modbus_exception_other) u8(pdu, 1).toString() else "")
        }
        if (code != function) throw NetException(Res.string.modbus_bad_reply)
        return pdu
    }

    fun bits(answer: ByteArray, quantity: Int): List<Boolean> {
        val size = (quantity + 7) / 8
        if (answer.size != size + 2 || u8(answer, 1) != size) throw NetException(Res.string.modbus_bad_reply)
        return List(quantity) { (u8(answer, 2 + it / 8) shr (it % 8)) and 1 == 1 }
    }

    fun registers(answer: ByteArray, quantity: Int): List<Int> {
        if (answer.size != quantity * 2 + 2 || u8(answer, 1) != quantity * 2) throw NetException(Res.string.modbus_bad_reply)
        return List(quantity) { u16(answer, 2 + it * 2) }
    }

    fun decode(words: List<Int>, format: ModbusFormat, swapWords: Boolean): String {
        val joined = if (format.words == 2) if (swapWords) (words[1] shl 16) or words[0] else (words[0] shl 16) or words[1] else words[0]
        return when (format) {
            ModbusFormat.INT16 -> joined.toShort().toString()
            ModbusFormat.UINT16, ModbusFormat.INT32 -> joined.toString()
            ModbusFormat.FLOAT32 -> Float.fromBits(joined).toDouble().fmtSig(7)
        }
    }
}
