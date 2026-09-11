package com.pickle.patcher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Minimal GitHub Releases client. Fetches the latest release metadata and downloads
 * the AMXX mod bundle artifact so the patcher can inject freshly CI-built payloads
 * without shipping a compiler.
 */
object ReleaseRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        val tag_name: String = "",
        val name: String = "",
        val body: String = "",
        val published_at: String = "",
        val assets: List<Asset> = emptyList(),
    ) {
        @Serializable
        data class Asset(
            val name: String = "",
            val browser_download_url: String = "",
            val size: Long = 0,
        )

        /**
         * Bundle for the given ABI. arm64-v8a keeps the legacy asset name
         * (amxx-bundle.zip, produced by every release) with
         * amxx-bundle-arm64-v8a.zip as the modern fallback; other ABIs use
         * amxx-bundle-<abi>.zip and are only present when CI built them.
         */
        fun bundleAsset(abi: String = "arm64-v8a"): Asset? {
            val names = if (abi == "arm64-v8a") {
                listOf("amxx-bundle.zip", "amxx-bundle-arm64-v8a.zip")
            } else {
                listOf("amxx-bundle-$abi.zip")
            }
            return assets.firstOrNull { it.name in names && it.name.endsWith(".zip") }
        }

        fun addonsAsset(): Asset? = assets.firstOrNull {
            it.name.startsWith("amxx-addons") && it.name.endsWith(".zip")
        }
    }

    private val webClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolves the latest release tag via the github.com redirect
     * (…/releases/latest -> …/releases/tag/vX). Costs no API quota,
     * unlike /releases/latest on api.github.com (60 req/hour shared).
     * Returns null on any failure (caller backs off).
     */
    suspend fun latestTagRedirect(repo: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://github.com/$repo/releases/latest")
                .header("User-Agent", "cs16-amxx-patcher")
                .head()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val finalUrl = resp.request.url.toString()
                finalUrl.substringAfterLast("/releases/tag/", "").ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun latest(repo: String): Release {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "cs16-amxx-patcher")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw IOException("GitHub ${resp.code}: ${resp.message}")
            json.decodeFromString<Release>(resp.body?.string().orEmpty())
        }
    }

    suspend fun download(
        asset: Release.Asset,
        dest: File,
        onProgress: (Float) -> Unit = {},
    ): File = downloadUrl(asset.browser_download_url, dest, asset.size) { done, total ->
        if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
    }

    suspend fun downloadUrl(
        url: String,
        dest: File,
        knownSize: Long = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "cs16-amxx-patcher")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download ${resp.code}")
            dest.parentFile?.mkdirs()
            val body = resp.body
                ?: throw IOException("Empty response body")
            val total = knownSize.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }
                ?: 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        return dest
    }
}