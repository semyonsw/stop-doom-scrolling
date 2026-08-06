package am.onex.stopdoom.guard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import am.onex.stopdoom.App
import am.onex.stopdoom.data.PendingChange
import am.onex.stopdoom.rules.RuleJson
import java.util.concurrent.TimeUnit

/**
 * Applies queued weakenings once their cooldown has elapsed.
 *
 * Runs periodically rather than on an exact alarm: a change landing a few minutes
 * late is harmless, and a periodic job survives reboots and Doze without needing
 * exact-alarm permission. The UI also drains the queue on resume so an expired
 * change appears the moment you look at it.
 */
class ApplyPendingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        container.applyDuePendingChanges()
        container.usage.trimEventLog()
        return Result.success()
    }

    companion object {
        private const val NAME = "apply-pending-changes"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ApplyPendingWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Shared by the worker and the UI so both drain the queue the same way.
 * Returns the changes that were applied.
 */
suspend fun am.onex.stopdoom.AppContainer.applyDuePendingChanges(
    nowMillis: Long = System.currentTimeMillis(),
): List<PendingChange> {
    val due = pending.due(nowMillis)
    for (change in due) {
        when (change.kind) {
            PendingChange.Kind.RULE_UPSERT ->
                RuleJson.decodeOne(change.payload).getOrNull()?.let { rules.upsert(it) }

            PendingChange.Kind.RULE_DELETE ->
                rules.delete(change.targetId)

            PendingChange.Kind.MAINTENANCE -> {
                val minutes = change.payload.toIntOrNull() ?: 0
                settings.setMaintenanceUntil(nowMillis + minutes * 60_000L)
            }

            PendingChange.Kind.COOLDOWN_MINUTES ->
                change.payload.toIntOrNull()?.let { settings.setCooldownMinutes(it) }

            PendingChange.Kind.TOGGLE -> {
                val toggle = GuardedToggle.parse(change.targetId)
                val value = change.payload.toBooleanStrictOrNull()
                if (toggle != null && value != null) cooldownGate.applyToggle(toggle, value)
            }
        }
        pending.remove(change.id)
        usage.log(nowMillis, CooldownGate.KIND_APPLIED, change.targetId, change.description)
    }
    if (due.isNotEmpty()) engine.replaceRules(rules.readAll())
    return due
}
