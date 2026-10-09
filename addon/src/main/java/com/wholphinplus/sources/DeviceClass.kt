package com.wholphinplus.sources

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wholphinplus.sources.core.CopyFit
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.hdrRank
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * How much this TV box can do, so a Fire TV Stick browses as smoothly as a Shield and plays
 * what it can decode. Owner, 2026-10-08: "performance will be different on my Shield vs
 * Firestick vs basic Android TV… fine tune UI, supported playback and fallbacks".
 *
 * - **Full** (Shield, Fire TV Cube, boxes with a big CPU core and 3 GB): everything as designed.
 * - **Balanced** (Fire TV Stick 4K Max, Chromecast with Google TV, Onn 4K): a little less built
 *   ahead of the screen and fewer badge lookups at once.
 * - **Light** (Fire TV Stick, Stick Lite, 1-1.5 GB boxes): small card pictures from the start, the
 *   backdrop's slow zoom off (it redraws the whole screen for 12 s), much less built off screen.
 *
 * Motion itself ([com.wholphinplus.sources.cinema.CinemaGlide]) is the same everywhere: the glide
 * is cheap, what it moves is what costs. Playback: [fit] judges each copy against the hardware
 * decoders and the screen, so the picker (and "play the top copy") leads with one this box plays.
 */
object DeviceClass {
    enum class Tier(val label: String) { FULL("Full"), BALANCED("Balanced"), LIGHT("Light") }

    const val AUTO = "auto"

    /** One codec's hardware decoder: the biggest picture it takes, and the extras it knows. */
    data class Decoder(
        val maxWidth: Int,
        val maxHeight: Int,
        val tenBit: Boolean,
        val dolbyVision: Boolean,
    )

    /** What was found; plain values so [tierOf] and [fitOf] can be tested off the device. */
    data class Facts(
        val maker: String = "",
        val model: String = "",
        val soc: String = "",
        val sdk: Int = 0,
        val ramMb: Int = 0,
        val lowRam: Boolean = false,
        val heapMb: Int = 0,
        val cores: Int = 0,
        val maxGhz: Float = 0f,
        // A core bigger than the A53/A55 class (Shield's A57, Cube's A73); null: couldn't tell
        val bigCore: Boolean? = null,
        val fireTv: Boolean = false,
        val emulator: Boolean = false,
        // Tallest mode the screen offers (2160 on a 4K TV), and the HDR formats it takes
        val screenHeight: Int = 0,
        val screenHdr: Set<String> = emptySet(),
        // Hardware video decoders by codec label (Labels.videoCodec: "HEVC", "H.264", "AV1", "VP9"…)
        val decoders: Map<String, Decoder> = emptyMap(),
    )

    /** The setting (Settings → About & Help → This TV): [AUTO] or a [Tier] name. Set by ConnectionStore. */
    var mode by mutableStateOf(AUTO)

    /** What the hardware looks like; [AUTO] uses it. */
    var detected by mutableStateOf(Tier.FULL)
        private set

    val tier: Tier get() = if (mode == AUTO) detected else runCatching { Tier.valueOf(mode) }.getOrDefault(detected)

    val light: Boolean get() = tier == Tier.LIGHT

    /** How much of the off-screen list is built ahead of time: all of it on Full, less below. */
    val cacheScale: Float
        get() =
            when (tier) {
                Tier.FULL -> 1f
                Tier.BALANCED -> 0.7f
                Tier.LIGHT -> 0.4f
            }

    /** Cards built past the edge of the row you rest on, in dp ([com.wholphinplus.sources.cinema] RestedRowWindow). */
    val rowAheadDp: Float
        get() =
            when (tier) {
                Tier.FULL -> 900f
                Tier.BALANCED -> 680f
                Tier.LIGHT -> 460f
            }

    /** Titles' art looked up ahead in the row you rest on. */
    val artAhead: Int
        get() =
            when (tier) {
                Tier.FULL -> 16
                Tier.BALANCED -> 12
                Tier.LIGHT -> 8
            }

    /** Rows around you whose card pictures load into memory while the remote rests ([com.wholphinplus.sources.cinema.PosterPrefetch]). */
    val prefetchMemoryRows: Int
        get() =
            when (tier) {
                // A long hold travels ~6 rows: with 4 the last ones went by as blank cards (~30 MB at 8)
                Tier.FULL -> 8
                Tier.BALANCED -> 4
                Tier.LIGHT -> 1
            }

    /** Rows ahead whose card pictures download to disk while the remote rests. */
    val prefetchDiskRows: Int
        get() =
            when (tier) {
                Tier.FULL -> 16
                Tier.BALANCED -> 10
                Tier.LIGHT -> 4
            }

    /** Cards per row loaded ahead. */
    val prefetchCards: Int
        get() =
            when (tier) {
                Tier.FULL -> 10
                Tier.BALANCED -> 8
                Tier.LIGHT -> 6
            }

    /** Cards whose picture is already in memory fill while the page moves (one a frame); Light waits for a rest. */
    val fillWhileMoving: Boolean
        get() = tier != Tier.LIGHT

