package com.lucid.samples

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** A one-screen placeholder app whose only job is to have a package name and a versionCode. */
class SampleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = packageManager.getPackageInfo(packageName, 0)
        setContentView(
            TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 20f
                text = buildString {
                    appendLine(getString(R.string.sample_label))
                    appendLine(packageName)
                    append("version ${info.versionName}")
                }
            },
        )
    }
}
