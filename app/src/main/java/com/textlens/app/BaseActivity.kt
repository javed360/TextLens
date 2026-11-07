package com.textlens.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Base for every screen: applies the in-app language and refreshes itself if the language changes. */
open class BaseActivity : AppCompatActivity() {

    private var langAtCreate = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.localized(newBase))
    }

    /**
     * AppCompat re-applies its own configuration (night mode); without this it can drop the
     * language we set in attachBaseContext. Keep everything from our context except uiMode.
     */
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        val mine = baseContext?.resources?.configuration
        if (overrideConfiguration != null && mine != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(mine)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        langAtCreate = LocaleHelper.current(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // The language was changed on another screen while this one waited in the back stack.
        if (LocaleHelper.current(this) != langAtCreate) recreate()
    }
}
