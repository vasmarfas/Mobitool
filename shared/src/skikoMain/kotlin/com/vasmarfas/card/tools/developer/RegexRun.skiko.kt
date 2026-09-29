package com.vasmarfas.card.tools.developer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun runRegex(
    pattern: String,
    text: String,
    ignoreCase: Boolean,
    multiline: Boolean,
    dotAll: Boolean,
    replacement: String?,
): RegexRunResult = withContext(Dispatchers.Default) {
    RegexTester.run(pattern, text, ignoreCase, multiline, dotAll, replacement)
}
