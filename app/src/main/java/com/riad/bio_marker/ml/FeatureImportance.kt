package com.riad.bio_marker.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads `assets/feature_importance.json` — a flat `{feature_name: float}` map
 * produced by `model_training/export_feature_importance.py`. The values are
 * already normalised so the strongest contributor is `1.0`.
 *
 * Until this class existed, the JSON file was bundled but never read. Now the
 * Clinician dashboard can show "Model importance (top 3): …" so the user can
 * see *why* the model weighs each signal the way it does.
 *
 * Failure modes are non-fatal — if the asset is missing or malformed, the
 * loader returns an empty map and the UI hides the section gracefully.
 */
object FeatureImportance {

    private const val TAG = "FeatureImportance"
    private const val ASSET_NAME = "feature_importance.json"

    /** Cached so we don't re-parse on every Clinician-tab redraw. */
    @Volatile private var cache: Map<String, Float>? = null

    fun load(context: Context): Map<String, Float> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: parse(context).also { cache = it }
        }
    }

    /** Top [n] features sorted by descending normalised importance. */
    fun topN(context: Context, n: Int = 3): List<Pair<String, Float>> =
        load(context).entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }

    private fun parse(context: Context): Map<String, Float> {
        return try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val map = LinkedHashMap<String, Float>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optDouble(k, 0.0).toFloat()
            }
            Log.i(TAG, "Loaded $ASSET_NAME — ${map.size} features")
            map
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load $ASSET_NAME — UI will hide importance section", t)
            emptyMap()
        }
    }

    /** For tests / dev tooling: clear the in-memory cache. */
    fun reset() { cache = null }
}

