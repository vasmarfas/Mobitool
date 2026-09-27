package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

data class JwtParts(
    val header: JsonObject,
    val payload: JsonObject,
    val signature: String,
)

class JwtException(val text: StringResource) : Exception(text.key)

object Jwt {
    val claimNames: Map<String, StringResource> = mapOf(
        "iss" to Res.string.jwt_claim_iss,
        "sub" to Res.string.jwt_claim_sub,
        "aud" to Res.string.jwt_claim_aud,
        "exp" to Res.string.jwt_claim_exp,
        "nbf" to Res.string.jwt_claim_nbf,
        "iat" to Res.string.jwt_claim_iat,
        "jti" to Res.string.jwt_claim_jti,
        "alg" to Res.string.algorithm,
        "typ" to Res.string.type,
        "kid" to Res.string.jwt_claim_kid,
        "cty" to Res.string.jwt_claim_cty,
        "scope" to Res.string.jwt_claim_scope,
        "scp" to Res.string.jwt_claim_scope,
        "azp" to Res.string.jwt_claim_azp,
        "nonce" to Res.string.jwt_claim_nonce,
        "auth_time" to Res.string.jwt_claim_auth_time,
        "sid" to Res.string.jwt_claim_sid,
        "email" to Res.string.jwt_claim_email,
        "email_verified" to Res.string.jwt_claim_email_verified,
        "name" to Res.string.name,
        "given_name" to Res.string.jwt_claim_given_name,
        "family_name" to Res.string.jwt_claim_family_name,
        "preferred_username" to Res.string.jwt_claim_preferred_username,
        "username" to Res.string.jwt_claim_username,
        "roles" to Res.string.jwt_claim_roles,
        "role" to Res.string.jwt_claim_role,
        "groups" to Res.string.jwt_claim_groups,
        "client_id" to Res.string.jwt_claim_client_id,
        "token_use" to Res.string.jwt_claim_token_use,
        "amr" to Res.string.jwt_claim_amr,
        "acr" to Res.string.jwt_claim_acr,
        "at_hash" to Res.string.jwt_claim_at_hash,
        "picture" to Res.string.jwt_claim_picture,
        "locale" to Res.string.locale,
    )

    val timeClaims = setOf("exp", "nbf", "iat", "auth_time", "updated_at")

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeSegment(segment: String): String? = try {
        Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(segment).decodeToString(throwOnInvalidSequence = true)
    } catch (e: Exception) {
        null
    }

    fun decode(token: String): Result<JwtParts> {
        val parts = token.trim().removePrefix("Bearer ").trim().split('.')
        if (parts.size != 3) {
            return Result.failure(JwtException(Res.string.jwt_three_parts))
        }
        val header = decodeObject(parts[0]) ?: return Result.failure(JwtException(Res.string.jwt_header_not_json))
        val payload = decodeObject(parts[1]) ?: return Result.failure(JwtException(Res.string.jwt_payload_not_json))
        return Result.success(JwtParts(header, payload, parts[2]))
    }

    private fun decodeObject(segment: String): JsonObject? {
        val json = decodeSegment(segment) ?: return null
        return JsonTools.parse(json).getOrNull() as? JsonObject
    }

    fun claimText(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { claimText(it) }
        is JsonObject -> JsonTools.minify(value)
    }

    fun epochSeconds(value: JsonElement): Long? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()
}
