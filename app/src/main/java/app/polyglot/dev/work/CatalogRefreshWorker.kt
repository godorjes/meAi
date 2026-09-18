package app.polyglot.dev.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.polyglot.dev.PolyglotApp
import app.polyglot.dev.data.repo.CatalogRepository
import java.util.concurrent.TimeUnit

/**
 * 定时尽力更新，每 24 小时（需有网络）：
 *
 * 1. 公共模型目录：只有配置了可信的远程地址才请求；失败保留现有目录。
 * 2. 各厂家最新模型：用 App 级 Key 拉取每家最新前五，整家替换本地缓存；
 *    一家失败不影响其他家，失败或空结果不清空已有缓存。
 *
 * 用户打开模型选择器只读本地缓存，不在输入时在线请求；
 * 本任务只是"尽力更新"，页面正常使用不依赖它。
 */
class CatalogRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PolyglotApp)?.container ?: return Result.success()

        // 1. 公共目录：未部署时地址为空，直接跳过
        runCatching {
            container.catalogRepository.refreshFromRemote(CatalogRepository.PUBLIC_CATALOG_URL)
        }

        // 2. 各厂家最新模型缓存：逐厂家隔离，失败静默跳过，保留旧缓存
        runCatching { container.catalogRepository.syncTopModels() }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "catalog_refresh"
        private const val WORK_NAME_ONCE = "catalog_refresh_once"

        /** 每天一次的周期任务 */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** 首次安装且还没有缓存时立即拉一次，不必等下一个周期 */
        fun enqueueOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CatalogRefreshWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
