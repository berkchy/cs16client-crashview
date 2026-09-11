package com.pickle.patcher.lib

import java.io.File

/**
 * End-to-end patch: analyze source -> repack (inject + align) -> sign (V1/V2) -> verify.
 * Produces a structured report the UI can animate and the user can read.
 */
object ApkPatcher {

    enum class Step { ANALYZE, INJECT, ALIGN, SIGN, VERIFY }

    data class PatchRequest(
        val sourceApk: File,
        val outputApk: File,
        val bundle: Bundle,
        val keystore: SigningKeystore,
        val exclude: ExcludeRule = ExcludeRule.DEFAULT,
        val minSdk: Int = 21,
        val keepAbi: String? = null,
    )

    data class PatchReport(
        val sourceName: String,
        val sourceSizeBytes: Long,
        val sourceEntries: Int,
        val removedEntries: List<String>,
        val addedEntries: List<String>,
        val keptCount: Int,
        val alignedStored: Int,
        val padBytes: Long,
        val unsignedSizeBytes: Long,
        val signedSizeBytes: Long,
        val resolutionSeconds: Double,
        val arscStored: Boolean,
        val arscAligned: Boolean,
        val moduleLibs: List<String>,
        val patchedLibs: List<String>,
        val verification: ApkSignerTool.Verification?,
        val success: Boolean,
    ) {
        val sourceSizeMb: Double get() = sourceSizeBytes / 1048576.0
        val outputSizeMb: Double get() = signedSizeBytes / 1048576.0
    }

    fun patch(
        request: PatchRequest,
        onStep: (Step, Float) -> Unit = { _, _ -> },
    ): PatchReport {
        val t0 = System.nanoTime()

        onStep(Step.ANALYZE, 0.02f)
        val src = ZipRaw.open(request.sourceApk)
            ?: throw IllegalArgumentException("Source APK could not be parsed: ${request.sourceApk}")
        val sourceEntries = src.entries.size
        src.close()
        val arscBefore = ZipRaw.open(request.sourceApk)?.entries?.get("resources.arsc")

        onStep(Step.INJECT, 0.1f)
        var progressLast = 0L
        val repack = ZipRepacker.repack(
            source = request.sourceApk,
            output = request.outputApk,
            bundle = request.bundle,
            exclude = request.exclude,
            pruneAbiExcept = request.keepAbi,
            progress = { done, total ->
                val p = 0.1f + 0.5f * (done.toFloat() / total.toFloat())
                onStep(Step.INJECT, p.coerceIn(0.1f, 0.6f))
                progressLast = done
            },
        )

        onStep(Step.ALIGN, 0.62f)
        val arsc = ZipRaw.open(request.outputApk)?.entries?.get("resources.arsc")
        val arscStored = arsc != null && arsc.method == 0
        val arscAligned = arsc != null && (arsc.dataOffset % 4) == 0L
        val abi = request.keepAbi ?: "arm64-v8a"
        val abiPrefix = "lib/$abi/lib"
        val modSuffix = if (abi == "armeabi-v7a") "_amxx_arm.so" else "_amxx_amd64.so"
        val modules = repack.added.filter { it.startsWith(abiPrefix) && it.endsWith(modSuffix) }
            .plus(request.bundle.manifest.entries.map { it.target }.filter { it.startsWith(abiPrefix) && it.endsWith(modSuffix) })
            .distinct()

        // sanity: align ALL stored entries of output
        val out = ZipRaw.open(request.outputApk)
        val misaligned = out?.entries?.values?.filter { it.method == 0 && (it.dataOffset % 4) != 0L }?.map { it.name } ?: emptyList()
        if (misaligned.isNotEmpty()) {
            throw IllegalArgumentException("Output has misaligned stored entries: $misaligned")
        }
        out?.close()

        onStep(Step.SIGN, 0.68f)
        val tmpSigned = File("${request.outputApk.path}.tmp")
        val outcome = ApkSignerTool.sign(request.outputApk, tmpSigned, request.keystore, request.minSdk)
        if (request.outputApk.exists()) request.outputApk.delete()
        if (!tmpSigned.renameTo(request.outputApk)) {
            tmpSigned.copyTo(request.outputApk, overwrite = true)
            tmpSigned.delete()
        }

        onStep(Step.VERIFY, 0.9f)
        val seconds = (System.nanoTime() - t0) / 1_000_000_000.0
        onStep(Step.VERIFY, 1.0f)

        return PatchReport(
            sourceName = request.sourceApk.name,
            sourceSizeBytes = request.sourceApk.length(),
            sourceEntries = sourceEntries,
            removedEntries = repack.removed,
            addedEntries = repack.added,
            keptCount = repack.kept.size,
            alignedStored = repack.alignedStored,
            padBytes = repack.padBytes,
            unsignedSizeBytes = repack.bytesWritten,
            signedSizeBytes = request.outputApk.length(),
            resolutionSeconds = seconds,
            arscStored = arscStored,
            arscAligned = arscAligned,
            moduleLibs = modules.sorted(),
            patchedLibs = repack.patchedLibs,
            verification = ApkSignerTool.Verification(
                verified = outcome.verified,
                signerFingerprintSha256 = outcome.signerFingerprintSha256,
                signerFingerprintSha1 = "",
                usedV1 = outcome.usedV1,
                usedV2 = outcome.usedV2,
                errors = outcome.errors,
            ),
            success = outcome.verified && arscStored && arscAligned,
        )
    }
}