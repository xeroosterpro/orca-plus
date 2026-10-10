package com.wholphinplus.sources

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserItemDataDto

/**
 * The Jellyfin types Wholphin expects from each address, read from the Jellyfin SDK's own
 * serializers, and [fit] to make an Emby answer one: every field Jellyfin requires is there, ids are
 * UUIDs where Jellyfin has a UUID, enums hold values Jellyfin knows. Walking the SDK's descriptors
 * covers the whole model (users, items, media sources, streams, people…) instead of a list of fixes.
 */
internal object EmbyShapes {
    private val user = UserDto.serializer().descriptor
    private val item = BaseItemDto.serializer().descriptor
    private val items = BaseItemDtoQueryResult.serializer().descriptor
    private val itemList = ListSerializer(BaseItemDto.serializer()).descriptor
    private val userList = ListSerializer(UserDto.serializer()).descriptor
    private val userData = UserItemDataDto.serializer().descriptor
    private val playback = PlaybackInfoResponse.serializer().descriptor
    private val auth = AuthenticationResult.serializer().descriptor

    // By the address Wholphin asked (Jellyfin's, before the rewrite), first match wins
    private val routes: List<Pair<Regex, SerialDescriptor>> =
        listOf(
            Regex("(?i)/Users/AuthenticateByName$") to auth,
            Regex("(?i)/Users/Me$") to user,
            Regex("(?i)/Users(/Public)?$") to userList,
            Regex("(?i)/Users/[0-9a-f-]{32,36}$") to user,
            Regex("(?i)/Items/Latest$") to itemList,
            Regex("(?i)/DisplayPreferences/[^/]+$") to org.jellyfin.sdk.model.api.DisplayPreferencesDto.serializer().descriptor,
            Regex("(?i)/Items/[^/]+/PlaybackInfo$") to playback,
            Regex("(?i)/(UserPlayedItems|UserFavoriteItems)/[^/]+$") to userData,
            Regex("(?i)/UserItems/[^/]+/UserData$") to userData,
            Regex("(?i)/Items/[^/]+/(Similar|Intros|LocalTrailers|SpecialFeatures)$") to items,
            Regex("(?i)/Items/[0-9a-f-]{32,36}$") to item,
            Regex("(?i)/(Items|UserViews|UserItems/Resume|Shows/NextUp|Shows/Upcoming|Shows/[^/]+/(Episodes|Seasons)|Persons|Genres|Studios|Artists|MusicGenres|Movies/Recommendations)$") to items,
        )

    fun of(
        method: String,
        path: String,
    ): SerialDescriptor? {
        val d = routes.firstOrNull { it.first.containsMatchIn(path) }?.second ?: return null
        return d.takeIf { method == "GET" || method == "POST" }
    }

    /** [e] fitted to [d] (see the class). */
    fun fit(
        e: JsonElement,
        d: SerialDescriptor,
    ): JsonElement =
        when {
            e is JsonNull -> e
            d.serialName.removeSuffix("?").endsWith("UUID") -> uuid(e)
            d.kind == StructureKind.LIST && e is JsonArray -> JsonArray(e.map { fit(it, d.getElementDescriptor(0)) })
            d.kind == StructureKind.MAP && e is JsonObject -> JsonObject(e.mapValues { fit(it.value, d.getElementDescriptor(1)) })
            d.kind == StructureKind.CLASS && e is JsonObject -> obj(e, d)
            d.kind == SerialKind.ENUM && e is JsonPrimitive -> enum(e, d)
            else -> e
        }

