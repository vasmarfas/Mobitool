package com.vasmarfas.card.tools.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.HashAlgorithm
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

val totpTool = Tool(
    id = "totp",
    category = ToolCategory.SECURITY,
    title = Res.string.totp_codes,
    description = Res.string.totp_description,
    icon = Icons.Filled.Key,
    keywords = listOf("totp", "otp", "2fa", "mfa", "authenticator", "one-time", "google authenticator", "двухфакторная", "код", "аутентификатор"),
) { TotpScreen() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TotpScreen() {
    var input by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf(6) }
    var period by rememberSaveable { mutableStateOf(30) }
    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = currentEpochMillis()
        }
    }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.base32_secret_or_otpauth_uri.str(),
        singleLine = false,
        minLines = 2,
        placeholder = "JBSWY3DPEHPK3PXP",
        monospace = true,
    )
    val parsed = remember(input) { Totp.parseUri(input) }
    if (parsed == null) {
        SegmentedChoice(options = listOf(6, 8), selected = digits, onSelect = { digits = it }, label = { "$it ${Res.string.digits.str()}" })
        SegmentedChoice(options = listOf(30, 60), selected = period, onSelect = { period = it }, label = { "$it ${Res.string.unit_s.str()}" })
    }
    if (input.isBlank()) return
    val secretText = parsed?.secret ?: input.trim()
    val secret = remember(secretText) { Base32.decode(secretText) }
    if (secret == null || secret.isEmpty()) {
        ErrorText(Res.string.totp_not_a_valid_base32.str())
        return
    }
    val activeDigits = parsed?.digits ?: digits
    val activePeriod = parsed?.period ?: period
    val algorithm = parsed?.algorithm ?: HashAlgorithm.SHA1
    val counter = Totp.counter(now, activePeriod)
    val code = remember(secretText, counter, activeDigits, activePeriod, algorithm) {
        Totp.code(secret, counter, activeDigits, algorithm)
    }
    val remaining = Totp.secondsRemaining(now, activePeriod)
    ResultCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                code.chunked(if (activeDigits == 8) 4 else 3).joinToString(" "),
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = monoFamily()),
                modifier = Modifier.weight(1f),
            )
            CopyIconButton(code)
        }
        LinearWavyProgressIndicator(
            progress = { remaining.toFloat() / activePeriod },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.totp_valid_for, remaining),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ResultCard(Res.string.parameters.str()) {
        if (parsed?.issuer != null) KeyValueRow(Res.string.issuer_2.str(), parsed.issuer, mono = false)
        if (parsed?.account != null) KeyValueRow(Res.string.account.str(), parsed.account, mono = false)
        KeyValueRow(Res.string.algorithm.str(), "HMAC-${algorithm.title}", copyable = false)
        KeyValueRow(Res.string.totp_digits.str(), activeDigits.toString(), copyable = false)
        KeyValueRow(Res.string.period.str(), "$activePeriod ${Res.string.unit_s.str()}", copyable = false)
        KeyValueRow(Res.string.counter.str(), counter.toString(), copyable = false)
        KeyValueRow(Res.string.secret_base32.str(), secretText)
        KeyValueRow(
            Res.string.previous_next_code.str(),
            Totp.code(secret, counter - 1, activeDigits, algorithm) + " / " + Totp.code(secret, counter + 1, activeDigits, algorithm),
            copyable = false,
        )
        if (parsed == null) {
            KeyValueRow(
                "otpauth://",
                Totp.buildUri(OtpAuth(secretText, null, null, activeDigits, activePeriod, algorithm)),
            )
        }
    }
}
