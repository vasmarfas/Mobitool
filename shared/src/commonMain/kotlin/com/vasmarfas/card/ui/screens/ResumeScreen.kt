package com.vasmarfas.card.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.Education
import com.vasmarfas.card.data.Experience
import com.vasmarfas.card.data.ResumeRepository
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ContentColumn
import com.vasmarfas.card.ui.components.EmptyState
import com.vasmarfas.card.ui.components.FindableText
import com.vasmarfas.card.ui.components.PageMaxWidth
import com.vasmarfas.card.ui.components.SectionTitle
import com.vasmarfas.card.ui.components.TagChips

// newest start first, of two jobs started the same month the running one goes above
private val byRecency = compareByDescending<Experience> { it.from }.thenByDescending { it.to ?: "9999-99" }

@Composable
fun ResumeScreen() {
    val state by ResumeRepository.state.collectAsState()
    val resume = state.value
    ContentColumn(maxWidth = PageMaxWidth, verticalSpacing = 20.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FindableText(Res.string.resume_page.str(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            resume?.pdf?.let { url ->
                OutlinedButton(
                    onClick = {
                        Analytics.log(AnalyticsEvent.LINK_OPEN, mapOf(AnalyticsParam.LINK to "resume_pdf", AnalyticsParam.SOURCE to "resume"))
                        openUrl(url)
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Res.string.resume_pdf.str())
                }
            }
        }
        if (resume == null) return@ContentColumn
        if (resume.isEmpty) {
            EmptyState(
                icon = Icons.Filled.Description,
                title = Res.string.resume_page.str(),
                description = Res.string.resume_empty.str(),
            )
            return@ContentColumn
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                resume.summary?.let { FindableText(it.str(), style = MaterialTheme.typography.bodyLarge) }
                if (resume.experience.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(Res.string.experience.str())
                        resume.experience.sortedWith(byRecency).forEach { ExperienceCard(it) }
                    }
                }
                if (resume.education.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(Res.string.education.str())
                        resume.education.forEach { EducationCard(it) }
                    }
                }
                if (resume.courses.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(Res.string.courses_and_certificates.str())
                        resume.courses.forEach { EducationCard(it) }
                    }
                }
                if (resume.skills.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(Res.string.skills.str())
                        resume.skills.forEach { group ->
                            FindableText(group.title.str(), style = MaterialTheme.typography.titleMedium)
                            TagChips(group.items)
                        }
                    }
                }
                if (resume.languages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(Res.string.languages.str())
                        TagChips(resume.languages.map { "${it.name.str()} — ${it.level.str()}" })
                    }
                }
            }
        }
    }
}

private val monthShortNames = listOf(
    Res.string.month_short_1, Res.string.month_short_2, Res.string.month_short_3, Res.string.month_short_4,
    Res.string.month_short_5, Res.string.month_short_6, Res.string.month_short_7, Res.string.month_short_8,
    Res.string.month_short_9, Res.string.month_short_10, Res.string.month_short_11, Res.string.month_short_12,
)

@Composable
private fun formatMonth(value: String): String {
    val parts = value.split('-')
    val year = parts.getOrNull(0) ?: return value
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return year
    return "${monthShortNames.getOrNull(month - 1)?.str() ?: month} $year"
}

@Composable
private fun ExperienceCard(item: Experience) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FindableText(item.role.str(), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FindableText(item.company.str(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                item.url?.let { url ->
                    IconButton(onClick = { openUrl(url) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = Res.string.open.str(),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            val period = "${formatMonth(item.from)} – ${item.to?.let { formatMonth(it) } ?: Res.string.present.str()}" +
                (item.location?.let { " · ${it.str()}" } ?: "")
            FindableText(period, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.summary?.let { FindableText(it.str(), style = MaterialTheme.typography.bodyMedium) }
            item.bullets.forEach { bullet ->
                Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    FindableText(bullet.str(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (item.tags.isNotEmpty()) TagChips(item.tags)
        }
    }
}

@Composable
private fun EducationCard(item: Education) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FindableText(item.degree.str(), style = MaterialTheme.typography.titleMedium)
            FindableText(item.institution.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            val meta = listOfNotNull(item.year, item.note?.str()).joinToString(" · ")
            FindableText(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
