/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.blocklist

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duckduckgo.anvil.annotations.ContributesWorker
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.mobile.android.vpn.store.VpnDatabase
import com.duckduckgo.mobile.android.vpn.trackers.AppTrackerExceptionRuleMetadata
import com.duckduckgo.mobile.android.vpn.trackers.AppTrackerMetadata
import com.squareup.anvil.annotations.ContributesMultibinding
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.withContext
import timber.log.Timber

@ContributesWorker(AppScope::class)
class AppTrackerListUpdateWorker(context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters) {
    @Inject
    lateinit var appTrackerListDownloader: AppTrackerListDownloader
    @Inject
    lateinit var vpnDatabase: VpnDatabase
    @Inject
    lateinit var dispatchers: DispatcherProvider

    override suspend fun doWork(): Result {
        return withContext(dispatchers.io()) {
            val updateBlocklistResult = updateTrackerBlocklist()
            val updateRulesResult = updateTrackerExceptionRules()

            val success = Result.success()
            if (updateBlocklistResult != success || updateRulesResult != success) {
                Timber.w("One of the app tracker list updates failed, scheduling a retry")
                return@withContext Result.retry()
            }

            Timber.w("Tracker list updates success")
            return@withContext success
        }
    }

    private fun updateTrackerBlocklist(): Result {
        Timber.d("Updating the app tracker blocklist")
        val blocklist = appTrackerListDownloader.downloadAppTrackerBlocklist()
        when (blocklist.etag) {
            is ETag.ValidETag -> {
                val currentEtag =
                    vpnDatabase.vpnAppTrackerBlockingDao().getTrackerBlocklistMetadata()?.eTag
                val updatedEtag = blocklist.etag.value

                if (updatedEtag == currentEtag) {
                    Timber.v("Downloaded blocklist has same eTag, noop")
                    return Result.success()
                }

                Timber.d("Updating the app tracker blocklist, eTag: ${blocklist.etag.value}")

                vpnDatabase
                    .vpnAppTrackerBlockingDao()
                    .updateTrackerBlocklist(
                        blocklist.blocklist,
                        blocklist.appPackages,
                        AppTrackerMetadata(eTag = blocklist.etag.value),
                        blocklist.entities
                    )

                return Result.success()
            }
            else -> {
                Timber.w("Received app tracker blocklist with invalid eTag")
                return Result.retry()
            }
        }
    }

    private fun updateTrackerExceptionRules(): Result {
        Timber.d("Updating the app tracker exception rules")
        val exceptionRules = appTrackerListDownloader.downloadAppTrackerExceptionRules()
        when (exceptionRules.etag) {
            is ETag.ValidETag -> {
                val currentEtag =
                    vpnDatabase.vpnAppTrackerBlockingDao().getTrackerExceptionRulesMetadata()?.eTag
                val updatedEtag = exceptionRules.etag.value

                if (updatedEtag == currentEtag) {
                    Timber.v("Downloaded exception rules has same eTag, noop")
                    return Result.success()
                }

                Timber.d("Updating the app tracker rules, eTag: ${exceptionRules.etag.value}")
                vpnDatabase
                    .vpnAppTrackerBlockingDao()
                    .updateTrackerExceptionRules(
                        exceptionRules.trackerExceptionRules,
                        AppTrackerExceptionRuleMetadata(eTag = exceptionRules.etag.value)
                    )

                return Result.success()
            }
            else -> {
                Timber.w("Received app tracker exception rules with invalid eTag")
                return Result.retry()
            }
        }
    }
}

@ContributesMultibinding(
    scope = AppScope::class,
    boundType = LifecycleObserver::class
)
class AppTrackerListUpdateWorkerScheduler @Inject constructor(
    private val workManager: WorkManager
) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        Timber.v("Scheduling tracker blocklist update worker")
        val workerRequest =
            PeriodicWorkRequestBuilder<AppTrackerListUpdateWorker>(12, TimeUnit.HOURS)
                .addTag(APP_TRACKER_LIST_UPDATE_WORKER_TAG)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniquePeriodicWork(
            APP_TRACKER_LIST_UPDATE_WORKER_TAG, ExistingPeriodicWorkPolicy.KEEP, workerRequest
        )
    }

    companion object {
        private const val APP_TRACKER_LIST_UPDATE_WORKER_TAG = "APP_TRACKER_LIST_UPDATE_WORKER_TAG"
    }
}
