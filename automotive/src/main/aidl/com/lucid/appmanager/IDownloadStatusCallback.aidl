package com.lucid.appmanager;

import com.lucid.appmanager.DownloadStatus;

oneway interface IDownloadStatusCallback {
    /** Run phase, progress, vehicle signals or feature flag changed. */
    void onStatusChanged(in DownloadStatus status);

    /** One formatted log line per service event, same format as logcat. */
    void onEvent(String line);
}
