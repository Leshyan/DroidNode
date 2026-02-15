package com.actl.mvp.api.v1.ui.xml

import android.util.Xml
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser

object UiXmlParser {

    fun parse(xml: String): ParseResult {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
            parser.next()
        }
        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "hierarchy") {
            error("Expected <hierarchy> root tag")
        }

        val rotation = parser.getAttributeValue(null, "rotation")?.toIntOrNull()
        val children = mutableListOf<UiNode>()
        var nodeCount = 0

        while (true) {
            val event = parser.next()
            when {
                event == XmlPullParser.START_TAG && parser.name == "node" -> {
                    val node = parseNode(parser)
                    children += node
                    nodeCount += node.totalNodeCount()
                }

                event == XmlPullParser.END_TAG && parser.name == "hierarchy" -> {
                    return ParseResult(
                        nodeCount = nodeCount,
                        hierarchy = UiHierarchy(
                            rotation = rotation,
                            children = children
                        )
                    )
                }

                event == XmlPullParser.END_DOCUMENT -> error("Unexpected end of XML document")
            }
        }
    }

    private fun parseNode(parser: XmlPullParser): UiNode {
        check(parser.eventType == XmlPullParser.START_TAG && parser.name == "node")

        val node = UiNode(
            index = parser.getAttributeValue(null, "index")?.toIntOrNull(),
            text = parser.getAttributeValue(null, "text").orEmpty(),
            resourceId = parser.getAttributeValue(null, "resource-id").orEmpty(),
            className = parser.getAttributeValue(null, "class").orEmpty(),
            packageName = parser.getAttributeValue(null, "package").orEmpty(),
            contentDesc = parser.getAttributeValue(null, "content-desc").orEmpty(),
            checkable = parser.getAttributeValue(null, "checkable")?.toBooleanStrictOrNull() ?: false,
            checked = parser.getAttributeValue(null, "checked")?.toBooleanStrictOrNull() ?: false,
            clickable = parser.getAttributeValue(null, "clickable")?.toBooleanStrictOrNull() ?: false,
            enabled = parser.getAttributeValue(null, "enabled")?.toBooleanStrictOrNull() ?: false,
            focusable = parser.getAttributeValue(null, "focusable")?.toBooleanStrictOrNull() ?: false,
            focused = parser.getAttributeValue(null, "focused")?.toBooleanStrictOrNull() ?: false,
            scrollable = parser.getAttributeValue(null, "scrollable")?.toBooleanStrictOrNull() ?: false,
            longClickable = parser.getAttributeValue(null, "long-clickable")?.toBooleanStrictOrNull() ?: false,
            password = parser.getAttributeValue(null, "password")?.toBooleanStrictOrNull() ?: false,
            selected = parser.getAttributeValue(null, "selected")?.toBooleanStrictOrNull() ?: false,
            visibleToUser = parser.getAttributeValue(null, "visible-to-user")?.toBooleanStrictOrNull() ?: false,
            bounds = parseBounds(parser.getAttributeValue(null, "bounds").orEmpty()),
            children = emptyList()
        )

        val children = mutableListOf<UiNode>()
        while (true) {
            val event = parser.next()
            when {
                event == XmlPullParser.START_TAG && parser.name == "node" -> children += parseNode(parser)
                event == XmlPullParser.END_TAG && parser.name == "node" -> {
                    return node.copy(children = children)
                }
                event == XmlPullParser.END_DOCUMENT -> error("Unexpected end of XML while parsing node")
            }
        }
    }

    private fun parseBounds(raw: String): UiBounds? {
        val pattern = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        val match = pattern.find(raw) ?: return null
        val values = match.groupValues
        return UiBounds(
            left = values[1].toInt(),
            top = values[2].toInt(),
            right = values[3].toInt(),
            bottom = values[4].toInt()
        )
    }
}

data class ParseResult(
    val nodeCount: Int,
    val hierarchy: UiHierarchy
)

@Serializable
data class UiHierarchy(
    val rotation: Int?,
    val children: List<UiNode>
)

@Serializable
data class UiNode(
    val index: Int?,
    val text: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val contentDesc: String,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    val visibleToUser: Boolean,
    val bounds: UiBounds?,
    val children: List<UiNode>
) {
    fun totalNodeCount(): Int = 1 + children.sumOf { it.totalNodeCount() }
}

@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
