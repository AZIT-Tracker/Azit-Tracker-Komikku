package eu.kanade.tachiyomi.data.track.azit

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Moves the AniList tracking of the library to Azit Tracker, one manga after the other.
 *
 * For each library manga with an AniList track and no Azit track: bind Azit by the AniList id (Azit imports the
 * title, or makes it from Komikku's data), then copy status, chapters, score and dates. Manga already on Azit are
 * skipped, so running it again resumes where a stopped run ended.
 */
class AzitMigrationJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val azit = Injekt.get<TrackerManager>().azit
        if (!azit.isLoggedIn) return Result.failure()
        val unbindAnilist = inputData.getBoolean(KEY_UNBIND_ANILIST, false)

        setForegroundSafely()

        val tracks = try {
            azit.anilistTracksToMigrate()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Azit migration: could not list the AniList tracks" }
            return Result.failure()
        }
        val summary = AzitMigrationSummary()
        tracks.forEachIndexed { index, track ->
            if (isStopped) return@forEachIndexed
            context.notify(ID_PROGRESS, progressNotification(track.title, index, tracks.size).build())
            try {
                summary.add(azit.migrateFromAnilist(track, unbindAnilist))
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Azit migration failed for ${track.title}" }
                summary.addFailure(track.title)
            }
            delay(DELAY_MS)
        }

        context.notify(ID_PROGRESS, summaryNotification(summary, stopped = isStopped).build())
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            ID_PROGRESS,
            progressNotification(null, 0, 0).build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun progressNotification(title: String?, done: Int, total: Int) =
        context.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setContentTitle(context.stringResource(KMR.strings.azit_migration_running))
            title?.let { setContentText("${done + 1}/$total · $it") }
            setProgress(total, done, total == 0)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }

    private fun summaryNotification(summary: AzitMigrationSummary, stopped: Boolean) =
        context.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_done_24dp)
            setContentTitle(
                context.stringResource(
                    if (stopped) KMR.strings.azit_migration_stopped else KMR.strings.azit_migration_done,
                ),
            )
            val text = context.stringResource(
                KMR.strings.azit_migration_summary,
                summary.migrated,
                summary.created,
                summary.failed.size,
            )
            setContentText(text)
            val failed = summary.failed.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n")
            setStyle(NotificationCompat.BigTextStyle().bigText(text + failed.orEmpty()))
            setOngoing(false)
            setAutoCancel(true)
        }

    companion object {
        private const val TAG = "AzitMigration"
        private const val KEY_UNBIND_ANILIST = "unbind_anilist"
        private const val ID_PROGRESS = -0x7A17

        /** Pause between two titles: the server is self-hosted, and AniList imports are rate limited. */
        private const val DELAY_MS = 1_000L

        fun start(context: Context, unbindAnilist: Boolean) {
            val request = OneTimeWorkRequestBuilder<AzitMigrationJob>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_UNBIND_ANILIST to unbindAnilist))
                .addTag(TAG)
                .build()
            // KEEP: tapping "migrate" again while it runs does not start a second run.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Counts of a migration run (shown in the final notification). */
class AzitMigrationSummary {
    var migrated = 0
        private set
    var created = 0
        private set
    var skipped = 0
        private set
    val failed = mutableListOf<String>()

    fun add(outcome: Azit.MigrationOutcome) {
        when (outcome) {
            Azit.MigrationOutcome.MIGRATED -> migrated++
            Azit.MigrationOutcome.CREATED -> {
                migrated++
                created++
            }
            Azit.MigrationOutcome.SKIPPED -> skipped++
        }
    }

    fun addFailure(title: String) {
        failed += title
    }
}
