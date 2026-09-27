package com.vasmarfas.card.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.Analytics
import com.vasmarfas.card.core.AnalyticsEvent
import com.vasmarfas.card.core.AnalyticsParam
import com.vasmarfas.card.core.AppConfig
import com.vasmarfas.card.core.Lang
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.formatCount
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.parseCount
import com.vasmarfas.card.core.str
import com.vasmarfas.card.data.Article
import com.vasmarfas.card.data.Avatar as ProfileAvatar
import com.vasmarfas.card.data.GithubStars
import com.vasmarfas.card.data.HabrStats
import com.vasmarfas.card.data.Link
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.data.Profile
import com.vasmarfas.card.data.ProfileRepository
import com.vasmarfas.card.data.ThemeMode
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ContentColumn
import com.vasmarfas.card.ui.components.FindableText
import com.vasmarfas.card.ui.components.LayoutSize
import com.vasmarfas.card.ui.components.LocalLayoutSize
import com.vasmarfas.card.ui.components.PageMaxWidth
import com.vasmarfas.card.ui.components.PromptChevron
import com.vasmarfas.card.ui.components.SectionTitle
import com.vasmarfas.card.ui.components.linkDefaultLabel
import com.vasmarfas.card.ui.components.linkIcon
import com.vasmarfas.card.ui.components.linkParams
import com.vasmarfas.card.ui.components.rememberCopy
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.pluralStringResource

fun currentYear(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year

@Composable
fun HomeScreen(
    onOpenProjects: (ProjectsTab) -> Unit,
    onOpenResume: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by ProfileRepository.state.collectAsState()
    ContentColumn(maxWidth = PageMaxWidth, verticalSpacing = 24.dp) {
        HomeHeader(onOpenSettings)
        Crossfade(targetState = state.value) { profile ->
            if (profile == null) return@Crossfade
            LaunchedEffect(profile.articles) { HabrStats.refresh(profile.articles) }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Hero(profile)
                    StatsStrip(profile)
                    FeaturedProjects(profile) { onOpenProjects(ProjectsTab.PROJECTS) }
                    SupportLine(profile)
                    LatestArticles(profile) { onOpenProjects(ProjectsTab.ARTICLES) }
                    Services(profile)
                    ContactCard(profile, Res.string.contact_title.str(), Res.string.contact_body.str(), "contact")
                    ResumeTeaser(onOpenResume)
                    AboutSection(profile)
                    Footer(profile)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val settings = LocalSettings.current
    val lang = LocalLang.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FindableText(
            Res.string.app_name.str(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { settings.updateLang(if (lang == Lang.RU) Lang.EN else Lang.RU) },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (lang == Lang.RU) "EN" else "RU")
        }
        // the button offers the opposite of what is on screen, which on SYSTEM depends on the platform
        val dark = when (settings.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        IconButton(
            onClick = { settings.updateThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, contentDescription = Res.string.theme.str())
        }
        if (LocalLayoutSize.current == LayoutSize.COMPACT) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = Res.string.settings.str())
            }
        }
    }
}

internal fun Profile.link(type: String): Link? = links.firstOrNull { it.active && it.type == type }

private val ContactTypes = setOf("telegram", "email", "phone")

