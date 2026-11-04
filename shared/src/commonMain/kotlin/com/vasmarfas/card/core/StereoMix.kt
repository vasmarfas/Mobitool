package com.vasmarfas.card.core

private const val MINUS_3_DB = 0.70710677f

// channel order for a bare count, as Android and Web Audio read it: L R front, C centre, F LFE, l r surround, S back centre
private val layouts = mapOf(3 to "LRC", 4 to "LRlr", 5 to "LRClr", 6 to "LRCFlr", 7 to "LRCFlrS", 8 to "LRCFlrlr")

// ITU-R BS.775 table 2, centre and surrounds at -3 dB, no LFE. The table ends at 3/2, the extra channels of 6.1 and
// 7.1 get the same gain. Rows sum to 1, so all channels at full scale do not clip. media.mjs holds a copy
fun stereoMatrix(channels: Int): List<FloatArray> {
    val layout = layouts[channels] ?: "LR".padEnd(channels, 'F')
    return listOf('L' to 'l', 'R' to 'r').map { (front, surround) ->
        val gains = layout.map {
            when (it) {
                front -> 1f
                surround, 'C', 'S' -> MINUS_3_DB
                else -> 0f
            }
        }
        val sum = gains.sum()
        FloatArray(channels) { i -> gains[i] / sum }
    }
}
