package com.riad.bio_marker.core

/**
 * Static mapping of well-known package names to a coarse activity category.
 *
 * Used by [com.riad.bio_marker.collectors.UsageStatsCollector] when emitting
 * per-app foreground sessions as [DailyEvent]s, so the narrator can say
 * "watched video for 2 h" instead of "spent 2 h on com.google.android.youtube".
 *
 * This is intentionally a small curated list — anything not listed falls
 * through to [Category.OTHER]. We never inspect content, only package id.
 */
object AppCategoryMap {

    enum class Category {
        SOCIAL,         // Facebook / Instagram / Twitter / TikTok
        MESSAGING,      // WhatsApp / Messenger / Telegram / Discord
        VIDEO,          // YouTube / Netflix / Prime
        GAME,
        PRODUCTIVITY,   // Docs / Sheets / Slack / Email
        BROWSER,
        OTHER,
    }

    private val MAP: Map<String, Category> = mapOf(
        // Social
        "com.facebook.katana" to Category.SOCIAL,
        "com.instagram.android" to Category.SOCIAL,
        "com.twitter.android" to Category.SOCIAL,
        "com.zhiliaoapp.musically" to Category.SOCIAL,   // TikTok
        "com.snapchat.android" to Category.SOCIAL,
        "com.linkedin.android" to Category.SOCIAL,
        "com.reddit.frontpage" to Category.SOCIAL,
        "com.pinterest" to Category.SOCIAL,
        // Messaging
        "com.whatsapp" to Category.MESSAGING,
        "com.facebook.orca" to Category.MESSAGING,       // Messenger
        "org.telegram.messenger" to Category.MESSAGING,
        "com.discord" to Category.MESSAGING,
        "com.viber.voip" to Category.MESSAGING,
        "com.skype.raider" to Category.MESSAGING,
        "com.imo.android.imoim" to Category.MESSAGING,
        "com.google.android.apps.messaging" to Category.MESSAGING,
        // Video
        "com.google.android.youtube" to Category.VIDEO,
        "com.netflix.mediaclient" to Category.VIDEO,
        "com.amazon.avod.thirdpartyclient" to Category.VIDEO,
        "com.disney.disneyplus" to Category.VIDEO,
        "com.spotify.music" to Category.VIDEO,
        // Browsers
        "com.android.chrome" to Category.BROWSER,
        "org.mozilla.firefox" to Category.BROWSER,
        // Productivity
        "com.google.android.gm" to Category.PRODUCTIVITY,
        "com.microsoft.office.outlook" to Category.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.docs" to Category.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.sheets" to Category.PRODUCTIVITY,
        "com.Slack" to Category.PRODUCTIVITY,
        "com.microsoft.teams" to Category.PRODUCTIVITY,
    )

    fun categoryOf(packageName: String): Category =
        MAP[packageName] ?: when {
            packageName.contains("game", ignoreCase = true) -> Category.GAME
            else -> Category.OTHER
        }

    /** Friendly app name shown in the timeline. Falls back to the last segment of the pkg id. */
    fun displayName(packageName: String): String = when (packageName) {
        "com.facebook.katana" -> "Facebook"
        "com.facebook.orca" -> "Messenger"
        "com.whatsapp" -> "WhatsApp"
        "com.instagram.android" -> "Instagram"
        "com.google.android.youtube" -> "YouTube"
        "com.netflix.mediaclient" -> "Netflix"
        "org.telegram.messenger" -> "Telegram"
        "com.twitter.android" -> "Twitter / X"
        "com.zhiliaoapp.musically" -> "TikTok"
        "com.snapchat.android" -> "Snapchat"
        "com.linkedin.android" -> "LinkedIn"
        "com.spotify.music" -> "Spotify"
        "com.google.android.gm" -> "Gmail"
        "com.android.chrome" -> "Chrome"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.titlecase() }
    }
}

