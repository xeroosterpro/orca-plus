package com.wholphinplus.sources.core

import com.wholphinplus.sources.ui.badgesFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays recorded input/output pairs (test resource cleanroom-golden.json) against the
 * matching, label and badge functions, and lists every disagreement in one failure.
 */
class CleanRoomGoldenTest {
    private val cases: List<JsonObject> by lazy {
        // The recordings come from the owner's servers and stay out of git: no file, no test
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE)
        org.junit.Assume.assumeTrue("$RESOURCE not present", stream != null)
        val text = stream!!.bufferedReader().use { it.readText() }
        (Json.parseToJsonElement(text) as JsonArray).filterIsInstance<JsonObject>()
    }

    @Test fun `every recorded case gives the recorded answer`() {
        assertTrue("no cases loaded", cases.isNotEmpty())
        val mismatches = mutableListOf<String>()
        var count = 0
        for (case in cases) {
            val fn = case.string("fn")
            val input = case.obj("in") ?: JsonObject(emptyMap())
            val expected = (case["out"] ?: JsonNull).let { if (fn == "badgesFor") asMarks(it) else it }
            val actual =
                try {
                    run(fn, input)
                } catch (e: Exception) {
                    JsonPrimitive("THROWS ${e.javaClass.simpleName}")
                }
            if (!same(expected, actual)) {
                count++
                if (mismatches.size < 50) mismatches += "$fn in=$input expected=$expected actual=$actual"
            }
        }
        if (count > 0) fail("$count of ${cases.size} cases differ (first ${mismatches.size}):\n" + mismatches.joinToString("\n"))
    }

    /** The cases were recorded on a personal build; a shared build drops every badge image. */
    // Recorded when badges were downloaded images: a badge that had one is now a drawn mark
    private fun asMarks(badges: JsonElement): JsonElement =
        JsonArray(
            (badges as? JsonArray).orEmpty().map { b ->
                val o = (b as? JsonObject).orEmpty()
                JsonObject(mapOf("text" to (o["text"] ?: JsonNull), "mark" to JsonPrimitive(o["imageUrl"].let { it != null && it !is JsonNull })))
            },
        )

    private fun same(
        expected: JsonElement,
        actual: JsonElement,
    ): Boolean {
        val exp = expected as? JsonPrimitive
        val act = actual as? JsonPrimitive
        // A recorded "THROWS java.lang.Foo" also matches the simple class name
        if (exp != null && act != null && exp.isString && exp.content.startsWith("THROWS ") && act.content.startsWith("THROWS ")) {
            return exp.content.substringAfterLast('.').substringAfterLast(' ') == act.content.removePrefix("THROWS ")
        }
        return expected == actual
    }

    private fun run(
        fn: String,
        i: JsonObject,
    ): JsonElement =
        when (fn) {
            "audio" -> JsonPrimitive(Labels.audio(i.text("codec"), i.text("profile"), i.number("channels"), i.text("trackTitle"), i.text("fileName")))
            "videoCodec" -> JsonPrimitive(Labels.videoCodec(i.text("codec")))
            "hdrFromName" -> JsonPrimitive(Labels.hdrFromName(i.text("fileName")))
            "hdrRank" -> JsonPrimitive(hdrRank(i.text("hdr")))
            "qualityLabel" -> JsonPrimitive(qualityLabel(i.number("height") ?: 0, i.number("width") ?: 0, i.text("fileName")))
            "qualityRank" -> JsonPrimitive(qualityRank(i.text("quality")))
            "normalizeTitle" -> JsonPrimitive(Matcher.normalizeTitle(i.text("title")))
            "score" -> {
                val s =
                    Matcher.score(
                        i.text("requestedTitle"),
                        i.number("requestedYear"),
                        i.nullableText("imdbId"),
                        i.number("tmdbId"),
                        i.number("tvdbId"),
                        candidate(i.obj("candidate")),
                    )
                JsonObject(mapOf("score" to JsonPrimitive(s), "isAcceptable" to JsonPrimitive(Matcher.isAcceptable(s))))
            }
            "isLikelySameVersion" ->
                JsonPrimitive(Matcher.isLikelySameVersion(i.text("requestedTitle"), i.number("requestedYear"), candidate(i.obj("candidate"))))
            "isAcceptable" -> JsonPrimitive(Matcher.isAcceptable(i.number("score") ?: 0))
            "normalizeServerUrl" -> JsonPrimitive(normalizeServerUrl(i.text("rawUrl")))
            "hostLabel" -> JsonPrimitive(hostLabel(i.text("serverUrl")))
            "sameEndpoint" -> JsonPrimitive(sameEndpoint(i.text("left"), i.text("right")))
            "detectServerKind" -> JsonPrimitive(detectServerKind(i.text("productName"), i.text("serverName")).name)
            "badgesFor" ->
                JsonArray(
                    badgesFor(source(i)).map {
                        JsonObject(mapOf("text" to JsonPrimitive(it.text), "mark" to JsonPrimitive(it.mark)))
                    },
                )
            "size" -> JsonPrimitive(source(i).size)
            "sourceRanking" -> {
                val sources = i.objects("sources").map(::source)
                JsonArray(sources.withIndex().sortedWith(compareBy(sourceRanking) { it.value }).map { JsonPrimitive(it.index) })
            }
            else -> error("Unknown fn $fn")
        }

    private fun candidate(o: JsonObject?): CandidateInfo {
        val c = o ?: JsonObject(emptyMap())
        val ids = c.obj("providerIds")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        return CandidateInfo(c.text("title"), c.number("productionYear"), ids)
    }

    private fun source(o: JsonObject): ExternalSource =
        ExternalSource(
            o.text("connectionId"),
            o.text("serverLabel"),
            o.text("serverKind").let { k -> ServerKind.entries.firstOrNull { it.name == k } ?: ServerKind.UNKNOWN },
            o.text("url"),
            o.text("quality"),
            o.number("qualityRank") ?: 0,
            o.text("videoCodec"),
            o.text("hdr"),
            o.text("audio"),
            o.text("container"),
            o.long("sizeBytes") ?: 0L,
            o.text("fileName"),
            compatible = o.boolean("compatible") ?: false,
        )

    private fun JsonObject.text(name: String): String = string(name)

    private fun JsonObject.nullableText(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.number(name: String): Int? = int(name)

    private companion object {
        const val RESOURCE = "cleanroom-golden.json"
    }
}
