package com.vasmarfas.card.tools.documents.editor

import com.vasmarfas.card.tools.documents.pdf.PageContent
import com.vasmarfas.card.tools.documents.pdf.PageCopier
import com.vasmarfas.card.tools.documents.pdf.PdfArray
import com.vasmarfas.card.tools.documents.pdf.PdfAssembler
import com.vasmarfas.card.tools.documents.pdf.PdfDict
import com.vasmarfas.card.tools.documents.pdf.PdfDocument
import com.vasmarfas.card.tools.documents.pdf.PdfEncryptor
import com.vasmarfas.card.tools.documents.pdf.PdfInt
import com.vasmarfas.card.tools.documents.pdf.PdfName
import com.vasmarfas.card.tools.documents.pdf.PdfNull
import com.vasmarfas.card.tools.documents.pdf.PdfObject
import com.vasmarfas.card.tools.documents.pdf.PdfPage
import com.vasmarfas.card.tools.documents.pdf.PdfPermissions
import com.vasmarfas.card.tools.documents.pdf.PdfReal
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfRef
import com.vasmarfas.card.tools.documents.pdf.PdfStream
import com.vasmarfas.card.tools.documents.pdf.PdfString
import com.vasmarfas.card.tools.documents.pdf.PdfWriter
import com.vasmarfas.card.tools.documents.pdf.appearanceFit
import com.vasmarfas.card.tools.documents.pdf.content
import com.vasmarfas.card.tools.documents.pdf.formatReal
import com.vasmarfas.card.tools.documents.pdf.rectOf

object PdfEditWriter {
    fun write(
        main: PdfDocument?,
        edit: DocumentEdit,
        fonts: MarkFonts,
        options: SaveOptions = SaveOptions(),
        replacements: Map<PdfDocument, Map<Int, PdfObject>> = emptyMap(),
        date: String? = null,
    ): ByteArray {
        require(edit.pages.isNotEmpty()) { "No pages to write" }
        return EditWriter(main, edit, fonts, options, replacements, date).run()
    }
}

private val infoKeys = setOf("Title", "Author", "Subject", "Keywords", "Creator", "CreationDate")

