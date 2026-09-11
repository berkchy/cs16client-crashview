package com.pickle.patcher.lib

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Release bundle manifest. The GitHub workflow produces one `bundle.zip` per release:
 * entries are the amxx/metamod payload (libs + addons configs/plugins) that the patcher
 * injects into a stock CS16Client APK.
 *
 * The manifest (bundle.json, stored at the bundle root) describes every payload entry with
 * its target path inside the patched APK and the compression that must be used. Any entry
 * in the zip streams that is not listed is treated as default STORED.
 */
@Serializable
data class BundleManifest(
    val version: String = "",
    val game: String = "cs16client",
    val abi: String = "arm64-v8a",
    val entries: List<BundleEntry> = emptyList(),
) {
    @Serializable
    data class BundleEntry(
        val source: String,          // path inside bundle.zip
        val target: String,          // path inside the patched APK
        val method: Compression = Compression.STORED,
        val required: Boolean = true,
        val description: String = "",
    )

    enum class Compression { STORED, DEFLATED }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

        fun parse(manifestJson: String): BundleManifest = json.decodeFromString(manifestJson)

        fun encode(manifest: BundleManifest): String = json.encodeToString(
            kotlinx.serialization.serializer<BundleManifest>(), manifest
        )
    }
}

/**
 * Describes which existing APK entries must be removed/ignored because the bundle replaces them.
 */
@Serializable
data class ExcludeRule(
    val prefixes: List<String> = emptyList(),
    val exact: List<String> = emptyList(),
) {
    companion object {
        /**
         * Default exclusions: the old AMXX/metamod libs (core + 11 module libs) and any stale
         * signature block. Only those targets are pruned; the game's own native libs
         * (lib/arm64-v8a/libxash*.so, libSDL*.so, ...) are never touched, so the patched
         * APK keeps its engine intact.
         */
        val DEFAULT = ExcludeRule(
            prefixes = listOf(
                "lib/arm64-v8a/libamxmodx.so",
                "lib/arm64-v8a/libmetamod.so",
                "lib/arm64-v8a/libcstrike_amxx_amd64.so",
                "lib/arm64-v8a/libcsx_amxx_amd64.so",
                "lib/arm64-v8a/libengine_amxx_amd64.so",
                "lib/arm64-v8a/libfakemeta_amxx_amd64.so",
                "lib/arm64-v8a/libfun_amxx_amd64.so",
                "lib/arm64-v8a/libgeoip_amxx_amd64.so",
                "lib/arm64-v8a/libhamsandwich_amxx_amd64.so",
                "lib/arm64-v8a/libjson_amxx_amd64.so",
                "lib/arm64-v8a/libnvault_amxx_amd64.so",
                "lib/arm64-v8a/libreapi_amxx_amd64.so",
                "lib/arm64-v8a/libregex_amxx_amd64.so",
                "lib/arm64-v8a/libsockets_amxx_amd64.so",
                "lib/arm64-v8a/libsqlite_amxx_amd64.so",
                // v7a: ARM32 modules use "_arm" (pre-v1.22.3 legacy "_amd64" kept)
                "lib/armeabi-v7a/libcstrike_amxx_arm.so",
                "lib/armeabi-v7a/libcsx_amxx_arm.so",
                "lib/armeabi-v7a/libengine_amxx_arm.so",
                "lib/armeabi-v7a/libfakemeta_amxx_arm.so",
                "lib/armeabi-v7a/libfun_amxx_arm.so",
                "lib/armeabi-v7a/libgeoip_amxx_arm.so",
                "lib/armeabi-v7a/libhamsandwich_amxx_arm.so",
                "lib/armeabi-v7a/libjson_amxx_arm.so",
                "lib/armeabi-v7a/libnvault_amxx_arm.so",
                "lib/armeabi-v7a/libreapi_amxx_arm.so",
                "lib/armeabi-v7a/libregex_amxx_arm.so",
                "lib/armeabi-v7a/libsockets_amxx_arm.so",
                "lib/armeabi-v7a/libsqlite_amxx_arm.so",
                "lib/armeabi-v7a/libcstrike_amxx_amd64.so",
                "lib/armeabi-v7a/libcsx_amxx_amd64.so",
                "lib/armeabi-v7a/libengine_amxx_amd64.so",
                "lib/armeabi-v7a/libfakemeta_amxx_amd64.so",
                "lib/armeabi-v7a/libfun_amxx_amd64.so",
                "lib/armeabi-v7a/libgeoip_amxx_amd64.so",
                "lib/armeabi-v7a/libhamsandwich_amxx_amd64.so",
                "lib/armeabi-v7a/libjson_amxx_amd64.so",
                "lib/armeabi-v7a/libnvault_amxx_amd64.so",
                "lib/armeabi-v7a/libreapi_amxx_amd64.so",
                "lib/armeabi-v7a/libregex_amxx_amd64.so",
                "lib/armeabi-v7a/libsockets_amxx_amd64.so",
                "lib/armeabi-v7a/libsqlite_amxx_amd64.so",
                "META-INF/",
            ),
            exact = emptyList(),
        )
    }
}

/** A parsed bundle ready for injection. */
data class Bundle(
    val manifest: BundleManifest,
    val files: Map<String, ByteArray>,      // bundle.zip relative path -> content
) {
    fun resolveEntry(e: BundleManifest.BundleEntry): ByteArray? = files[e.source]

    companion object {
        private const val MANIFEST_PATH = "bundle.json"

        /** Parse a bundle zip's bytes. */
        fun fromZip(zipBytes: ByteArray): Bundle {
            val file = ZipRaw.open(zipBytes) ?: error("Not a zip archive")
            try {
                val manifestEntry = file.entries.getValue(MANIFEST_PATH)
                val manifestBytes = file.readContent(manifestEntry)
                val manifest = BundleManifest.parse(manifestBytes.toString(Charsets.UTF_8))

                val files = HashMap<String, ByteArray>()
                for ((name, entry) in file.entries) {
                    if (name.endsWith("/")) continue
                    if (name == MANIFEST_PATH) continue
                    files[name] = file.readContent(entry)
                }
                return Bundle(manifest, files)
            } finally {
                file.close()
            }
        }
    }
}