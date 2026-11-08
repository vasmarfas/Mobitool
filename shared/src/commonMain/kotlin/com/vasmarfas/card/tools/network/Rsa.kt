package com.vasmarfas.card.tools.network

object Rsa {
    // big-endian numbers, odd modulus, the result is as long as the modulus
    fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray {
        require(base.size <= modulus.size)
        return Montgomery(modulus).run { leave(pow(enter(base), exponent)) }
    }
}
