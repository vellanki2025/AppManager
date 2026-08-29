package com.lucid.auto.appstore.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.fakes.FakeAutoUpdateScheduler
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-6.4: the flag and the periodic worker stay in lockstep. Enabling enqueues unique work;
 * disabling cancels it, so no background update job exists while auto-update is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoUpdateSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerAutoUpdateScheduler

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerAutoUpdateScheduler(workManager)
    }

    private fun workInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(
            WorkManagerAutoUpdateScheduler.UNIQUE_WORK_NAME,
        ).get()

    @Test
    fun `enqueue registers exactly one unique periodic job`() {
        scheduler.enqueue()

        val infos = workInfos()
        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `enqueueing twice keeps a single job`() {
        scheduler.enqueue()
        scheduler.enqueue()

        assertThat(workInfos()).hasSize(1)
    }

    @Test
    fun `cancel removes the periodic job`() {
        scheduler.enqueue()
        scheduler.cancel()

        assertThat(workInfos().single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `the job requires an unmetered network and a healthy battery`() {
        scheduler.enqueue()

        val constraints = workInfos().single().constraints
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(constraints.requiresBatteryNotLow()).isTrue()
    }

    @Test
    fun `applyFlag enqueues when enabled and cancels when disabled`() {
        val fake = FakeAutoUpdateScheduler()

        fake.applyFlag(true)
        fake.applyFlag(false)

        assertThat(fake.calls).containsExactly("enqueue", "cancel").inOrder()
    }

    @Test
    fun `applyFlag on the real scheduler leaves no job behind when disabled`() {
        scheduler.applyFlag(true)
        assertThat(workInfos().single().state).isEqualTo(WorkInfo.State.ENQUEUED)

        scheduler.applyFlag(false)
        assertThat(workInfos().single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}
