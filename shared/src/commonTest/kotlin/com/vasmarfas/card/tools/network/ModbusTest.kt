package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// request and response bytes are the worked examples of Modbus Application Protocol V1.1b3, sections 6.1 to 6.6 and 7
class ModbusTest {
    private fun hex(text: String): ByteArray = assertNotNull(SocketPayload.hex(text))

    private fun bits(text: String) = text.map { it == '1' }

    @Test
    fun buildsReadRequests() {
        assertContentEquals(hex("01 00 13 00 13"), Modbus.readRequest(ModbusTable.COILS, 0x13, 19))
        assertContentEquals(hex("02 00 C4 00 16"), Modbus.readRequest(ModbusTable.DISCRETE_INPUTS, 0xC4, 22))
        assertContentEquals(hex("03 00 6B 00 03"), Modbus.readRequest(ModbusTable.HOLDING_REGISTERS, 0x6B, 3))
        assertContentEquals(hex("04 00 08 00 01"), Modbus.readRequest(ModbusTable.INPUT_REGISTERS, 8, 1))
    }

    @Test
    fun buildsWriteRequests() {
        assertContentEquals(hex("05 00 AC FF 00"), Modbus.writeCoil(0xAC, true))
        assertContentEquals(hex("05 00 AC 00 00"), Modbus.writeCoil(0xAC, false))
        assertContentEquals(hex("06 00 01 00 03"), Modbus.writeRegister(1, 3))
    }

    @Test
    fun quantityLimitsFollowTheSpecification() {
        assertEquals(2000, ModbusTable.COILS.maxQuantity)
        assertEquals(2000, ModbusTable.DISCRETE_INPUTS.maxQuantity)
        assertEquals(125, ModbusTable.HOLDING_REGISTERS.maxQuantity)
        assertEquals(125, ModbusTable.INPUT_REGISTERS.maxQuantity)
    }

    @Test
    fun wrapsPduInMbapHeader() {
        assertContentEquals(hex("12 34 00 00 00 06 11 03 00 6B 00 03"), Modbus.frame(0x1234, 0x11, hex("03 00 6B 00 03")))
    }

    @Test
    fun readsCoilsLeastSignificantBitFirst() {
        val answer = Modbus.answer(hex("00 01 00 00 00 06 11 01 03 CD 6B 05"), 1, 1)
        assertEquals(bits("1011001111010110101"), Modbus.bits(answer, 19))
    }

    @Test
    fun readsDiscreteInputs() {
        val answer = Modbus.answer(hex("00 01 00 00 00 06 11 02 03 AC DB 35"), 1, 2)
        assertEquals(bits("0011010111011011101011"), Modbus.bits(answer, 22))
    }

    @Test
    fun readsHoldingRegisters() {
        val answer = Modbus.answer(hex("00 01 00 00 00 09 11 03 06 02 2B 00 00 00 64"), 1, 3)
        assertEquals(listOf(555, 0, 100), Modbus.registers(answer, 3))
    }

    @Test
    fun readsInputRegister() {
        val answer = Modbus.answer(hex("00 01 00 00 00 05 11 04 02 00 0A"), 1, 4)
        assertEquals(listOf(10), Modbus.registers(answer, 1))
    }

    @Test
    fun writeAnswerEchoesTheRequest() {
        assertContentEquals(hex("05 00 AC FF 00"), Modbus.answer(hex("00 01 00 00 00 06 11 05 00 AC FF 00"), 1, 5))
        assertContentEquals(hex("06 00 01 00 03"), Modbus.answer(hex("00 01 00 00 00 06 11 06 00 01 00 03"), 1, 6))
    }

    @Test
    fun exceptionResponseBecomesItsText() {
        val error = assertFailsWith<NetException> { Modbus.answer(hex("00 01 00 00 00 03 11 81 02"), 1, 1) }
        assertEquals(Res.string.modbus_exception_2, error.reason)
    }

    @Test
    fun everyExceptionCodeHasItsOwnText() {
        val codes = listOf(1, 2, 3, 4, 5, 6, 8, 10, 11)
        assertEquals(codes.size, codes.map(Modbus::exceptionText).toSet().size)
        assertEquals(Res.string.modbus_exception_other, Modbus.exceptionText(7))
        val error = assertFailsWith<NetException> { Modbus.answer(hex("00 01 00 00 00 03 11 83 07"), 1, 3) }
        assertEquals("7", error.detail)
    }

    @Test
    fun rejectsFramesThatDoNotMatch() {
        fun reason(frame: String, transaction: Int = 1, function: Int = 3) =
            assertFailsWith<NetException> { Modbus.answer(hex(frame), transaction, function) }.reason
        assertEquals(Res.string.net_error_timeout, reason(""))
        assertEquals(Res.string.modbus_bad_reply, reason("00 01 00"))
        assertEquals(Res.string.modbus_bad_reply, reason("00 01 00 00 00 05 11 04 02 00 0A"))
        assertEquals(Res.string.modbus_bad_reply, reason("00 02 00 00 00 05 11 03 02 00 0A"))
        assertEquals(Res.string.modbus_bad_reply, reason("00 01 00 01 00 05 11 03 02 00 0A"))
        assertEquals(Res.string.modbus_bad_reply, reason("00 01 00 00 00 09 11 03 06 02 2B"))
        assertEquals(Res.string.modbus_bad_reply, reason("00 01 00 00 00 01 11 03 06 02 2B"))
    }

    @Test
    fun rejectsPayloadOfTheWrongSize() {
        assertFailsWith<NetException> { Modbus.registers(hex("03 04 00 01 00 02"), 3) }
        assertFailsWith<NetException> { Modbus.registers(hex("03 06 00 01 00 02"), 3) }
        assertFailsWith<NetException> { Modbus.bits(hex("01 01 FF"), 9) }
    }

    @Test
    fun decodesSixteenBitValues() {
        assertEquals("-1", Modbus.decode(listOf(0xFFFF), ModbusFormat.INT16, false))
        assertEquals("-32768", Modbus.decode(listOf(0x8000), ModbusFormat.INT16, false))
        assertEquals("555", Modbus.decode(listOf(0x022B), ModbusFormat.INT16, false))
        assertEquals("65535", Modbus.decode(listOf(0xFFFF), ModbusFormat.UINT16, false))
    }

    @Test
    fun decodesInt32InBothWordOrders() {
        assertEquals("-2", Modbus.decode(listOf(0xFFFF, 0xFFFE), ModbusFormat.INT32, false))
        assertEquals("-2", Modbus.decode(listOf(0xFFFE, 0xFFFF), ModbusFormat.INT32, true))
        assertEquals("65536", Modbus.decode(listOf(1, 0), ModbusFormat.INT32, false))
        assertEquals("1", Modbus.decode(listOf(1, 0), ModbusFormat.INT32, true))
    }

    // IEEE 754 binary32: 0x3F800000 is 1, 0xC0000000 is -2, 0x40490FDB is pi
    @Test
    fun decodesFloatInBothWordOrders() {
        assertEquals("1", Modbus.decode(listOf(0x3F80, 0), ModbusFormat.FLOAT32, false))
        assertEquals("1", Modbus.decode(listOf(0, 0x3F80), ModbusFormat.FLOAT32, true))
        assertEquals("-2", Modbus.decode(listOf(0xC000, 0), ModbusFormat.FLOAT32, false))
        assertEquals("3.141593", Modbus.decode(listOf(0x4049, 0x0FDB), ModbusFormat.FLOAT32, false))
        assertEquals("NaN", Modbus.decode(listOf(0x7FC0, 0), ModbusFormat.FLOAT32, false))
    }
}