    private fun obj(
        given: JsonObject,
        d: SerialDescriptor,
    ): JsonObject {
        val e = if (d.serialName.removeSuffix("?").endsWith(".MediaStream")) videoRange(given) else given
        val out = LinkedHashMap<String, JsonElement>(e)
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            val ed = d.getElementDescriptor(i)
            val v = e[name]
            when {
                v == null || v is JsonNull -> if (!d.isElementOptional(i) && !ed.isNullable) out[name] = blank(ed)
                else -> {
                    val f = fit(v, ed)
                    // A value Jellyfin can't read where it may be left out: left out
                    out[name] = if (f is JsonNull && !ed.isNullable && d.isElementOptional(i)) continue else f
                }
            }
            if (out[name] is JsonNull && !ed.isNullable) {
                if (d.isElementOptional(i)) out.remove(name) else out[name] = blank(ed)
            }
        }
        return JsonObject(out)
    }

    /**
     * A picture's range in Jellyfin's two fields. Emby says "HDR 10", "HLG" or "DolbyVision" in
     * VideoRange (Jellyfin: SDR or HDR) and the Dolby Vision profile in ExtendedVideoSubType; read
     * as they were, every HDR copy looked SDR (an unknown value) and Dolby Vision went unnoticed.
     */
    internal fun videoRange(e: JsonObject): JsonObject {
        fun field(name: String) = (e[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val range = field("VideoRange").replace(" ", "")
        if (range.isEmpty() || field("VideoRangeType").isNotEmpty()) return e
        val ext = field("ExtendedVideoType")
        val sub = field("ExtendedVideoSubType")
        val type =
            when {
                range.equals("DolbyVision", true) || ext.equals("DolbyVision", true) ->
                    when {
                        sub.startsWith("DoviProfile7", true) -> "DOVI" // (the SDK has no DOVIWithEL)
                        sub.equals("DoviProfile81", true) -> "DOVIWithHDR10"
                        sub.equals("DoviProfile82", true) -> "DOVIWithSDR"
                        sub.equals("DoviProfile84", true) -> "DOVIWithHLG"
                        else -> "DOVI"
                    }
                ext.equals("Hdr10Plus", true) || range.equals("HDR10+", true) -> "HDR10Plus"
                range.equals("HLG", true) || ext.equals("HyperLogGamma", true) -> "HLG"
                range.equals("HDR10", true) || range.equals("HDR", true) || ext.equals("Hdr10", true) -> "HDR10"
                range.equals("SDR", true) -> "SDR"
                else -> return e
            }
        return JsonObject(e + mapOf("VideoRange" to JsonPrimitive(if (type == "SDR") "SDR" else "HDR"), "VideoRangeType" to JsonPrimitive(type)))
    }

    /** A numeric Emby id as a marked UUID; a real UUID as it is. */
    private fun uuid(e: JsonElement): JsonElement {
        val s = (e as? JsonPrimitive)?.content ?: return e
        return if (s.isNotEmpty() && s.length <= 15 && s.all(Char::isDigit)) JsonPrimitive(EmbyBridge.toUuid(s)) else e
    }

    /** A value Jellyfin's enum doesn't have: null where allowed, else its first value. */
    private fun enum(
        e: JsonPrimitive,
        d: SerialDescriptor,
    ): JsonElement {
        val v = e.content
        for (i in 0 until d.elementsCount) if (d.getElementName(i).equals(v, ignoreCase = true)) return JsonPrimitive(d.getElementName(i))
        return if (d.isNullable) JsonNull else JsonPrimitive(d.getElementName(0))
    }

    /** What Jellyfin would send for a required field Emby left out. */
    private fun blank(d: SerialDescriptor): JsonElement =
        when {
            d.serialName.removeSuffix("?").endsWith("UUID") -> JsonPrimitive("00000000-0000-0000-0000-000000000000")
            d.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
            d.kind == PrimitiveKind.INT || d.kind == PrimitiveKind.LONG || d.kind == PrimitiveKind.SHORT || d.kind == PrimitiveKind.BYTE -> JsonPrimitive(0)
            d.kind == PrimitiveKind.FLOAT || d.kind == PrimitiveKind.DOUBLE -> JsonPrimitive(0.0)
            d.kind == PrimitiveKind.STRING || d.kind == PrimitiveKind.CHAR -> JsonPrimitive("")
            d.kind == SerialKind.ENUM -> JsonPrimitive(d.getElementName(0))
            d.kind == StructureKind.LIST -> JsonArray(emptyList())
            d.kind == StructureKind.MAP -> JsonObject(emptyMap())
            d.kind == StructureKind.CLASS || d.kind is PolymorphicKind -> obj(JsonObject(emptyMap()), d)
            else -> JsonNull
        }
}
