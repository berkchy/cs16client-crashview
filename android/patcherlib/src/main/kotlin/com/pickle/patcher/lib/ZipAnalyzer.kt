package com.pickle.patcher.lib

import java.io.File

/** Reads structural facts about a source APK (UI + validation). */
object ZipAnalyzer {

    data class ArchiveInfo(
        val name: String,
        val sizeBytes: Long,
        val entryCount: Int,
        val hasResourcesArsc: Boolean,
        val archAbi: String?,
        val abis: List<String>,
        val libCount: Int,
        val misalignedStored: List<String>,
    )

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    fun analyze(file: File): ArchiveInfo {
        val zip = ZipRaw.open(file)
            ?: return ArchiveInfo(file.name, file.length(), 0, false, null, emptyList(), 0, emptyList())
        try {
            val arsc = zip.entries["resources.arsc"]
            val libs = zip.entries.keys.filter { it.startsWith("lib/") && it.endsWith(".so") }
            // Which known ABIs has lib dirs for (in priority order).
            val abis = KNOWN_ABIS.filter { abi -> libs.any { it.startsWith("lib/$abi/") } }
            val abi = abis.firstOrNull()
            val misaligned = zip.entries.values
                .filter { it.method == 0 && (it.dataOffset % 4) != 0L }
                .map { it.name }
            return ArchiveInfo(
                name = file.name,
                sizeBytes = file.length(),
                entryCount = zip.entries.size,
                hasResourcesArsc = arsc != null,
                archAbi = abi,
                abis = abis,
                libCount = libs.size,
                misalignedStored = misaligned,
            )
        } finally {
            zip.close()
        }
    }

    /** Queries whether the output contains the given path with the given method. */
    fun entryState(file: File, path: String): Triple<Boolean, Int, Long> {
        val zip = ZipRaw.open(file) ?: return Triple(false, -1, -1)
        try {
            val e = zip.entries[path] ?: return Triple(false, -1, -1)
            return Triple(true, e.method, e.dataOffset)
        } finally {
            zip.close()
        }
    }
}