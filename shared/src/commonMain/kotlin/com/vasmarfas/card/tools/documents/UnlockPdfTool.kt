package com.vasmarfas.card.tools.documents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.documents.editor.MarkFonts
import com.vasmarfas.card.tools.documents.editor.PdfEditWriter
import com.vasmarfas.card.tools.documents.editor.startingEdit
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfEncryptedException
import com.vasmarfas.card.tools.media.PickButton
import com.vasmarfas.card.tools.media.TaskProgress
import com.vasmarfas.card.tools.media.TaskState
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ToolInputField
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

val unlockPdfTool = Tool(
    id = "unlock-pdf",
    category = ToolCategory.DOCUMENTS,
    title = Res.string.unlock_pdf,
    description = Res.string.unlock_pdf_description,
    icon = Icons.Filled.LockOpen,
    keywords = listOf(
        "unlock pdf", "remove pdf password", "decrypt pdf", "pdf restrictions", "allow printing",
        "снять пароль с pdf", "разблокировать pdf", "убрать защиту pdf", "pdf без пароля", "разрешить печать",
    ),
) { UnlockPdfScreen() }

@Composable
private fun UnlockPdfScreen() {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<PlatformFile?>(null) }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PdfResult?>(null) }
    val task = remember { TaskState() }

    PickButton(Res.string.choose_pdf.str(), pdfExtensions, icon = Icons.Filled.PictureAsPdf, empty = file == null) {
        file = it.first()
        result = null
    }
    val source = file ?: return
    Text(source.name, style = MaterialTheme.typography.bodyLarge)
    ToolInputField(value = password, onValueChange = { password = it }, label = Res.string.password.str())
    ActionButton(
        text = Res.string.remove_protection.str(),
        icon = Icons.Filled.LockOpen,
        enabled = !task.running,
        onClick = {
            result = null
            val secret = password
            task.launch(scope) {
                val bytes = source.readBytes()
                val document = try {
                    withContext(Dispatchers.Default) { PdfDocument.parse(bytes, secret) }
                } catch (e: PdfEncryptedException) {
                    throw IllegalStateException(getString(Res.string.wrong_password))
                } catch (e: Exception) {
                    throw IllegalStateException(getString(Res.string.not_a_pdf))
                }
                if (!document.encrypted) throw IllegalStateException(getString(Res.string.pdf_not_protected))
                val fonts = MarkFonts(documentFonts(serif = true))
                val copy = withContext(Dispatchers.Default) { PdfEditWriter.write(document, startingEdit(document), fonts) }
                result = PdfResult(renamed(source.name, "pdf", "-unlocked"), copy, document.pageCount)
            }
        },
    )
    TaskProgress(task, Res.string.rendering.str())
    result?.let { PdfResultCard(it) }
}