    /** Off-screen card pictures started per frame. */
    val picturesPerFrame: Int
        get() = if (tier == Tier.LIGHT) 1 else 2

    /** Still frames before card faces start filling (the billboard's change goes first). */
    val settleFrames: Int
        get() = if (tier == Tier.LIGHT) 8 else 4

    /** Poster badge lookups at once ([com.wholphinplus.sources.cinema.PosterOverlays]). */
    val lookupsAtOnce: Int
        get() =
            when (tier) {
                Tier.FULL -> 6
                Tier.BALANCED -> 4
                Tier.LIGHT -> 2
            }

    @Volatile var facts = Facts()
        private set

    @Volatile private var started = false

    /** Reads the hardware once (cheap facts now; decoders and screen on a background thread). */
    fun init(context: Context) {
        if (started) return
        started = true
        logCrashes()
        val app = context.applicationContext
        facts = cheapFacts(app)
        detected = tierOf(facts)
        Thread {
            try {
                facts = facts.copy(decoders = decoders(), screenHeight = screenHeight(app), screenHdr = screenHdr(app))
                Timber.i("Orca device: %s, runs as %s", summary(), tier.label)
            } catch (e: Throwable) {
                Timber.w(e, "Orca device: decoder scan failed")
            }
            com.wholphinplus.sources.core.copyFit = CopyFit { fitOf(facts, it) }
        }.apply { name = "orca-device"; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * A crash's stack in the log before the crash reporter takes it: the reporter keeps it to
     * itself (2026-10-09: three launches crashed right after an install and nothing was left to
     * read). Chained, so the reporter still runs.
     */
    private fun logCrashes() {
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { android.util.Log.e("OrcaCrash", "Uncaught on ${t.name}", e) }
            next?.uncaughtException(t, e)
        }
    }

    /** One line for the log and Settings: "NVIDIA SHIELD Android TV · Tegra X1 · 3.0 GB · 8 cores · 4K HDR10 DV". */
    fun summary(f: Facts = facts): String {
        val screen =
            when {
                f.screenHeight >= 2160 -> "4K"
                f.screenHeight > 0 -> "${f.screenHeight}p"
                else -> null
            }
        val hdr = listOf("Dolby Vision" to "DV", "HDR10+" to "HDR10+", "HDR10" to "HDR10", "HLG" to "HLG").filter { it.first in f.screenHdr }.map { it.second }
        val decode = f.decoders.keys.sorted().joinToString("/").takeIf { it.isNotBlank() }?.let { "decodes $it" }
        return listOfNotNull(
            listOf(f.maker.replaceFirstChar { it.titlecase(Locale.US) }, f.model).filter { it.isNotBlank() }.distinct().joinToString(" "),
            f.soc.takeIf { it.isNotBlank() },
            f.ramMb.takeIf { it > 0 }?.let { "%.1f GB".format(Locale.US, it / 1024f) },
            f.cores.takeIf { it > 0 }?.let { c -> if (f.maxGhz >= 0.3f) "%d cores %.1f GHz".format(Locale.US, c, f.maxGhz) else "$c cores" },
            (listOfNotNull(screen) + hdr).joinToString(" ").takeIf { it.isNotBlank() },
            decode,
        ).joinToString("  ·  ")
    }

    /** The detected class from [f] (pure). */
    fun tierOf(f: Facts): Tier =
        when {
            // The emulator bench stands in for the Shield; NVIDIA boxes are the reference
            f.emulator || f.maker.equals("NVIDIA", ignoreCase = true) -> Tier.FULL
            f.lowRam || (f.ramMb in 1 until 1_800) -> Tier.LIGHT
            f.bigCore == false && f.maxGhz in 0.1f..1.75f -> Tier.LIGHT
            f.ramMb >= 2_700 && f.bigCore == true -> Tier.FULL
            // No core info (rare): a roomy box with a fast clock
            f.ramMb >= 3_500 && f.bigCore == null && f.maxGhz >= 2.0f -> Tier.FULL
            else -> Tier.BALANCED
        }

    /**
     * How well this box plays [s] (pure). A copy plays when a hardware decoder takes its codec at
     * its size (unknown codec or size: assumed fine). Quality and HDR count only as far as the
     * screen shows them: a 4K copy on a 1080p TV ranks with the 1080p ones (and then the copy
     * that doesn't overshoot leads), Dolby Vision without a DV decoder and screen counts as HDR10.
     */
    fun fitOf(
        f: Facts,
        s: ExternalSource,
    ): CopyFit.Fit {
        val dec = f.decoders[s.videoCodec]
        val width = s.width.takeIf { it > 0 } ?: widthFor(s.qualityRank)
        val height = s.height.takeIf { it > 0 } ?: s.qualityRank
        val plays =
            when {
                // Nothing known about the hardware (decoder scan not done): no opinion
                f.decoders.isEmpty() || s.videoCodec.isBlank() || s.compatible -> true
                dec == null -> false
                // Portrait-friendly check: 3840x1600 fits a 3840x2160 decoder either way round
                else -> fitsSize(dec, width, height)
            }
        val screen = if (f.screenHeight > 0) maxOf(f.screenHeight, 720) else Int.MAX_VALUE
        val shown = minOf(s.qualityRank, screen)
        val hdr = hdrRank(s.hdr)
        val shownHdr =
            when {
                hdr == 0 || f.screenHdr.isEmpty() && f.screenHeight == 0 -> hdr
                f.screenHdr.isEmpty() -> 0
                hdr == 3 && ("Dolby Vision" !in f.screenHdr || dec?.dolbyVision == false) -> 1
                hdr == 2 && "HDR10+" !in f.screenHdr -> 1
                else -> hdr
            }
        return CopyFit.Fit(plays = plays, quality = shown, hdr = shownHdr, overshoot = s.qualityRank > screen)
    }

    private fun widthFor(height: Int): Int = if (height <= 0) 0 else height * 16 / 9

    private fun fitsSize(
        d: Decoder,
        w: Int,
        h: Int,
    ): Boolean {
        if (w <= 0 || h <= 0) return true
        val (long, short) = maxOf(w, h) to minOf(w, h)
        return long <= maxOf(d.maxWidth, d.maxHeight) && short <= minOf(d.maxWidth, d.maxHeight)
    }

    // ---- Reading the hardware

    private fun cheapFacts(context: Context): Facts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val parts = cpuParts()
        val pm = context.packageManager
        return Facts(
            maker = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty() else Build.HARDWARE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            ramMb = (mem.totalMem / (1024 * 1024)).toInt(),
            lowRam = am?.isLowRamDevice == true,
            heapMb = am?.largeMemoryClass ?: 0,
            cores = Runtime.getRuntime().availableProcessors(),
            maxGhz = maxGhz(),
            bigCore = if (parts.isEmpty()) null else parts.any { it !in LITTLE_CORES },
            fireTv = pm.hasSystemFeature("amazon.hardware.fire_tv") || Build.MODEL.orEmpty().startsWith("AFT"),
            emulator = Build.HARDWARE.orEmpty().let { it == "ranchu" || it == "goldfish" } || Build.FINGERPRINT.orEmpty().startsWith("generic"),
        )
    }

