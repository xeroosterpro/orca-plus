package com.wholphinplus.sources

/**
 * The version as people read it. Under the hood every build keeps Wholphin's form,
 * `1.0.9-47-g1a2b3c4d` (installed copies find updates by comparing release titles in that form,
 * so it can't change); people see "Orca+ 47". Public releases count up one per release (r47 is
 * Orca+ 47); a personal build counts the private repo's commits, so it reads "Orca+ dev 354".
 */
object OrcaVersion {
    private val RAW = Regex("v?(\\d+\\.\\d+\\.\\d+)(?:-(\\d+)-g[0-9a-zA-Z]+)?")

    /** "Orca+ 47" for this build's [raw] version ("Orca+ dev 354" for a personal build). */
    fun name(raw: String): String = label(raw, BuildConfig.PUBLIC_BUILD)

    /** "Orca+ 48" for a public release's [raw] title (releases are always public). */
    fun release(raw: String): String = label(raw, true)

    /** "Orca+ 47 · built on Wholphin 1.0.9". */
    fun full(raw: String): String {
        val base = RAW.matchEntire(raw.trim())?.groupValues?.get(1) ?: return raw
        return "${name(raw)}  ·  built on Wholphin $base"
    }

    private fun label(
        raw: String,
        public: Boolean,
    ): String {
        val n = RAW.matchEntire(raw.trim())?.groupValues?.get(2) ?: return raw
        return when {
            n.isEmpty() -> "Orca+"
            public -> "Orca+ $n"
            else -> "Orca+ dev $n"
        }
    }
}
