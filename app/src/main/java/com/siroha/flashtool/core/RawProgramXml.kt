package com.siroha.flashtool.core

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringWriter

/** One <program> entry from a qdl rawprogram*.xml — one flashable partition. */
data class RawProgramPartition(
    val label: String,
    val filename: String,
    val rawAttributes: Map<String, String>
)

/**
 * Minimal reader/writer for qdl's rawprogram*.xml so the QDL Flash screen can
 * show a partition checklist instead of an all-or-nothing flash. We don't
 * need to understand every attribute — we just need to preserve every
 * <program> element byte-for-byte and filter which ones are included.
 *
 * Uses android.util.Xml (bundled with every Android version) rather than
 * XmlPullParserFactory.newInstance(), which depends on service-discovery
 * metadata that can be stripped by R8/minification.
 */
object RawProgramXml {

    fun parsePartitions(file: File): List<RawProgramPartition> {
        val parser = Xml.newPullParser()
        parser.setInput(file.reader())
        val result = mutableListOf<RawProgramPartition>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "program") {
                val attrs = (0 until parser.attributeCount).associate {
                    parser.getAttributeName(it) to parser.getAttributeValue(it)
                }
                result += RawProgramPartition(
                    label = attrs["label"] ?: "(unlabeled)",
                    filename = attrs["filename"] ?: "",
                    rawAttributes = attrs
                )
            }
            event = parser.next()
        }
        return result
    }

    /**
     * Writes a new rawprogram XML containing only the <program> entries
     * whose label is in [keepLabels], preserving every original attribute.
     * qdl reads this filtered file instead of the full one when the user
     * has deselected some partitions in the checklist.
     */
    fun writeFiltered(sourceFile: File, keepLabels: Set<String>, destFile: File) {
        val parser = Xml.newPullParser()
        parser.setInput(sourceFile.reader())

        val sw = StringWriter()
        sw.append("<?xml version=\"1.0\" ?>\n<data>\n")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "program") {
                val label = parser.getAttributeValue(null, "label") ?: ""
                if (label in keepLabels) {
                    sw.append("  <program")
                    for (i in 0 until parser.attributeCount) {
                        sw.append(" ${parser.getAttributeName(i)}=\"${parser.getAttributeValue(i)}\"")
                    }
                    sw.append("/>\n")
                }
            }
            event = parser.next()
        }
        sw.append("</data>\n")
        destFile.writeText(sw.toString())
    }
}
