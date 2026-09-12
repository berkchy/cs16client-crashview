package com.pickle.patcher.lib

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Byte-preserving APK repacker.
 *
 * Copies every surviving entry from the source APK verbatim (stored entries as raw bytes,
 * deflated entries as their exact compressed stream), prunes entries matched by
 * [ExcludeRule], injects [Bundle] payload entries, and aligns every stored entry
 * (mirrors zipalign -f 4, with native libs aligned to 16 KB for
 * Android 15+ / 16 KB-page devices).
 *
 * Output is an unsigned but fully valid, aligned APK ready for apksig signing.
 */
object ZipRepacker {

    /**
     * ReGameDLL (`libcs`) guard for arm64: upstream ReGameDLL bug — weapon-item slot pointers
     * (`m_rgpPlayerItems` / `m_pActiveItem`, read from player+0x628 / +0x670..) hold tiny garbage
     * values (e.g. `0x1`, `0x5`, `0x9`) instead of NULL, so naked `cbz` (null-only) checks pass and
     * the code derefs `garbage + small_offset` → SIGSEGV (fault addr `0x5`/`0x9`). Each dangerous
     * `cbz xRt, T` is patched to `tbz xRt, #32, T` (same target): real arm64 object pointers are
     * always ≥ 4 GiB (bit 32 set), so any sub-4 GiB value is treated as no-entity and the whole
     * deref block is skipped. The one-byte opcode change `0xB4 -> 0xB6` yields an identical-target
     * `tbz xRt, #32` for these `cbz` encodings. 21 patch sites total:
     * 6 × PostThink slot-entry + 1 × PostThink m_pActiveItem + 6 × UpdateClientData slot-entry
     * + 1 × UpdateClientData m_pActiveItem + 7 × inlined drop-current-weapon (0x628-slot) blocks
     * in RemoveAllItems / Killed / DetachTank / Disappear / StartObserver / PlayerUse /
     * ItemPostFrame.
     * Single byte per site: opcode byte 0xB4 -> 0xB6.
     */
    val LIBCS_ENTRY = "lib/arm64-v8a/libcs_android_arm64.so"
    val DEX_ENTRY = "classes.dex"

    /** Byte pattern to find: "-dll @yapb" embedded in the full argv string. */
    private val DEX_OLD = "-dll @yapb".toByteArray(Charsets.UTF_8)
    /** Replacement: same length (10 bytes). "-dll @mm" + 3 null bytes padding. */
    private val DEX_NEW = byteArrayOf(
        0x2d, 0x64, 0x6c, 0x6c, 0x20, 0x40, 0x6d, 0x6d, 0x00, 0x00
    )

    /** (instruction file-offset, expected register bits) for every `cbz` that guards a weapon pointer. */
    private val LIBCS_PATCHES = listOf(
        0x240be4 to 20,   // PostThink slot 0 (x20)
        0x240c74 to 20,   // slot 1
        0x240d04 to 20,   // slot 2
        0x240d94 to 20,   // slot 3
        0x240e24 to 20,   // slot 4
        0x240eb4 to 20,   // slot 5
        0x240fc8 to 8,    // PostThink m_pActiveItem (x8)
        0x247bf8 to 0,    // UpdateClientData slot 0 (x0)
        0x247c10 to 0,    // slot 1
        0x247c28 to 0,    // slot 2
        0x247c40 to 0,    // slot 3
        0x247c58 to 0,    // slot 4
        0x247c70 to 0,    // slot 5
        0x247d20 to 0,    // UpdateClientData m_pActiveItem (x0)
        0x236a84 to 8,    // RemoveAllItems slot0 (0x628 ptr, x8) — fault addr 0x5
        0x238500 to 8,    // Killed slot0 (0x628 ptr, x8) — fault addr 0x5
        0x236eb0 to 8,    // DetachTank slot0 (x8)
        0x23c240 to 8,    // Disappear slot0 (x8)
        0x23d2d0 to 8,    // StartObserver slot0 (x8)
        0x23d75c to 9,    // PlayerUse slot0 (x9)
        0x241354 to 8,    // ItemPostFrame slot0 (x8)
    )

