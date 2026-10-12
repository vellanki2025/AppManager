package com.lucid.appstore.ui

import android.os.Parcel
import android.os.Parcelable

/** One catalogue entry exposed over AIDL. */
data class AppInfo(
    val packageName: String,
    val title: String,
    val versionName: String,
    val sizeBytes: Long,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(packageName); dest.writeString(title); dest.writeString(versionName); dest.writeLong(sizeBytes)
    }

    companion object CREATOR : Parcelable.Creator<AppInfo> {
        override fun createFromParcel(p: Parcel) = AppInfo(p.readString()!!, p.readString()!!, p.readString()!!, p.readLong())
        override fun newArray(size: Int) = arrayOfNulls<AppInfo>(size)
    }
}
