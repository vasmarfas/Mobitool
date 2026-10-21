package com.vasmarfas.card.core

import kotlinx.coroutines.flow.Flow

// id is the MAC address on Android and a per-app UUID on iOS, which hides addresses
class BleAdvert(
    val id: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val connectable: Boolean?,
    val manufacturer: Map<Int, ByteArray>,
    val services: List<String>,
    val serviceData: Map<String, ByteArray>,
)

enum class BleProblem { NO_PERMISSION, OFF, UNSUPPORTED, FAILED }

class BleException(val problem: BleProblem) : Exception(problem.name)

expect fun bleAdverts(): Flow<BleAdvert>
