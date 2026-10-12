package com.lucid.appstore

import android.os.Parcel
import android.os.Parcelable
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.NetworkMode
import java.time.LocalTime

/** AIDL view of [Config]. `scheduleTime` is `HH:mm`, `networkMode` is the enum name. */
data class DownloadConfig(
    val scheduleTime: String,
    val networkMode: String,
    val batteryThresholdPercent: Int,
    val batteryResumeMarginPercent: Int,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(d: Parcel, flags: Int) {
        d.writeString(scheduleTime); d.writeString(networkMode); d.writeInt(batteryThresholdPercent); d.writeInt(batteryResumeMarginPercent)
    }

    /** Applies the editable fields onto an existing [Config], keeping the durations it does not carry. */
    fun applyTo(c: Config): Config = c.copy(
        scheduleTime = LocalTime.parse(scheduleTime),
        networkMode = NetworkMode.valueOf(networkMode),
        batteryThresholdPercent = batteryThresholdPercent,
        batteryResumeMarginPercent = batteryResumeMarginPercent,
    )

    companion object CREATOR : Parcelable.Creator<DownloadConfig> {
        fun from(c: Config) = DownloadConfig(
            c.scheduleTime.toString(), c.networkMode.name,
            c.batteryThresholdPercent, c.batteryResumeMarginPercent,
        )
        override fun createFromParcel(p: Parcel) =
            DownloadConfig(p.readString()!!, p.readString()!!, p.readInt(), p.readInt())
        override fun newArray(size: Int) = arrayOfNulls<DownloadConfig>(size)
    }
}