    // ARM's little cores (A32, A35, A53, A55, A510): Fire TV Sticks, most budget boxes
    private val LITTLE_CORES = setOf(0xd01, 0xd04, 0xd03, 0xd05, 0xd46)

    /** The "CPU part" of each ARM core (0xd07 = A57…); empty when not ARM or unreadable. */
    private fun cpuParts(): Set<Int> =
        runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("CPU part") }
                .mapNotNull { it.substringAfter(':').trim().removePrefix("0x").toIntOrNull(16) }
                .toSet()
        }.getOrDefault(emptySet())

    private fun maxGhz(): Float =
        runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
                .maxOrNull()?.let { it / 1_000_000f } ?: 0f
        }.getOrDefault(0f)

    private fun display(context: Context): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun screenHeight(context: Context): Int {
        val d = display(context) ?: return 0
        return d.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
    }

    private fun screenHdr(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < 24) return emptySet()
        val types = display(context)?.hdrCapabilities?.supportedHdrTypes ?: return emptySet()
        return types.toList().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                4 -> "HDR10+" // HDR_TYPE_HDR10_PLUS, API 29
                else -> null
            }
        }.toSet()
    }

    private val MIMES =
        mapOf(
            "video/hevc" to "HEVC",
            "video/avc" to "H.264",
            "video/av01" to "AV1",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/mpeg2" to "MPEG-2",
            "video/dolby-vision" to "Dolby Vision",
        )

    /** Hardware decoders only: a software HEVC decoder "supports" 4K at a few frames a second. */
    private fun decoders(): Map<String, Decoder> {
        val out = mutableMapOf<String, Decoder>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || !hardware(info)) continue
            for (mime in info.supportedTypes) {
                val label = MIMES[mime.lowercase(Locale.US)] ?: continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val video = caps.videoCapabilities ?: continue
                val tenBit =
                    caps.profileLevels.any {
                        when (label) {
                            "HEVC" -> it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                            "VP9" -> it.profile >= MediaCodecInfo.CodecProfileLevel.VP9Profile2
                            "AV1" -> it.profile >= 2
                            else -> false
                        }
                    }
                val d = Decoder(video.supportedWidths.upper, video.supportedHeights.upper, tenBit, label == "Dolby Vision")
                out[label] = out[label]?.let { old -> if (old.maxWidth * old.maxHeight >= d.maxWidth * d.maxHeight) old.copy(tenBit = old.tenBit || d.tenBit) else d.copy(tenBit = old.tenBit || d.tenBit) } ?: d
            }
        }
        // A Dolby Vision decoder plays DV HEVC copies; the copies themselves say "HEVC"
        out.remove("Dolby Vision")?.let { dv -> out["HEVC"]?.let { out["HEVC"] = it.copy(dolbyVision = true) } ?: run { out["HEVC"] = dv } }
        return out
    }

    private fun hardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isAlias
        val n = info.name.lowercase(Locale.US)
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.contains(".sw."))
    }
}
