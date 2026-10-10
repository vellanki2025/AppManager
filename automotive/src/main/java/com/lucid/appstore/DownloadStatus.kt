package com.lucid.appstore

import android.os.Parcel
import android.os.Parcelable

/** Snapshot of the run state for AIDL clients. `phase` and `waitReason` use the core enum names. */
data class DownloadStatus(
    val enabled: Boolean,
    val phase: String,
    val waitReason: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val lastSuccessAt: Long,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (enabled) 1 else 0); dest.writeString(phase); dest.writeString(waitReason)
        dest.writeLong(downloadedBytes); dest.writeLong(totalBytes); dest.writeLong(lastSuccessAt)
    }

    companion object CREATOR : Parcelable.Creator<DownloadStatus> {
        override fun createFromParcel(p: Parcel) =
            DownloadStatus(p.readInt() == 1, p.readString()!!, p.readString(), p.readLong(), p.readLong(), p.readLong())
        override fun newArray(size: Int) = arrayOfNulls<DownloadStatus>(size)
    }
}