private class EditWriter(
    private val main: PdfDocument?,
    private val edit: DocumentEdit,
    fonts: MarkFonts,
    private val options: SaveOptions,
    replacements: Map<PdfDocument, Map<Int, PdfObject>>,
    private val date: String?,
) {
    private val writer = PdfWriter()
    private val painter = MarkPainter(writer, fonts)
    private val appearances = FieldAppearance(writer, painter)
    private val fields: List<FormField> = main?.let { PdfForms.read(it) }.orEmpty()
    private val acro: PdfDict? = main?.catalog?.dict("AcroForm", main)
    private val needAppearances = acro?.boolean("NeedAppearances", main) == true
    private val keepForm = acro != null && fields.isNotEmpty() && !options.flattenForm
    private val flattenForm = fields.isNotEmpty() && options.flattenForm
    private val widgets: Map<Int, Pair<FormField, FormWidget>> = fields.flatMap { field -> field.widgets.mapNotNull { w -> w.ref?.let { it.number to (field to w) } } }.toMap()
    private val copier = PageCopier(writer, ::keepAnnotation, replacements)
    private val catalog = writer.reserve()
    private val tree = writer.reserve()
    private val targets: List<PdfRef> = edit.pages.map { writer.reserve() }
    private val targetById: Map<Long, PdfRef> = edit.pages.indices.associate { edit.pages[it].id to targets[it] }

    fun run(): ByteArray {
        edit.pages.forEachIndexed { i, page -> (page.source as? SourcePage)?.let { copier.register(it.page, targets[i]) } }
        val fieldTree = if (keepForm && main != null && acro != null) {
            for (page in edit.pages) (page.source as? SourcePage)?.page?.takeIf { it.document === main }?.let(copier::registerAnnotations)
            FieldTree(main, copier, writer).also { it.reserve(acro.array("Fields", main)) }
        } else {
            null
        }
        edit.pages.forEachIndexed { i, page -> writer[targets[i]] = page(i, page) }
        if (fieldTree != null) {
            fieldTree.fill()
            applyValues(fieldTree)
        }
        writer[tree] = PdfDict("Type" to PdfName("Pages"), "Kids" to PdfArray(targets.toMutableList<PdfObject>()), "Count" to PdfInt.of(targets.size))
        writer[catalog] = catalog(fieldTree)
        val info = writer.add(info())
        painter.finish()
        val encryptor = edit.security?.let { PdfEncryptor.aes256(it.userPassword, it.ownerPassword, PdfPermissions(it.allowPrint, it.allowCopy, it.allowEdit)) }
        return writer.toByteArray(catalog, info, encryptor)
    }

    // an annotation without an appearance has nothing to paint into the page, flattening leaves it as it is
    private fun keepAnnotation(doc: PdfDocument, annotation: PdfDict): Boolean = when (annotation.name("Subtype", doc)) {
        "Link" -> true
        "Widget" -> !(flattenForm && doc === main)
        "Popup" -> !(options.flattenAnnotations || options.removeAnnotations)
        else -> !options.removeAnnotations && (!options.flattenAnnotations || annotation.dict("AP", doc)?.get("N") == null)
    }

    private fun page(index: Int, page: EditPage): PdfDict {
        val self = targets[index]
        val source = page.source
        val dict = when (source) {
            is SourcePage -> copier.copyPage(source.page, tree, self, page.turn, page.objects.takeIf { it.isNotEmpty() }?.let { content(source.page, it) })
            is BlankPage -> PdfDict("Type" to PdfName("Page"), "Parent" to tree, "MediaBox" to box(source.mediaBox), "Resources" to PdfDict())
            is ImagePage -> PdfDict(
                "Type" to PdfName("Page"),
                "Parent" to tree,
                "MediaBox" to box(source.mediaBox),
                "Resources" to PdfDict("XObject" to PdfDict("Im0" to painter.image(source.image))),
                "Contents" to writer.content("q ${formatReal(source.width)} 0 0 ${formatReal(source.height)} 0 0 cm /Im0 Do Q\n"),
            )
        }
        if (source !is SourcePage && page.rotation != 0) dict["Rotate"] = PdfInt.of(page.rotation)
        page.crop?.let { dict["CropBox"] = box(it) }
        val canvas = painter.canvas()
        if (source is SourcePage) flattenExisting(source.page, canvas)
        val added = ArrayList<PdfObject>()
        for (mark in page.marks) {
            if (mark is NoteMark || (options.keepMarksEditable && mark !is CoverMark)) {
                added += writer.add(annotation(mark, self))
            } else {
                canvas.draw(mark)
            }
        }
        edit.watermark?.let { watermark(canvas, page, it) }
        edit.numbering?.let { numbering(canvas, page, index, it) }
        if (!canvas.isEmpty) overlay(dict, canvas, source.mediaBox)
        if (added.isNotEmpty()) dict["Annots"] = PdfArray(((dict["Annots"] as? PdfArray)?.items.orEmpty() + added).toMutableList())
        return dict
    }

    private fun content(page: PdfPage, objects: Map<Int, ObjectEdit>): ByteArray {
        val moved = objects.mapNotNull { (key, edit) -> edit.transform?.takeIf { !edit.removed }?.let { key to it.values() } }.toMap()
        return PageContent.of(page).rewrite(objects.filterValues { it.removed }.keys, moved)
    }

    private fun overlay(dict: PdfDict, canvas: PaintCanvas, media: PdfRect) {
        val form = painter.form(canvas, media)
        val resources = clone(writer.resolve(dict["Resources"]) as? PdfDict)
        val xobjects = clone(writer.resolve(resources["XObject"]) as? PdfDict)
        var k = 1
        while ("Tbx$k" in xobjects) k++
        xobjects["Tbx$k"] = form
        resources["XObject"] = xobjects
        dict["Resources"] = resources
        val existing = when (val contents = dict["Contents"]) {
            null -> emptyList()
            is PdfArray -> contents.items
            else -> (writer.resolve(contents) as? PdfArray)?.items ?: listOf(contents)
        }
        val parts = ArrayList<PdfObject>()
        if (existing.isNotEmpty()) {
            parts += writer.content("q\n")
            parts += existing
            parts += writer.content("\nQ\n")
        }
        parts += writer.content("/Tbx$k Do\n")
        dict["Contents"] = PdfArray(parts)
    }

    private fun flattenExisting(page: PdfPage, canvas: PaintCanvas) {
        val doc = page.document
        val list = page.dict.array("Annots", doc) ?: return
        for (item in list.items) {
            val annotation = doc.resolve(item) as? PdfDict ?: continue
            val subtype = annotation.name("Subtype", doc)
            val widget = subtype == "Widget"
            val flatten = when {
                widget -> flattenForm && doc === main
                subtype == "Link" || subtype == "Popup" -> false
                else -> options.flattenAnnotations
            }
            if (!flatten || (annotation.int("F", doc) ?: 0) and (2 or 32) != 0) continue
            val rect = rectOf(annotation.array("Rect", doc), doc) ?: continue
            var state: String? = null
            if (widget) {
                val (field, formWidget) = (item as? PdfRef)?.let { widgets[it.number] } ?: (null to null)
                val edited = field?.let { edit.fields[it.name] }
                val value = edited ?: field?.value
                if (field != null && formWidget != null && value != null) {
                    val stale = edited != null || needAppearances || annotation.dict("AP", doc)?.get("N") == null
                    val generated = if (stale) appearances.form(field, formWidget, value, doc) else null
                    if (generated != null) {
                        paint(canvas, generated, rect)
                        continue
                    }
                    val chosen = (value as? FieldValue.Check)?.state
                    state = if (chosen != null && chosen == formWidget.onState) chosen else "Off"
                }
            }
            val normal = annotation.dict("AP", doc)?.get("N") ?: continue
            val appearance = when (val resolved = doc.resolve(normal)) {
                is PdfStream -> normal
                is PdfDict -> resolved[state ?: annotation.name("AS", doc) ?: continue] ?: continue
                else -> continue
            }
            val stream = doc.resolve(appearance) as? PdfStream ?: continue
            val bbox = rectOf(stream.dict.array("BBox", doc), doc) ?: continue
            val form = copier.copyObject(doc, appearance) as? PdfRef ?: continue
            canvas.paintForm(form, appearanceFit(rect, bbox, stream.dict.array("Matrix", doc)?.numbers(doc)))
        }
    }

    private fun paint(canvas: PaintCanvas, form: PdfRef, rect: PdfRect) {
        val stream = writer[form] as? PdfStream ?: return
        val bbox = rectOf(stream.dict["BBox"] as? PdfArray, null) ?: return
        canvas.paintForm(form, appearanceFit(rect, bbox, (stream.dict["Matrix"] as? PdfArray)?.numbers()))
    }

    private fun applyValues(tree: FieldTree) {
        val doc = main ?: return
        for (field in fields) {
            val value = edit.fields[field.name] ?: continue
            val target = if (field.merged) {
                field.widgets[0].ref?.let { copier.annotationTarget(doc, it.number) }
            } else {
                field.nodeRef?.let { tree.targetOf(it.number) }
            }
            val node = target?.let { writer[it] as? PdfDict } ?: continue
            node["V"] = when (value) {
                is FieldValue.Text -> PdfString.ofText(value.value)
                is FieldValue.Choice -> if (value.values.size == 1) PdfString.ofText(value.values[0]) else PdfArray(value.values.mapTo(ArrayList<PdfObject>()) { PdfString.ofText(it) })
                is FieldValue.Check -> PdfName(value.state ?: "Off")
            }
            for (widget in field.widgets) {
                for (target in widget.ref?.let { copier.copiesOfAnnotation(doc, it.number) }.orEmpty()) {
                    val out = writer[target] as? PdfDict ?: continue
                    when (field.kind) {
                        FieldKind.TEXT, FieldKind.COMBO, FieldKind.LIST -> appearances.form(field, widget, value, doc)?.let { out["AP"] = PdfDict("N" to it) }
                        FieldKind.CHECKBOX, FieldKind.RADIO -> {
                            val chosen = (value as? FieldValue.Check)?.state
                            val on = widget.onState ?: "Yes"
                            out["AS"] = PdfName(if (chosen != null && chosen == on) on else "Off")
                            if (out["AP"] == null) out["AP"] = PdfDict("N" to appearances.checkStates(widget, field.textColor))
                        }
                        FieldKind.BUTTON, FieldKind.SIGNATURE -> Unit
                    }
                }
            }
        }
    }

    private fun annotation(mark: Mark, page: PdfRef): PdfDict {
        val canvas = painter.canvas()
        canvas.draw(mark)
        val rect = mark.bounds
        val dict = PdfDict(
            "Type" to PdfName("Annot"),
            "Rect" to box(rect),
            "F" to PdfInt.of(4),
            "P" to page,
            "NM" to PdfString.ofText("tbx-${mark.id}"),
        )
        date?.let { dict["M"] = PdfString.ofText(it) }
        dict["AP"] = PdfDict("N" to painter.form(canvas, rect))
        when (mark) {
            is InkMark -> {
                dict["Subtype"] = PdfName("Ink")
                dict["InkList"] = PdfArray(PdfArray(mark.path.xy.mapTo(ArrayList<PdfObject>()) { PdfReal(it.toDouble()) }))
                dict["C"] = rgb(mark.color)
                dict["BS"] = PdfDict("W" to PdfReal(mark.width.toDouble()))
                if (alpha(mark.color) < 1f) dict["CA"] = PdfReal(alpha(mark.color).toDouble())
            }
            is ShapeMark -> {
                dict["C"] = rgb(mark.color)
                dict["BS"] = PdfDict("W" to PdfReal(mark.width.toDouble()))
                mark.fill?.let { dict["IC"] = rgb(it) }
                when (mark.kind) {
                    ShapeKind.RECTANGLE -> dict["Subtype"] = PdfName("Square")
                    ShapeKind.ELLIPSE, ShapeKind.DOT -> dict["Subtype"] = PdfName("Circle")
                    ShapeKind.LINE, ShapeKind.ARROW -> {
                        dict["Subtype"] = PdfName("Line")
                        dict["L"] = PdfArray(PdfReal(mark.x0), PdfReal(mark.y0), PdfReal(mark.x1), PdfReal(mark.y1))
                        if (mark.kind == ShapeKind.ARROW) dict["LE"] = PdfArray(PdfName("None"), PdfName("ClosedArrow"))
                    }
                    ShapeKind.CHECK, ShapeKind.CROSS -> dict["Subtype"] = PdfName("Stamp")
                }
            }
            is TextMark -> {
                dict["Subtype"] = PdfName("FreeText")
                dict["Contents"] = PdfString.ofText(mark.text)
                dict["DA"] = PdfString.ofText("/Helv ${formatReal(mark.size.toDouble())} Tf ${rgbOperands(mark.color)} rg")
                dict["Q"] = PdfInt.of(mark.align.ordinal)
                if (mark.fill == null && !mark.border) dict["IT"] = PdfName("FreeTextTypeWriter")
            }
            is ImageMark, is SignatureMark -> dict["Subtype"] = PdfName("Stamp")
            is NoteMark -> {
                dict["Subtype"] = PdfName("Text")
                dict["Contents"] = PdfString.ofText(mark.text)
                dict["Name"] = PdfName("Comment")
                dict["C"] = rgb(mark.color)
                dict["F"] = PdfInt.of(4 or 8 or 16)
            }
            is CoverMark -> {
                dict["Subtype"] = PdfName("Square")
                dict["IC"] = rgb(mark.color)
                dict["C"] = rgb(mark.color)
            }
            is MarkupMark -> {
                dict["Subtype"] = PdfName(
                    when (mark.kind) {
                        MarkupKind.HIGHLIGHT -> "Highlight"
                        MarkupKind.UNDERLINE -> "Underline"
                        MarkupKind.STRIKEOUT -> "StrikeOut"
                        MarkupKind.SQUIGGLY -> "Squiggly"
                    },
                )
                dict["QuadPoints"] = PdfArray(mark.quads.flatMap { q -> q.points.map { PdfReal(it) } }.toMutableList<PdfObject>())
                dict["C"] = rgb(mark.color)
                if (mark.text.isNotEmpty()) dict["Contents"] = PdfString.ofText(mark.text)
            }
        }
        return dict
    }

    private fun watermark(canvas: PaintCanvas, page: EditPage, watermark: Watermark) {
        val at = watermarkPlacement(page, watermark, canvas.textWidth(watermark.text, watermark.size))
        canvas.textLine(watermark.text, at, watermark.size, watermark.color, watermark.opacity)
    }

    private fun numbering(canvas: PaintCanvas, page: EditPage, index: Int, numbering: PageNumbering) {
        if (numbering.skipFirst && index == 0) return
        val label = numbering.label(index, edit.pages.size)
        canvas.textLine(label, numberPlacement(page, numbering, canvas.textWidth(label, numbering.size)), numbering.size, numbering.color)
    }

    private fun catalog(fieldTree: FieldTree?): PdfDict {
        val out = PdfDict("Type" to PdfName("Catalog"), "Pages" to tree)
        val doc = main
        val source = doc?.catalog
        if (doc != null && source != null) {
            for (key in listOf("ViewerPreferences", "PageLayout", "Lang", "OCProperties")) {
                source[key]?.let { copier.copyObject(doc, it) }?.takeIf { it !is PdfNull }?.let { out[key] = it }
            }
            source.dict("Names", doc)?.get("EmbeddedFiles")?.let { copier.copyObject(doc, it) }?.takeIf { it !is PdfNull }?.let {
                out["Names"] = PdfDict("EmbeddedFiles" to it)
            }
            if (samePages(doc)) source["PageLabels"]?.let { copier.copyObject(doc, it) }?.takeIf { it !is PdfNull }?.let { out["PageLabels"] = it }
            if (edit.info.isEmpty()) source["Metadata"]?.let { copier.copyObject(doc, it) }?.takeIf { it !is PdfNull }?.let { out["Metadata"] = it }
            source["OpenAction"]?.let { action ->
                val dest = when (val resolved = doc.resolve(action)) {
                    is PdfArray -> action
                    is PdfDict -> resolved.takeIf { it.name("S", doc) == "GoTo" }?.get("D")
                    else -> null
                }
                dest?.let { copier.destination(doc, it) }?.let { out["OpenAction"] = it }
            }
        }
        val outline = PdfOutline.write(writer, edit.outline, targetById::get)
        if (outline != null) out["Outlines"] = outline
        source?.name("PageMode", doc)?.let { mode -> if (mode != "UseOutlines" || outline != null) out["PageMode"] = PdfName(mode) }
        if (fieldTree != null && doc != null && acro != null) out["AcroForm"] = acroForm(doc, acro, fieldTree)
        if (edit.security != null) out["Extensions"] = PdfDict("ADBE" to PdfDict("BaseVersion" to PdfName("1.7"), "ExtensionLevel" to PdfInt.of(8)))
        return out
    }

    private fun acroForm(doc: PdfDocument, acro: PdfDict, tree: FieldTree): PdfDict {
        val out = PdfDict()
        for ((key, value) in acro.entries) {
            when (key) {
                "Fields" -> out[key] = PdfArray(tree.top.toMutableList<PdfObject>())
                "XFA" -> Unit
                "CO" -> {
                    val order = (doc.resolve(value) as? PdfArray)?.items.orEmpty().mapNotNull { item ->
                        (item as? PdfRef)?.let { tree.targetOf(it.number) ?: copier.annotationTarget(doc, it.number) }
                    }
                    if (order.isNotEmpty()) out[key] = PdfArray(order.toMutableList<PdfObject>())
                }
                else -> copier.copyObject(doc, value).takeIf { it !is PdfNull }?.let { out[key] = it }
            }
        }
        return out
    }

    private fun samePages(doc: PdfDocument): Boolean =
        edit.pages.size == doc.pageCount && edit.pages.withIndex().all { (i, page) -> (page.source as? SourcePage)?.page?.let { it.document === doc && it.index == i } == true }

    private fun info(): PdfDict {
        val out = PdfDict()
        main?.info?.forEach { (key, value) -> if (key in infoKeys) out[key] = PdfString.ofText(value) }
        for ((key, value) in edit.info) if (value.isBlank()) out.remove(key) else out[key] = PdfString.ofText(value)
        out["Producer"] = PdfString.ofText(PdfAssembler.PRODUCER)
        date?.let { out["ModDate"] = PdfString.ofText(it) }
        return out
    }

    private fun clone(dict: PdfDict?): PdfDict = PdfDict(LinkedHashMap(dict?.entries.orEmpty()))

    private fun box(rect: PdfRect) = PdfArray(PdfReal(rect.left), PdfReal(rect.bottom), PdfReal(rect.right), PdfReal(rect.top))

    private fun rgb(argb: Int) = PdfArray(PdfReal(((argb shr 16) and 0xFF) / 255.0), PdfReal(((argb shr 8) and 0xFF) / 255.0), PdfReal((argb and 0xFF) / 255.0))

    private fun rgbOperands(argb: Int) = "${formatReal(((argb shr 16) and 0xFF) / 255.0)} ${formatReal(((argb shr 8) and 0xFF) / 255.0)} ${formatReal((argb and 0xFF) / 255.0)}"
}

