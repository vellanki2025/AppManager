package com.lucid.appmanager.ui

import android.os.Parcel
import android.os.Parcelable

/**
 * One catalogue entry exposed over AIDL.
 *
 * The client-facing view of [com.lucid.appmanager.catalogue.Artifact]; the artifact itself stays internal so
 * the cloud contract does not leak across the binder. `versionCode` is the comparison key —
 * `versionName` is a display string and is never compared.
 */
data class AppInfo(
    val packageName: String,
    val title: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    /** What the device has now, or -1 when the package is not installed. */
    val installedVersionCode: Long,
) : Parcelable {
    val updateAvailable: Boolean get() = installedVersionCode in 0 until versionCode
    val installed: Boolean get() = installedVersionCode >= 0

    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(packageName); dest.writeString(title); dest.writeString(versionName)
        dest.writeLong(versionCode); dest.writeLong(sizeBytes); dest.writeLong(installedVersionCode)
    }

    companion object CREATOR : Parcelable.Creator<AppInfo> {
        override fun createFromParcel(p: Parcel) =
            AppInfo(p.readString()!!, p.readString()!!, p.readString()!!, p.readLong(), p.readLong(), p.readLong())

        override fun newArray(size: Int) = arrayOfNulls<AppInfo>(size)
    }
}
