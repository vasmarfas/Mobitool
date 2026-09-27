package com.vasmarfas.card.tools.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.core.PickKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.renamed
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolSection
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val exifViewerTool = Tool(
    id = "exif-viewer",
    category = ToolCategory.MEDIA,
    title = Res.string.exif_viewer,
    description = Res.string.exif_viewer_description,
    icon = Icons.Filled.ImageSearch,
    keywords = listOf(
        "exif", "metadata", "gps", "geotag", "remove location", "camera", "lens", "iso", "shutter speed", "xmp", "iptc",
        "метаданные", "геометка", "геолокация", "удалить геолокацию", "где снято", "какой камерой", "дата съёмки", "выдержка", "диафрагма",
    ),
) { ExifScreen() }

private class Inspected(val name: String, val bytes: ByteArray, val container: ImageContainer, val exif: ExifData?, val preview: ImageBitmap?)

@Composable
private fun ExifScreen() {
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Inspected?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var keepOrientation by rememberSaveable { mutableStateOf(true) }
    var keepProfile by rememberSaveable { mutableStateOf(true) }
    val unreadable = Res.string.image_not_readable.str()

    PickButton(Res.string.open_photo.str(), imageExtensions, PickKind.IMAGE, icon = Icons.Filled.ImageSearch, empty = photo == null) { files ->
        val file = files.first()
        loadError = null
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                withContext(Dispatchers.Default) {
                    Inspected(file.name, bytes, ImageMetadata.detect(bytes), Exif.read(bytes), decodeImage(bytes, 640))
                }
            }
                .onSuccess { photo = it }
                .onFailure { loadError = unreadable }
        }
    }
    loadError?.let { ErrorText(it) }
    val current = photo ?: return
    Preview(current.preview)
    val exif = current.exif
    if (exif == null) {
        Text(Res.string.no_metadata.str(), style = MaterialTheme.typography.bodyLarge)
    } else {
        ExifSummary(current, exif)
        if (exif.tags.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) { Text("${Res.string.all_tags.str()} · ${exif.tags.size}") }
        }
        if (showAll) {
            exif.tags.groupBy { it.group }.forEach { (group, tags) ->
                ResultCard(title = group) {
                    tags.forEach { KeyValueRow(it.name, it.value, mono = false, copyable = false) }
                }
            }
        }
    }

    val inPlace = current.container in setOf(ImageContainer.JPEG, ImageContainer.PNG, ImageContainer.WEBP, ImageContainer.GIF)
    ToolSection(Res.string.remove_metadata.str()) {
        if (inPlace) {
            SwitchRow(Res.string.keep_orientation.str(), keepOrientation, { keepOrientation = it }, description = Res.string.keep_orientation_hint.str())
            SwitchRow(Res.string.keep_color_profile.str(), keepProfile, { keepProfile = it }, description = Res.string.keep_color_profile_hint.str())
        } else {
            Hint(Res.string.reencode_hint.str())
        }
        SaveButton(Res.string.remove_metadata.str()) {
            val clean = withContext(Dispatchers.Default) {
                if (inPlace) {
                    ImageMetadata.strip(current.bytes, keepOrientation, keepProfile)
                } else {
                    decodeImage(current.bytes)?.let { encodeImage(it, ImageTarget.JPEG, 95) }
                }
            } ?: throw IllegalStateException(unreadable)
            val extension = if (inPlace) current.name.substringAfterLast('.', "jpg") else "jpg"
            saveBytes(clean, renamed(current.name, extension, "-clean"))
        }
    }
}

@Composable
private fun ExifSummary(photo: Inspected, exif: ExifData) {
    ResultCard(title = photo.name) {
        val make = exif.make.orEmpty()
        val model = exif.model.orEmpty()
        val camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
        if (camera.isNotEmpty()) KeyValueRow(Res.string.camera.str(), camera, mono = false, copyable = false)
        exif.lens?.let { KeyValueRow(Res.string.lens.str(), it, mono = false, copyable = false) }
        val settings = listOfNotNull(exif.exposureTime, exif.fNumber, exif.iso?.let { "ISO $it" }, exif.focalLength)
        if (settings.isNotEmpty()) KeyValueRow(Res.string.shot_settings.str(), settings.joinToString(" · "), copyable = false)
        exif.dateTimeOriginal?.let { KeyValueRow(Res.string.taken_at.str(), it.replaceFirst(':', '-').replaceFirst(':', '-'), copyable = false) }
        if (exif.pixelWidth != null && exif.pixelHeight != null) {
            KeyValueRow(Res.string.video_resolution.str(), "${exif.pixelWidth} × ${exif.pixelHeight}", copyable = false)
        }
        exif.software?.let { KeyValueRow(Res.string.software.str(), it, mono = false, copyable = false) }
        KeyValueRow(Res.string.size.str(), formatBytes(photo.bytes.size.toLong(), binary = false), copyable = false)
    }
    val gps = exif.gps
    if (gps == null) {
        FileLine(Res.string.shot_location.str(), Res.string.no_location.str(), icon = Icons.Filled.LocationOff)
        return
    }
    ResultCard(title = Res.string.shot_location.str()) {
        val lat = gps.latitude.fmt(6)
        val lon = gps.longitude.fmt(6)
        KeyValueRow(Res.string.location.str(), "$lat, $lon")
        gps.altitudeMeters?.let { KeyValueRow(Res.string.altitude.str(), "${it.fmt(1)} ${Res.string.unit_m.str()}", copyable = false) }
        TextButton(onClick = { openUrl("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon") }) {
            Text(Res.string.open_in_openstreetmap.str())
        }
        TextButton(onClick = { openUrl("geo:$lat,$lon") }) { Text(Res.string.open_in_maps_app.str()) }
    }
}
