package com.pickle.patcher.data

import android.content.Context
import com.pickle.patcher.lib.Bundle
import java.io.File

/**
 * Supplies the mod bundle:
 *  1. a bundle.zip downloaded from GitHub Releases (preferred, always fresh)
 *  2. a locally cached bundle
 */
class BundleProvider(private val context: Context) {

    /** Where downloaded release bundles are stored. */
    fun cacheDir(): File = File(context.cacheDir, "patcher")

    fun cachedBundleFile(): File = File(cacheDir(), "amxx-bundle.zip")

    fun hasCachedBundle(): Boolean = cachedBundleFile().exists()

    fun loadCachedBundle(): Bundle? = loadFrom(cachedBundleFile())

    fun loadFrom(file: File): Bundle? = try {
        Bundle.fromZip(file.readBytes())
    } catch (t: Throwable) {
        null
    }
}
