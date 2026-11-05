package com.lucid.appmanager;

import com.lucid.appmanager.ui.AppInfo;
import com.lucid.appmanager.DownloadConfig;
import com.lucid.appmanager.DownloadStatus;
import com.lucid.appmanager.IDownloadStatusCallback;

/** Bound-service API for the store UI and other clients. Requires com.lucid.appmanager.permission.BIND_DOWNLOAD_SERVICE. */
interface IAppManagerDownloadService {
    /** Apps the catalogue currently offers. */
    List<AppInfo> getAvailableApps();

    /** Current run phase, wait reason, progress, vehicle signals and feature-flag state. */
    DownloadStatus getStatus();

    DownloadConfig getConfig();
    void setConfig(in DownloadConfig config);

    /** Feature flag (kill switch). */
    void setEnabled(boolean enabled);

    /** Install path: "PACKAGE_MANAGER" or "APK_OVERRIDE" (FR-52). Unknown values fall back to the former. */
    void setInstallMode(String mode);

    /** Start the gated download now. packageName is recorded for the catalogue phase; this phase downloads one bundle. */
    void startDownload(String packageName);

    /** Cancel the current run. The daily schedule stays armed. */
    void cancelDownload();

    void registerCallback(IDownloadStatusCallback callback);
    void unregisterCallback(IDownloadStatusCallback callback);
}
