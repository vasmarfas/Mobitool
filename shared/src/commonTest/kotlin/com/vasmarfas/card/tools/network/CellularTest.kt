package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.CellReading
import com.vasmarfas.card.core.CellTech
import com.vasmarfas.card.core.Net
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class CellularTest {
    private fun close(expected: Double, actual: Double?) = assertTrue(actual != null && abs(expected - actual) < 0.01, "$expected vs $actual")

    @Test
    fun channels() {
        CellBands.channel(CellTech.LTE, 1300)!!.let {
            assertEquals(listOf("B3 (1800)"), it.bands)
            close(1815.0, it.downlinkMhz)
        }
        close(806.0, CellBands.channel(CellTech.LTE, 6300)?.downlinkMhz)
        close(2650.0, CellBands.channel(CellTech.LTE, 3050)?.downlinkMhz)
        close(2585.0, CellBands.channel(CellTech.LTE, 37900)?.downlinkMhz)
        assertNull(CellBands.channel(CellTech.LTE, 70000))
        CellBands.channel(CellTech.NR, 636666)!!.let {
            close(3549.99, it.downlinkMhz)
            assertEquals(listOf("n77", "n78"), it.bands)
        }
        close(627.5, CellBands.channel(CellTech.NR, 125500)?.downlinkMhz)
        close(2140.0, CellBands.channel(CellTech.UMTS, 10700)?.downlinkMhz)
        close(942.6, CellBands.channel(CellTech.UMTS, 3013)?.downlinkMhz)
        close(947.0, CellBands.channel(CellTech.GSM, 60)?.downlinkMhz)
        close(1842.4, CellBands.channel(CellTech.GSM, 698)?.downlinkMhz)
    }

    @Test
    fun grades() {
        assertEquals(SignalGrade.EXCELLENT, CellGrades.rsrp(-75))
        assertEquals(SignalGrade.FAIR, CellGrades.rsrp(-95))
        assertEquals(SignalGrade.POOR, CellGrades.rsrp(-110))
        assertEquals(SignalGrade.GOOD, CellGrades.rsrq(-12))
        assertEquals(SignalGrade.FAIR, CellGrades.sinr(5.5))
        assertEquals(-87 to SignalGrade.GOOD, CellGrades.main(CellReading(CellTech.UMTS, true, rssi = -87)))
    }

    @Test
    fun huawei() {
        assertEquals("SessionID=abc" to "tok", Modems.huaweiSession("<?xml version=\"1.0\"?><response><SesInfo>SessionID=abc</SesInfo><TokInfo>tok</TokInfo></response>"))
        assertEquals("SessionID=abc" to "tok", Modems.huaweiSession("<response><SesInfo>abc</SesInfo><TokInfo>tok</TokInfo></response>"))
        val cell = Modems.huaweiSignal(
            """<?xml version="1.0" encoding="UTF-8"?><response><pci>150</pci><cell_id>26458625</cell_id><rssi>-63dBm</rssi>
            <rsrp>-91dBm</rsrp><rsrq>-9.0dB</rsrq><sinr>8dB</sinr><mode>7</mode><dlbandwidth>20MHz</dlbandwidth>
            <earfcn>DL:1850 UL:19850</earfcn><tac>1234</tac><band>3</band><plmn>25001</plmn></response>""",
        )!!
        assertEquals(CellTech.LTE, cell.tech)
        assertEquals(-91, cell.rsrp)
        assertEquals(-9, cell.rsrq)
        assertEquals(8.0, cell.sinr)
        assertEquals(1850, cell.arfcn)
        assertEquals(20_000, cell.bandwidthKhz)
        assertEquals("250", cell.mcc)
        assertEquals("01", cell.mnc)
        assertNull(Modems.huaweiSignal("<error><code>125002</code></error>"))
    }

    @Test
    fun zte() {
        val json = Net.json.parseToJsonElement(
            """{"network_type":"LTE","network_provider":"MTS RUS","lte_rsrp":"-97","lte_rsrq":"-11","lte_snr":"6.5","lte_pci":"1F2","cell_id":"0D59A01","lte_band":"7","lte_ca_pcell_arfcn":"3050"}""",
        ).jsonObject
        val cell = Modems.zteSignal(json)!!
        assertEquals(CellTech.LTE, cell.tech)
        assertEquals("MTS RUS", cell.operator)
        assertEquals(498, cell.pci)
        assertEquals(0x0D59A01L, cell.cellId)
        assertEquals(listOf(7), cell.bands)
        assertEquals(6.5, cell.sinr)
        assertNull(Modems.zteSignal(Net.json.parseToJsonElement("{}").jsonObject))
    }
}