@Composable
private fun Hero(profile: Profile) {
    val person = profile.person
    val compact = LocalLayoutSize.current == LayoutSize.COMPACT
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Avatar(profile.avatar, if (compact) 72.dp else 120.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptLine(
                    person.name.str(),
                    if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                )
                FindableText(person.title.str(), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    FindableText(person.location.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HeroBlocks(profile)
    }
}

private val HeroTilesMinWidth = 760.dp

// the width is taken here and not inside the cards: the row below asks them for intrinsic heights, which
// a subcomposition cannot answer
@Composable
private fun HeroBlocks(profile: Profile) {
    val contacts = profile.links.filter { it.active && it.type in ContactTypes }
    val resources = profile.links.filter { it.active && it.type !in ContactTypes && it.type != "support" }
    if (contacts.isEmpty() && resources.isEmpty()) return
    BoxWithConstraints {
        if (maxWidth < HeroTilesMinWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (contacts.isNotEmpty()) HeroBlock(Res.string.contact_me.str(), Modifier.fillMaxWidth()) { contacts.forEach { LinkRow(it, "hero") } }
                if (resources.isNotEmpty()) HeroBlock(Res.string.my_resources.str(), Modifier.fillMaxWidth()) { resources.forEach { LinkRow(it, "resources") } }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (contacts.isNotEmpty()) HeroBlock(Res.string.contact_me.str(), Modifier.weight(1f).fillMaxHeight()) { LinkTiles(contacts, 1, "hero") }
                if (resources.isNotEmpty()) HeroBlock(Res.string.my_resources.str(), Modifier.weight(1f).fillMaxHeight()) { LinkTiles(resources, 2, "resources") }
            }
        }
    }
}

@Composable
private fun LinkTiles(links: List<Link>, columns: Int, source: String) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        links.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach {
                    LinkRow(
                        it,
                        source,
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        inset = 12.dp,
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HeroBlock(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FindableText(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            content()
        }
    }
}

// the address is copied rather than opened: plenty of visitors have no mail client set up. Only that
// needs an icon, every other row opens its link
@Composable
private fun LinkRow(link: Link, source: String, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    val email = link.type == "email"
    val address = link.url.removePrefix("mailto:")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams(link.type, source))
                if (email) {
                    copy(address)
                    copied = true
                } else {
                    openUrl(link.url)
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(linkIcon(link.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(inset))
        Column(Modifier.weight(1f)) {
            FindableText(if (email) address else link.label?.str() ?: linkDefaultLabel(link.type), style = MaterialTheme.typography.bodyLarge)
            val hint = if (email && copied) Res.string.copied.str() else linkHint(link.url)
            hint?.let { FindableText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (email) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = Res.string.copy.str(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun linkHint(url: String): String? = when {
    url.startsWith("https://t.me/") -> "@" + url.trimEnd('/').substringAfterLast('/')
    url.startsWith("http") -> url.substringAfter("://").substringBefore('/').removePrefix("www.")
    else -> null
}

@Composable
private fun PromptLine(text: String, style: TextStyle) {
    val glyph = with(LocalDensity.current) { (style.fontSize * 0.72f).toDp() }
    val cursor by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            repeatMode = RepeatMode.Restart,
        ),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            PromptChevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(width = glyph * 170 / 295, height = glyph),
        )
        FindableText(text, style = style)
        Box(
            Modifier
                .size(width = 4.dp, height = glyph)
                .alpha(cursor)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

// picture and switch both come from profile.json, so the avatar changes without a release. The bundled
// copy shows while the network one loads, or for good if it never arrives
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun Avatar(avatar: ProfileAvatar, size: Dp) {
    if (!avatar.active) return
    var bitmap by remember(avatar.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatar.url) {
        bitmap = runCatching { Res.readBytes(AppConfig.AVATAR_BUNDLED_PATH).decodeToImageBitmap() }.getOrNull()
        if (avatar.url.isBlank()) return@LaunchedEffect
        val url = if (avatar.url.startsWith("http")) avatar.url else AppConfig.contentUrl(avatar.url)
        runCatching { Net.client.get(url).readRawBytes().decodeToImageBitmap() }.onSuccess { bitmap = it }
    }
    Box(Modifier.size(size).clip(CircleShape)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun AboutSection(profile: Profile) {
    val person = profile.person
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(Res.string.about_me.str())
        (listOf(person.bio) + person.about).forEach { paragraph ->
            FindableText(paragraph.str(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// the plural follows the number as shown: 135K reads as thousands whatever the exact count ends in
private class Stat(val value: String, val label: PluralStringResource)

// narrower cells split "администрирования" mid-word
private val StatMinWidth = 150.dp

@Composable
private fun StatsStrip(profile: Profile) {
    val year = remember { currentYear() }
    val stars by GithubStars.stars.collectAsState()
    val views by HabrStats.views.collectAsState()
    val totalStars = profile.projects.sumOf { GithubStars.starsOf(it, stars) ?: 0 }
    val totalViews = profile.articles.sumOf { HabrStats.viewsOf(it, views) ?: 0 }
    val installs = profile.projects.sumOf { parseCount(it.downloads) ?: 0 }
    val stats = listOfNotNull(
        (year - profile.milestones.androidSince).takeIf { it > 0 }?.let { Stat("$it+", Res.plurals.years_in_android_kmp) },
        (year - profile.milestones.sysadminSince).takeIf { it > 0 }?.let { Stat("$it+", Res.plurals.years_of_sysadmin_work) },
        totalStars.takeIf { it > 0 }?.let { Stat(it.toString(), Res.plurals.github_stars) },
        Stat(profile.articles.size.toString(), Res.plurals.habr_articles),
        totalViews.takeIf { it > 0 }?.let { Stat(formatCount(it), Res.plurals.article_views) },
        installs.takeIf { it > 0 }?.let { Stat(formatCount(it) + "+", Res.plurals.app_installs) },
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val columns = (maxWidth / StatMinWidth).toInt().coerceIn(2, 3)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                stats.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { stat -> StatCell(stat, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier) {
    Column(modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FindableText(stat.value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        FindableText(pluralStringResource(stat.label, parseCount(stat.value) ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeaturedProjects(profile: Profile, onOpenProjects: () -> Unit) {
    val featured = profile.projects.filter { it.featured }.ifEmpty { profile.projects.take(4) }
    val columns = if (LocalLayoutSize.current == LayoutSize.COMPACT) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(Res.string.featured_projects.str(), action = { SeeAllButton(onOpenProjects) })
        featured.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { project ->
                    ProjectCard(project, modifier = Modifier.weight(1f).fillMaxHeight(), condensed = true)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SupportLine(profile: Profile) {
    val support = profile.link("support") ?: return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FindableText(Res.string.support_projects.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportButton(support, "projects")
    }
}

@Composable
internal fun SupportButton(support: Link, source: String) {
    OutlinedButton(
        onClick = {
            Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams(support.type, source))
            Analytics.log(AnalyticsEvent.SUPPORT_OPEN, mapOf(AnalyticsParam.SOURCE to source))
            openUrl(support.url)
        },
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(support.label?.str() ?: linkDefaultLabel(support.type))
    }
}

@Composable
private fun SeeAllButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Text(Res.string.see_all.str())
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Services(profile: Profile) {
    val services = profile.services.filter { it.active }
    if (services.isEmpty()) return
    val columns = if (LocalLayoutSize.current == LayoutSize.COMPACT) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(Res.string.services_title.str())
        services.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { service ->
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FindableText(service.title.str(), style = MaterialTheme.typography.titleMedium)
                            FindableText(service.text.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LatestArticles(profile: Profile, onOpenAll: () -> Unit) {
    if (profile.articles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(Res.string.latest_articles.str(), action = { SeeAllButton(onOpenAll) })
        profile.articles.sortedByDescending { it.date }.take(3).forEach { ArticleRow(it) }
    }
}

@Composable
fun ArticleRow(article: Article, modifier: Modifier = Modifier) {
    val live by HabrStats.views.collectAsState()
    Card(
        onClick = { openUrl(article.url) },
        modifier = modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(linkIcon(article.source), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                FindableText(article.title.str(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                article.summary?.let {
                    FindableText(it.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val meta = buildString {
                    append(article.date)
                    HabrStats.viewsOf(article, live)?.let {
                        val shown = formatCount(it)
                        append(" · ").append(pluralStringResource(Res.plurals.views_count, parseCount(shown) ?: it, shown))
                    }
                }
                FindableText(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResumeTeaser(onOpenResume: () -> Unit) {
    Card(
        onClick = onOpenResume,
        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FindableText(Res.string.resume_page.str(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                FindableText(
                    Res.string.experience_education_skills.str(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
internal fun ContactCard(profile: Profile, title: String, body: String, source: String) {
    val contacts = profile.links.filter { it.active && it.type in ContactTypes }
    if (contacts.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FindableText(title, style = MaterialTheme.typography.titleLarge)
                FindableText(body, style = MaterialTheme.typography.bodyMedium)
            }
            contacts.forEach { LinkRow(it, source) }
        }
    }
}

@Composable
private fun Footer(profile: Profile) {
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FindableText(
            Res.string.built_with.str(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            profile.links.filter { it.active && it.type in ContactTypes }.forEach { link ->
                if (link.type == "email") {
                    val address = link.url.removePrefix("mailto:")
                    FooterButton(if (copied) Icons.Filled.Check else linkIcon(link.type), if (copied) Res.string.copied.str() else address) {
                        Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams(link.type, "footer"))
                        copy(address)
                        copied = true
                    }
                } else {
                    FooterButton(linkIcon(link.type), link.label?.str() ?: linkDefaultLabel(link.type)) {
                        Analytics.log(AnalyticsEvent.LINK_OPEN, linkParams(link.type, "footer"))
                        openUrl(link.url)
                    }
                }
            }
            FooterButton(linkIcon("github"), Res.string.source_code.str()) { openUrl(AppConfig.REPO_URL) }
        }
        FindableText("© ${currentYear()} vasmarfas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FooterButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), maxLines = 1)
    }
}
