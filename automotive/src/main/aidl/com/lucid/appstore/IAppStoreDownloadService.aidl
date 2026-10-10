package com.lucid.appstore;

import com.lucid.appstore.AppInfo;
import com.lucid.appstore.DownloadStatus;
import com.lucid.appstore.IDownloadStatusCallback;

/** Bound-service API for store clients. Requires com.lucid.appstore.permission.BIND_DOWNLOAD_SERVICE. */
interface IAppStoreDownloadService {
    /** Apps the catalogue currently offers. */
    List<AppInfo> getAvailableApps();

    /** Current run phase, wait reason, progress and feature-flag state. */
    DownloadStatus getStatus();

    /** Start the gated download now. packageName is recorded for the catalogue phase; this phase downloads one bundle. */
    void startDownload(String packageName);

    /** Cancel the current run. The daily schedule stays armed. */
    void cancelDownload();

    void registerCallback(IDownloadStatusCallback callback);
    void unregisterCallback(IDownloadStatusCallback callback);
}
