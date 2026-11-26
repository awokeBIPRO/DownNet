package com.downnet

import android.app.Application
import com.downnet.core.AppContainer
import com.downnet.download.Notifications
import com.downnet.share.SharingShortcuts

class DownNetApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // First-launch publication of the sharing shortcut. The direct-share
        // chip cannot exist before the app has run at least once, so this is
        // the earliest moment it can appear in anyone's share sheet.
        SharingShortcuts.publish(this)
        Notifications.ensureChannels(this)
    }
}
