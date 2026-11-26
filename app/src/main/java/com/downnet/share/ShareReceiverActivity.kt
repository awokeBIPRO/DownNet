package com.downnet.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.downnet.MainActivity
import com.downnet.R
import com.downnet.core.urlnorm.UrlNormalizer

/**
 * Share-sheet trampoline. Receives ACTION_SEND (text/plain) — including the
 * direct-share chip delivery — pulls the URL out of the shared text, hands it
 * to MainActivity, and vanishes. Translucent theme + noHistory so it never
 * flashes or lingers in recents.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = text?.let(UrlNormalizer::firstUrl)
            if (url != null) {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_INCOMING_URL, url)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } else {
                Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            }
        }
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
