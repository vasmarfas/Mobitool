package com.vasmarfas.card.tools.documents.pdf

import kotlin.math.abs
import kotlin.math.floor

internal class PageCopier(
    private val writer: PdfWriter,
    private val keepAnnotation: (PdfDocument, PdfDict) -> Boolean = { _, _ -> true },
    private val replacements: Map<PdfDocument, Map<Int, PdfObject>> = emptyMap(),
) {
    private class Pending(val document: PdfDocument, val source: PdfObject, val target: PdfRef)

    private class Annotation(val dict: PdfDict, val target: PdfRef, val linked: Boolean)

    private val copies = HashMap<PdfDocument, HashMap<Int, PdfRef>>()
    private val inherited = HashMap<PdfObject, PdfRef>()
    private val pageTargets = HashMap<PdfDocument, HashMap<Int, PdfRef>>()
    private val annotationTargets = HashMap<PdfDocument, HashMap<Int, PdfRef>>()
    private val repeatedAnnotations = HashMap<PdfDocument, HashMap<Int, MutableList<PdfRef>>>()
    private val claimed = HashSet<PdfRef>()
    private val namedDestinations = HashMap<PdfDocument, Map<PdfString, PdfObject>>()
    private val pending = ArrayDeque<Pending>()
    private var annotationDocument: PdfDocument? = null
    private var annotationCopies: Map<Int, PdfRef> = emptyMap()

    fun register(page: PdfPage, target: PdfRef) {
        val ref = page.ref ?: return
        pageTargets.getOrPut(page.document) { HashMap() }.getOrPut(ref.number) { target }
    }

    fun registerAnnotations(page: PdfPage) {
        val doc = page.document
        val list = page.dict.array("Annots", doc) ?: return
        val targets = annotationTargets.getOrPut(doc) { HashMap() }
        for (item in list.items) {
            val ref = item as? PdfRef ?: continue
            val annotation = doc.resolve(ref) as? PdfDict ?: continue
            if (keepAnnotation(doc, annotation) && keep(doc, annotation)) targets.getOrPut(ref.number) { writer.reserve() }
        }
    }

    fun annotationTarget(doc: PdfDocument, number: Int): PdfRef? = annotationTargets[doc]?.get(number)

    fun copiesOfAnnotation(doc: PdfDocument, number: Int): List<PdfRef> =
        listOfNotNull(annotationTarget(doc, number)) + repeatedAnnotations[doc]?.get(number).orEmpty()

    fun pageTarget(doc: PdfDocument, number: Int): PdfRef? = pageTargets[doc]?.get(number)

    fun reserveFor(doc: PdfDocument, number: Int): PdfRef = copies.getOrPut(doc) { HashMap() }.getOrPut(number) { writer.reserve() }

    fun copyPage(page: PdfPage, parent: PdfRef, self: PdfRef, extraRotation: Int, contents: ByteArray? = null): PdfDict {
        val doc = page.document
        val out = PdfDict()
        out["Type"] = PdfName("Page")
        out["Parent"] = parent
        out["MediaBox"] = box(page.mediaBox)
        if (page.cropBox != page.mediaBox) out["CropBox"] = box(page.cropBox)
        val rotation = ((page.rotation + extraRotation) % 360 + 360) % 360
        if (rotation != 0) out["Rotate"] = PdfInt.of(rotation)
        out["Resources"] = resources(page)
        for ((key, value) in page.dict.entries) {
            if (key !in skippedPageKeys && (contents == null || key != "Contents")) put(out, key, copy(doc, value))
        }
        if (contents != null) out["Contents"] = writer.stream(PdfDict(), contents)
        annotations(page, self)?.let { out["Annots"] = it }
        drain()
        return out
    }

    fun copyObject(doc: PdfDocument, obj: PdfObject): PdfObject {
        val result = copy(doc, obj)
        drain()
        return result
    }

    fun destination(doc: PdfDocument, dest: PdfObject): PdfArray? {
        val array = explicitDestination(doc, dest, 0) ?: return null
        val number = when (val first = array.items.firstOrNull()) {
            is PdfRef -> first.number
            is PdfInt -> doc.pages.getOrNull(first.value.toInt())?.ref?.number
            else -> null
        } ?: return null
        val target = pageTargets[doc]?.get(number) ?: return null
        val out = PdfArray(target)
        for (i in 1 until array.size) out.add(copy(doc, array[i]))
        return out
    }

    fun explicitDestination(doc: PdfDocument, dest: PdfObject, depth: Int = 0): PdfArray? {
        if (depth > 8) return null
        val next = when (val value = doc.resolve(dest)) {
            is PdfArray -> return value
            is PdfDict -> value["D"]
            is PdfName -> doc.catalog.dict("Dests", doc)?.get(value.name)
            is PdfString -> named(doc)[value] ?: doc.catalog.dict("Dests", doc)?.get(latin1(value.bytes))
            else -> null
        }
        return next?.let { explicitDestination(doc, it, depth + 1) }
    }

    private fun resources(page: PdfPage): PdfObject {
        val doc = page.document
        val entry = page.resourcesEntry
        if (entry == null || doc.resolve(entry) !is PdfDict) return PdfDict()
        if (entry is PdfRef || page.dict["Resources"] === entry) return copy(doc, entry)
        inherited[entry]?.let { return it }
        val target = writer.reserve()
        inherited[entry] = target
        pending.addLast(Pending(doc, entry, target))
        return target
    }

    private fun annotations(page: PdfPage, self: PdfRef): PdfArray? {
        val doc = page.document
        val list = page.dict.array("Annots", doc) ?: return null
        val kept = ArrayList<Annotation>()
        val local = HashMap<Int, PdfRef>()
        val reserved = annotationTargets[doc]
        for (item in list.items) {
            val annotation = doc.resolve(item) as? PdfDict ?: continue
            if (!keepAnnotation(doc, annotation) || !keep(doc, annotation)) continue
            val number = (item as? PdfRef)?.number
            val reservedTarget = number?.let { reserved?.get(it) }
            val first = reservedTarget?.takeIf { claimed.add(it) }
            val target = first ?: writer.reserve()
            if (number != null) {
                local[number] = target
                if (reservedTarget != null && first == null) repeatedAnnotations.getOrPut(doc) { HashMap() }.getOrPut(number) { ArrayList() }.add(target)
            }
            kept.add(Annotation(annotation, target, linked = first != null))
        }
        if (kept.isEmpty()) return null
        annotationDocument = doc
        annotationCopies = local
        val result = PdfArray()
        for (annotation in kept) {
            writer[annotation.target] = copyAnnotation(doc, annotation.dict, self, annotation.linked)
            result.add(annotation.target)
        }
        drain()
        annotationDocument = null
        annotationCopies = emptyMap()
        return result
    }

    private fun keep(doc: PdfDocument, annotation: PdfDict): Boolean {
        if (annotation.name("Subtype", doc) != "Link") return true
        annotation["Dest"]?.let { return destination(doc, it) != null }
        val action = annotation.dict("A", doc) ?: return true
        if (action.name("S", doc) != "GoTo") return true
        return destination(doc, action["D"] ?: return false) != null
    }

    private fun copyAnnotation(doc: PdfDocument, annotation: PdfDict, self: PdfRef, linked: Boolean): PdfDict {
        val out = PdfDict()
        val widget = annotation.name("Subtype", doc) == "Widget"
        for ((key, value) in annotation.entries) {
            when (key) {
                "P", "StructParent" -> Unit
                "Parent" -> if (!widget || linked) put(out, key, copy(doc, value))
                "Dest" -> destination(doc, value)?.let { out[key] = it }
                "A" -> action(doc, value)?.let { out[key] = it }
                else -> put(out, key, copy(doc, value))
            }
        }
        out["P"] = self
        return out
    }

    private fun action(doc: PdfDocument, value: PdfObject): PdfObject? {
        val dict = doc.resolve(value) as? PdfDict ?: return null
        if (dict.name("S", doc) != "GoTo") return copy(doc, value)
        val out = PdfDict()
        for ((key, entry) in dict.entries) {
            if (key == "D") {
                out["D"] = destination(doc, entry) ?: return null
            } else {
                put(out, key, copy(doc, entry))
            }
        }
        return out
    }

    private fun named(doc: PdfDocument): Map<PdfString, PdfObject> = namedDestinations.getOrPut(doc) {
        val result = HashMap<PdfString, PdfObject>()
        val root = doc.catalog.dict("Names", doc)?.dict("Dests", doc)
        val stack = ArrayDeque<PdfDict>()
        val seen = HashSet<PdfDict>()
        if (root != null) stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!seen.add(node) || seen.size > 100_000) continue
            node.array("Names", doc)?.let { names ->
                for (i in 0 until names.size - 1 step 2) {
                    val key = names.resolved(i, doc) as? PdfString ?: continue
                    result.getOrPut(key) { names[i + 1] }
                }
            }
            node.array("Kids", doc)?.items?.forEach { kid -> (doc.resolve(kid) as? PdfDict)?.let { stack.addLast(it) } }
        }
        result
    }

    private fun copy(doc: PdfDocument, obj: PdfObject): PdfObject = when (obj) {
        is PdfRef -> reference(doc, obj)
        is PdfArray -> PdfArray(obj.items.mapTo(ArrayList(obj.size)) { copy(doc, it) })
        is PdfDict -> copyDict(doc, obj, skipLength = false)
        is PdfStream -> PdfStream(copyDict(doc, obj.dict, skipLength = true), obj.data)
        else -> obj
    }

    private fun copyDict(doc: PdfDocument, dict: PdfDict, skipLength: Boolean): PdfDict {
        val out = PdfDict()
        for ((key, value) in dict.entries) {
            if (skipLength && key == "Length") continue
            put(out, key, copy(doc, value))
        }
        return out
    }

    private fun reference(doc: PdfDocument, ref: PdfRef): PdfObject {
        if (doc === annotationDocument) annotationCopies[ref.number]?.let { return it }
        annotationTargets[doc]?.get(ref.number)?.let { return it }
        pageTargets[doc]?.get(ref.number)?.let { return it }
        val known = copies.getOrPut(doc) { HashMap() }
        known[ref.number]?.let { return it }
        if (doc.isPageTreeNode(ref.number)) return PdfNull
        val source = replacements[doc]?.get(ref.number) ?: doc.resolve(ref)
        if (source is PdfNull || (source is PdfDict && source.name("Type") in structuralTypes)) return PdfNull
        val target = writer.reserve()
        known[ref.number] = target
        pending.addLast(Pending(doc, source, target))
        return target
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            writer[next.target] = copy(next.document, next.source)
        }
    }

    private fun put(dict: PdfDict, key: String, value: PdfObject) {
        if (value !is PdfNull) dict[key] = value
    }

    private fun box(rect: PdfRect) = PdfArray(number(rect.left), number(rect.bottom), number(rect.right), number(rect.top))

    private fun number(value: Double): PdfObject = if (value == floor(value) && abs(value) < 1e15) PdfInt.of(value.toLong()) else PdfReal(value)

    companion object {
        private val skippedPageKeys = setOf("Type", "Parent", "MediaBox", "CropBox", "Rotate", "Resources", "Annots", "StructParents", "B")
        private val structuralTypes = setOf("Catalog", "Pages", "Page", "StructTreeRoot", "StructElem", "Outlines", "OBJR", "MCR")
    }
}
