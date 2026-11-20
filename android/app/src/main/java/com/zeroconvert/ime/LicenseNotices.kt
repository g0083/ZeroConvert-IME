package com.zeroconvert.ime

import android.content.Context
import org.json.JSONObject

/** One row of the shared open-source license list shown in the settings screen. */
internal data class LicenseEntry(
    val id: String,
    val title: String,
    val description: String,
    val license: String
)

/** One full license text bundled with the app, referenced from the shared document. */
internal data class LicenseFullText(
    val id: String,
    val title: String,
    val asset: String
)

/**
 * Reads `assets/licenses/third-party.json`, which is the same file the Windows settings app renders.
 * The canonical copy lives at `licenses/third-party.json` in the repository root and is bundled by
 * the `sharedHostAssets` Gradle task; do not add a second hand-written list here.
 */
internal object LicenseNotices {
    private const val ASSET = "licenses/third-party.json"
    private const val PLATFORM = "android"

    fun entries(context: Context): List<LicenseEntry> = runCatching {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    fun fullTexts(context: Context): List<LicenseFullText> = runCatching {
        parseFullTexts(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    fun readFullText(context: Context, asset: String): String = runCatching {
        context.assets.open(asset).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    /** Parses the bundled full-text list; side-effect free so a JVM test can cover it. */
    fun parseFullTexts(json: String): List<LicenseFullText> {
        val array = JSONObject(json).optJSONArray("fullTexts") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val asset = item.optString("androidAsset").takeIf { it.isNotBlank() } ?: continue
                add(
                    LicenseFullText(
                        id = item.optString("id", index.toString()),
                        title = item.getString("title"),
                        asset = asset
                    )
                )
            }
        }
    }

    /** Parses the shared document; side-effect free so a JVM test can cover it. */
    fun parse(json: String): List<LicenseEntry> {
        val array = JSONObject(json).getJSONArray("entries")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (!appliesToPlatform(item)) continue
                val notes = item.optJSONObject("notes")
                val description = notes?.optString(PLATFORM)?.takeIf { it.isNotBlank() }
                    ?: item.optString("note").takeIf { it.isNotBlank() }
                    ?: item.optString("credit")
                add(
                    LicenseEntry(
                        id = item.optString("id", index.toString()),
                        title = item.getString("title"),
                        description = description,
                        license = item.optString("license")
                    )
                )
            }
        }
    }

    private fun appliesToPlatform(item: JSONObject): Boolean {
        val platforms = item.optJSONArray("platforms") ?: return true
        return (0 until platforms.length()).any { platforms.optString(it) == PLATFORM }
    }
}