    data class Result(
        val output: File,
        val kept: List<String>,
        val removed: List<String>,
        val added: List<String>,
        val entriesTotal: Int,
        val alignedStored: Int,
        val padBytes: Long,
        var bytesWritten: Long = 0,
        var patchedLibs: List<String> = emptyList(),
    )

    fun repack(
        source: File,
        output: File,
        bundle: Bundle,
        exclude: ExcludeRule = ExcludeRule.DEFAULT,
        pruneAbiExcept: String? = null,
        progress: ((Long, Long) -> Unit)? = null,
    ): Result {
        val src = ZipRaw.open(source) ?: throw IOException("Source APK could not be parsed")
        val srcLen = source.length()

        try {
            // ---- decide keep/remove/add ----
            val removed = ArrayList<String>()
            val keptEntries = ArrayList<ZipRaw.ZipEntryInfo>()
            for ((name, entry) in src.entries) {
                val excluded = excludable(name, exclude) || prunedByAbi(name, pruneAbiExcept)
                if (excluded) removed.add(name) else keptEntries.add(entry)
            }

            // bundle targets shadow existing kept entries
            val bundleTargets = bundle.manifest.entries.map { it.target }.toHashSet()
            val iterator = keptEntries.iterator()
            while (iterator.hasNext()) {
                val e = iterator.next()
                if (e.name in bundleTargets) {
                    removed.add(e.name)
                    iterator.remove()
                }
            }

            val raf = RandomAccessFile(output, "rw")
            val cdEntries = ArrayList<CdRecord>()
            var alignPadBytes = 0L
            var alignedStored = 0
            var bytesWritten = 0L

            try {
                raf.setLength(0)

                fun writeLocalHeader(
                    name: String,
                    method: Int,
                    compressedSize: Long,
                    uncompressedSize: Long,
                    crc: Long,
                    data: ByteArray,
                ) {
                    var localOffset = raf.filePointer
                    var extraLen = 0
                    if (method == 0) {
                        // zipalign behaviour: native libs (lib/**) must be 16 KB
                        // aligned for Android 15+ / 16 KB-page devices; everything
                        // else just needs 4-byte alignment.
                        val unit = if (name.startsWith("lib/")) 16384 else 4
                        val base = localOffset + 30 + name.length
                        val rem = (base % unit).toInt()
                        extraLen = if (rem == 0) 0 else (unit - rem)
                        alignPadBytes += extraLen
                        alignedStored++
                    }

                    val hdr = ByteArray(30)
                    val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x04034b50)
                    b.putShort(20)                                   // version needed
                    b.putShort(0)                                    // flags (no data descriptor)
                    b.putShort(method.toShort())
                    b.putShort(0)                                    // mod time
                    b.putShort(0x0821)                               // mod date (2026-08-29)
                    b.putInt(crc.toInt())
                    b.putInt(compressedSize.toInt())
                    b.putInt(uncompressedSize.toInt())
                    b.putShort(name.length.toShort())
                    b.putShort(extraLen.toShort())
                    raf.write(hdr)
                    raf.write(name.toByteArray(Charsets.UTF_8))
                    if (extraLen > 0) raf.write(ByteArray(extraLen))

                    val dataOffset = raf.filePointer
                    raf.write(data)

                    cdEntries.add(
                        CdRecord(
                            name = name,
                            method = method,
                            compressedSize = compressedSize,
                            uncompressedSize = uncompressedSize,
                            crc = crc,
                            localOffset = localOffset,
                            dataOffset = dataOffset,
                        )
                    )
                    bytesWritten += 30 + name.length + extraLen + data.size
                }

                // copy preserved entries (raw bytes, method as-is)
                val total = keptEntries.size + bundle.manifest.entries.size
                var done = 0
                val patchedLibs = ArrayList<String>()
                for (entry in keptEntries) {
                    if (entry.name == LIBCS_ENTRY) {
                        val content = src.readContent(entry)
                        val patched = patchLibCs(content)
                        if (patched != null) {
                            val out = java.io.ByteArrayOutputStream()
                            val def = Deflater(9, true)
                            def.setInput(patched)
                            def.finish()
                            val chunk = ByteArray(8192)
                            while (!def.finished()) {
                                val n = def.deflate(chunk)
                                out.write(chunk, 0, n)
                            }
                            def.end()
                            val compressed = out.toByteArray()
                            writeLocalHeader(
                                name = entry.name,
                                method = 8,
                                compressedSize = compressed.size.toLong(),
                                uncompressedSize = patched.size.toLong(),
                                crc = crc32(patched),
                                data = compressed,
                            )
                            patchedLibs.add(entry.name)
                            done++
                            progress?.invoke(bytesWritten, srcLen)
                            continue
                        }
                    }

                    // Patch classes.dex: -dll @yapb -> -dll @mm
                    if (entry.name == DEX_ENTRY) {
                        val content = src.readContent(entry)
                        val patched = patchDex(content)
                        if (patched != null) {
                            val out = java.io.ByteArrayOutputStream()
                            val def = Deflater(9, true)
                            def.setInput(patched)
                            def.finish()
                            val chunk = ByteArray(8192)
                            while (!def.finished()) {
                                val n = def.deflate(chunk)
                                out.write(chunk, 0, n)
                            }
                            def.end()
                            val compressed = out.toByteArray()
                            writeLocalHeader(
                                name = entry.name,
                                method = 8,
                                compressedSize = compressed.size.toLong(),
                                uncompressedSize = patched.size.toLong(),
                                crc = crc32(patched),
                                data = compressed,
                            )
                            patchedLibs.add(entry.name)
                            done++
                            progress?.invoke(bytesWritten, srcLen)
                            continue
                        }
                    }

                    val raw = ByteArray(entry.compressedSize.toInt())
                    src.file.seek(entry.dataOffset)
                    src.file.readFully(raw)

                    writeLocalHeader(
                        name = entry.name,
                        method = entry.method,
                        compressedSize = entry.compressedSize,
                        uncompressedSize = entry.uncompressedSize,
                        crc = entry.crc32,
                        data = raw,
                    )
                    done++
                    progress?.invoke(bytesWritten, srcLen)
                }

                // add bundle entries
                val added = ArrayList<String>()
                for (be in bundle.manifest.entries) {
                    val content = bundle.resolveEntry(be) ?: continue
                    val stored = be.method == BundleManifest.Compression.STORED
                    added.add(be.target)

                    if (stored) {
                        val crc = crc32(content)
                        writeLocalHeader(be.target, 0, content.size.toLong(), content.size.toLong(), crc, content)
                    } else {
                        val out = java.io.ByteArrayOutputStream()
                        val def = Deflater(9, true)
                        val inp = java.io.ByteArrayInputStream(content)
                        val buf = ByteArray(8192)
                        def.setInput(content)
                        def.finish()
                        val chunk = ByteArray(8192)
                        while (!def.finished()) {
                            val n = def.deflate(chunk)
                            out.write(chunk, 0, n)
                        }
                        def.end()
                        val compressed = out.toByteArray()
                        writeLocalHeader(be.target, 8, compressed.size.toLong(), content.size.toLong(), crc32(content), compressed)
                    }
                    done++
                    progress?.invoke(bytesWritten, srcLen)
                }

                // ---- central directory ----
                val cdOffset = raf.filePointer
                run {
                    val cb = java.io.ByteArrayOutputStream()
                    for (rec in cdEntries) {
                        val b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                        b.putInt(0x02014b50)
                        b.putShort(20)                     // version made by
                        b.putShort(20)                     // version needed
                        b.putShort(0)                      // flags
                        b.putShort(rec.method.toShort())
                        b.putShort(0)
                        b.putShort(0x0821)
                        b.putInt(rec.crc.toInt())
                        b.putInt(rec.compressedSize.toInt())
                        b.putInt(rec.uncompressedSize.toInt())
                        b.putShort(rec.name.length.toShort())
                        b.putShort(0)                      // extra len
                        b.putShort(0)                      // comment len
                        b.putShort(0)                      // disk
                        b.putShort(0)                      // internal attrs
                        b.putInt(0)                        // external attrs
                        b.putInt(rec.localOffset.toInt())
                        cb.write(b.array())
                        cb.write(rec.name.toByteArray(Charsets.UTF_8))
                    }
                    raf.write(cb.toByteArray())
                }

                // ---- EOCD ----
                val cdSize = raf.filePointer - cdOffset
                run {
                    val b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x06054b50)
                    b.putShort(0)
                    b.putShort(0)
                    b.putShort(cdEntries.size.toShort())
                    b.putShort(cdEntries.size.toShort())
                    b.putInt(cdSize.toInt())
                    b.putInt(cdOffset.toInt())
                    b.putShort(0)
                    raf.write(b.array())
                }

                raf.fd.sync()

                return Result(
                    output = output,
                    kept = keptEntries.map { it.name },
                    removed = removed,
                    added = added,
                    entriesTotal = cdEntries.size,
                    alignedStored = alignedStored,
                    padBytes = alignPadBytes,
                    bytesWritten = bytesWritten,
                    patchedLibs = patchedLibs,
                )
            } finally {
                raf.close()
            }
        } finally {
            src.close()
        }
    }

    /**
     * Apply the ReGameDLL weapon-slot guard to [content] (the decompressed libcs bytes).
     * Returns the patched bytes, or null when at least one expected instruction is absent
     * (different build / already fixed) so we leave it entirely untouched.
     */
    private fun patchLibCs(content: ByteArray): ByteArray? {
        val out = content.copyOf()
        for ((off, rt) in LIBCS_PATCHES) {
            if (out.size < off + 4) return null
            // verify cbz encoding: opcode high nibble 0xB4, register bits match
            val high = out[off + 3].toInt() and 0xFF
            val reg = out[off].toInt() and 0x1F
            if (high != 0xB4 || reg != rt) return null
            out[off + 3] = 0xB6.toByte()   // cbz -> tbz #32
        }
        return out
    }

    /**
     * Patch classes.dex: replace "-dll @yapb" with "-dll @mm" (same byte length).
     * The Xash3D engine resolves @mm to lib/<abi>/libmm_android_<arch>.so, so the
     * bundle ships metamod under that name instead of the old libyapb_android_*.so alias.
     * Returns patched bytes or null if pattern not found (already patched / different build).
     */
    private fun patchDex(content: ByteArray): ByteArray? {
        val idx = findByteArray(content, DEX_OLD)
        if (idx < 0) return null
        val out = content.copyOf()
        for (i in DEX_NEW.indices) out[idx + i] = DEX_NEW[i]
        return out
    }

    private fun findByteArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        val end = haystack.size - needle.size
        for (i in 0..end) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    private fun excludable(name: String, exclude: ExcludeRule): Boolean {
        // do not prune the signature block / meta unless explicitly requested; default prune keeps
        // the old amxx libs out but must NOT delete the signing-dir of the ORIGINAL apk blindly.
        for (p in exclude.exact) if (name == p) return true
        // exact rules for prefixes
        for (p in exclude.prefixes) {
            if (name.startsWith(p)) return true
        }
        return false
    }

    /**
     * ABIs we do not ship are dropped entirely so the patched APK only contains the
     * native libs for the target ABI (e.g. "arm64-v8a"). This keeps Xash from loading
     * a wrong-ABI engine/gamedll lib that would otherwise shadow the injected metamod.
     */
    private fun prunedByAbi(name: String, keepAbi: String?): Boolean {
        if (keepAbi == null) return false
        if (!name.startsWith("lib/")) return false
        // under lib/<abi>/ if the abi matches, keep; every other lib entry (other abi
        // dirs, or a stray lib at the root) is pruned.
        if (name.startsWith("lib/$keepAbi/")) return false
        return true
    }

    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private data class CdRecord(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Long,
        val localOffset: Long,
        val dataOffset: Long,
    )
}