package com.downnet.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.downnet.R

/**
 * Publishes the "Save media" sharing shortcut. The share-target declaration
 * lives in res/xml/shortcuts.xml; the dynamic shortcut pushed here carries
 * the matching category, which is what earns DownNet a chip in the
 * direct-share row of the share sheet.
 *
 * Honest ranking: the chip cannot exist before the app's first launch, the
 * system controls ordering, and pinning the chip is the only guaranteed slot.
 * [reportUsage] after every successful share-driven download is the lever we
 * do control.
 */
object SharingShortcuts {

    const val CATEGORY = "com.downnet.category.DOWNLOAD"
    private const val ID = "quick_save"

    fun publish(context: Context) {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(build(context)))
        }
    }

    /** Pushes the shortcut again — this is the usage signal the ranker reads. */
    fun reportUsage(context: Context) {
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, build(context))
        }
    }

    private fun build(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.shortcut_quick_save))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_save))
            .setIntent(
                Intent(context, ShareReceiverActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                }
            )
            .build()
}
