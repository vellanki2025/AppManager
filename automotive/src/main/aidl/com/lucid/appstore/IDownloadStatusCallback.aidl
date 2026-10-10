package com.lucid.appstore;

import com.lucid.appstore.DownloadStatus;

oneway interface IDownloadStatusCallback {
    void onStatusChanged(in DownloadStatus status);
}