private class FieldTree(private val doc: PdfDocument, private val copier: PageCopier, private val writer: PdfWriter) {
    private val kids = HashMap<Int, List<PdfRef>>()
    private val nodes = LinkedHashMap<Int, PdfDict>()
    var top: List<PdfRef> = emptyList()
        private set

    fun reserve(fields: PdfArray?) {
        top = fields?.items.orEmpty().mapNotNull { reserve(it, 0) }
    }

    private fun reserve(item: PdfObject, depth: Int): PdfRef? {
        val ref = item as? PdfRef ?: return null
        val node = doc.resolve(ref) as? PdfDict ?: return null
        val list = node.array("Kids", doc) ?: return copier.annotationTarget(doc, ref.number)
        if (depth > 32 || ref.number in nodes) return null
        nodes[ref.number] = node
        val target = copier.reserveFor(doc, ref.number)
        val kept = list.items.mapNotNull { reserve(it, depth + 1) }
        kids[ref.number] = kept
        return if (kept.isEmpty()) null else target
    }

    fun fill() {
        for ((number, node) in nodes) {
            val kept = kids[number].orEmpty()
            if (kept.isEmpty()) continue
            val out = PdfDict()
            for ((key, value) in node.entries) {
                if (key == "Kids") {
                    out[key] = PdfArray(kept.toMutableList<PdfObject>())
                } else {
                    copier.copyObject(doc, value).takeIf { it !is PdfNull }?.let { out[key] = it }
                }
            }
            writer[copier.reserveFor(doc, number)] = out
        }
    }

    fun targetOf(number: Int): PdfRef? = if (kids[number].isNullOrEmpty()) null else copier.reserveFor(doc, number)
}
