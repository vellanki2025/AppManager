package com.lucid.auto.appstore.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.local.PackageManagerInspector
import com.lucid.auto.appstore.domain.ManagedApps
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.Application

/**
 * Proves the "how do we get the local app version" mechanism against a real (shadowed)
 * PackageManager: versionName for display, longVersionCode for comparison, and
 * NameNotFoundException meaning "not installed" rather than an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageManagerInspectorTest {

    private lateinit var inspector: PackageManagerInspector
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        inspector = PackageManagerInspector(context)
    }

    private fun install(packageName: String, versionName: String, versionCode: Long, label: String) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            this.nonLocalizedLabel = label
        }
        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            this.longVersionCode = versionCode
            this.applicationInfo = appInfo
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `reads versionName and longVersionCode for an installed package`() {
        install("com.lucid.auto.media", "1.1.0", 10100, "Lucid Media")

        val installed = inspector.getInstalled("com.lucid.auto.media")

        assertThat(installed).isNotNull()
        assertThat(installed!!.packageName).isEqualTo("com.lucid.auto.media")
        assertThat(installed.versionName).isEqualTo("1.1.0")
        assertThat(installed.versionCode).isEqualTo(10100L)
    }

    @Test
    fun `a package that is not installed returns null rather than throwing`() {
        assertThat(inspector.getInstalled("com.spotify.music")).isNull()
    }

    @Test
    fun `getIcon returns null for a package that is not installed`() {
        assertThat(inspector.getIcon("com.spotify.music")).isNull()
    }

    @Test
    fun `bulk lookup returns only the packages that are actually installed`() {
        install("com.lucid.auto.media", "1.1.0", 10100, "Lucid Media")
        install("com.lucid.auto.radio", "2.0.0", 20000, "Lucid Radio")

        val found = inspector.getInstalled(ManagedApps.PACKAGE_NAMES)

        assertThat(found.keys)
            .containsExactly("com.lucid.auto.media", "com.lucid.auto.radio")
        assertThat(found.getValue("com.lucid.auto.radio").versionCode).isEqualTo(20000L)
    }

    @Test
    fun `the managed package list matches the three in-scope apps`() {
        assertThat(ManagedApps.PACKAGE_NAMES).containsExactly(
            "com.lucid.auto.media",
            "com.lucid.auto.radio",
            "com.spotify.music",
        ).inOrder()
    }
}
