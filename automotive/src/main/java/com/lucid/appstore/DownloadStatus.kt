package com.lucid.appstore

import android.os.Parcel
import android.os.Parcelable

/**
 * Snapshot for AIDL clients. `phase`, `waitReason`, `driving` and `networkClass` carry the core enum names;
 * vehicle fields are empty or -1 until the signal sources have reported.
 */
data class DownloadStatus(
    val enabled: Boolean,
    val phase: String,
    val waitReason: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val lastSuccessAt: Long,
    val driving: String,
    val batteryPercent: Int,
    val charging: Boolean,
    val networkClass: String,
    val networkValidated: Boolean,
    /** Cellular option is available only when true. */
    val cellularSubscribed: Boolean,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(d: Parcel, flags: Int) {
        d.writeInt(if (enabled) 1 else 0); d.writeString(phase); d.writeString(waitReason)
        d.writeLong(downloadedBytes); d.writeLong(totalBytes); d.writeLong(lastSuccessAt)
        d.writeString(driving); d.writeInt(batteryPercent); d.writeInt(if (charging) 1 else 0)
        d.writeString(networkClass); d.writeInt(if (networkValidated) 1 else 0); d.writeInt(if (cellularSubscribed) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<DownloadStatus> {
        override fun createFromParcel(p: Parcel) = DownloadStatus(
            p.readInt() == 1, p.readString()!!, p.readString(), p.readLong(), p.readLong(), p.readLong(),
            p.readString()!!, p.readInt(), p.readInt() == 1, p.readString()!!, p.readInt() == 1, p.readInt() == 1,
        )
        override fun newArray(size: Int) = arrayOfNulls<DownloadStatus>(size)
    }
}
