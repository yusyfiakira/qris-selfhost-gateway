package id.qrisgateway.listener

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pkg = inputData.getString(KEY_PKG) ?: return Result.failure()
        val message = inputData.getString(KEY_MSG) ?: return Result.failure()
        val config = ConfigStore.load(applicationContext)

        return when (val result = WebhookClient.sendMutation(config, pkg, message)) {
            is SendResult.Delivered -> {
                val label = when (result.outcome) {
                    "MATCHED_PAID" -> "✅ RETRY LUNAS (${result.invoiceId ?: "-"} | Rp ${result.amount ?: 0})"
                    "DUPLICATE_PAID" -> "✅ RETRY SUDAH LUNAS (${result.invoiceId ?: "-"})"
                    else -> "⚪ RETRY DIABAIKAN (Rp ${result.amount ?: 0})"
                }
                DeliveryLogStore.append(applicationContext, label)
                Result.success()
            }
            is SendResult.Rejected -> {
                DeliveryLogStore.append(applicationContext, "❌ DITOLAK (${result.code}): ${result.message}")
                Result.failure()
            }
            is SendResult.TransientFailure -> {
                if (runAttemptCount >= 6) {
                    DeliveryLogStore.append(applicationContext, "❌ GAGAL PERMANEN setelah retry: ${result.reason}")
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    companion object {
        private const val KEY_PKG = "pkg"
        private const val KEY_MSG = "msg"

        fun enqueue(context: Context, packageName: String, message: String) {
            val data = Data.Builder()
                .putString(KEY_PKG, packageName)
                .putString(KEY_MSG, message)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
