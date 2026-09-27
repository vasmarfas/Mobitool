package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.text.OutputCard
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

val jwtDecoderTool = Tool(
    id = "jwt-decoder",
    category = ToolCategory.DEVELOPER,
    title = Res.string.jwt_decoder,
    description = Res.string.jwt_decoder_description,
    icon = Icons.Filled.Token,
    keywords = listOf("jwt", "token", "jws", "oauth", "oidc", "claims", "bearer", "токен", "джвт", "авторизация"),
) { JwtDecoderScreen() }

@Composable
private fun JwtDecoderScreen() {
    var input by rememberSaveable { mutableStateOf("") }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.token.str(),
        singleLine = false,
        minLines = 4,
        placeholder = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.…",
        monospace = true,
    )
    if (input.isBlank()) return
    val result = remember(input) { Jwt.decode(input) }
    val parts = result.getOrNull()
    if (parts == null) {
        val error = result.exceptionOrNull()
        ErrorText(if (error is JwtException) error.text.str() else error?.message ?: "")
        return
    }
    val now = remember(input) { currentEpochMillis() / 1000 }
    val exp = parts.payload["exp"]?.let { Jwt.epochSeconds(it) }
    val nbf = parts.payload["nbf"]?.let { Jwt.epochSeconds(it) }
    ResultCard(Res.string.status.str()) {
        val status = when {
            exp != null && exp < now -> stringResource(Res.string.jwt_expired_ago, formatDurationMs((now - exp) * 1000))
            nbf != null && nbf > now -> stringResource(Res.string.jwt_not_valid_yet, formatDurationMs((nbf - now) * 1000))
            exp != null -> stringResource(Res.string.jwt_valid_for, formatDurationMs((exp - now) * 1000))
            else -> Res.string.no_expiration_claim.str()
        }
        KeyValueRow(Res.string.validity.str(), status, mono = false, copyable = false)
        val alg = (parts.header["alg"] as? JsonPrimitive)?.content ?: "?"
        KeyValueRow(Res.string.algorithm.str(), alg, copyable = false)
        if (alg.equals("none", ignoreCase = true)) {
            ErrorText(Res.string.alg_none_the_token_is_unsigned.str())
        }
        val signature = parts.signature.length
        KeyValueRow(Res.string.signature.str(), if (signature == 0) "—" else pluralStringResource(Res.plurals.jwt_signature_chars, signature, signature), copyable = false)
        Text(
            Res.string.jwt_signature_is_not_verified.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ResultCard(Res.string.claims.str()) {
        parts.payload.forEach { (key, value) ->
            val name = Jwt.claimNames[key]?.str()
            val label = if (name != null) "$key — $name" else key
            val text = Jwt.claimText(value)
            val seconds = if (key in Jwt.timeClaims) Jwt.epochSeconds(value) else null
            KeyValueRow(label, if (seconds != null) "$text  (${localDateTime(seconds * 1000).formatted()})" else text)
        }
    }
    OutputCard(JsonTools.format(parts.header, 2), title = Res.string.header.str())
    OutputCard(JsonTools.format(parts.payload, 2), title = Res.string.payload.str())
}
