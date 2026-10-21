package com.vasmarfas.card.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

object Net {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        prettyPrint = false
    }

    val client: HttpClient by lazy {
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
            }
            if (currentPlatform != PlatformKind.WEB) {
                defaultRequest { headers.append(HttpHeaders.UserAgent, "${AppConfig.APP_NAME}/$APP_VERSION") }
            }
        }
    }
}
