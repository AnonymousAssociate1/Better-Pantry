package com.anonymousassociate.betterpantry.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class NotificationSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout needed, just launch the dialog
        val dialog = NotificationSettingsDialog(this)
        dialog.setOnDismissListener {
            finish()
        }
        dialog.show()
    }
}
