package app.polyglot.dev

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.polyglot.dev.data.crypto.KeyCrypto
import app.polyglot.dev.data.local.AppDatabase
import app.polyglot.dev.data.local.DatabaseMigrations
import app.polyglot.dev.data.local.Prefs
import app.polyglot.dev.data.net.OpenAiClient
import app.polyglot.dev.data.repo.CatalogRepository
import app.polyglot.dev.data.repo.ChatRepository
import app.polyglot.dev.data.repo.ConfigRepository
import app.polyglot.dev.data.repo.DraftWriter
import app.polyglot.dev.data.repo.GenerationCoordinator
import app.polyglot.dev.work.CatalogRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 简单的服务容器，首版单模块内手工注入 */
class AppContainer(app: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "polyglot.db")
        // 升级一律走显式迁移，保留用户数据。
        // 这里刻意不配置任何 fallbackToDestructiveMigration：
        // 迁移缺失或失败时宁可打不开并报错，也不能把用户数据重建为空的。
        .addMigrations(*DatabaseMigrations.ALL)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // WAL + NORMAL 在掉电/系统崩溃后可能回滚最近若干次事务。
                // 要求每次提交都落到存储，所以把写连接的同步级别提到 FULL。
                db.execSQL("PRAGMA synchronous=FULL")
            }
        })
        .build()

    val prefs = Prefs(app)
    val keyCrypto = KeyCrypto(app)
    val client = OpenAiClient()
    val configRepository = ConfigRepository(db, keyCrypto, client)
    val chatRepository = ChatRepository(db)
    val catalogRepository = CatalogRepository(db, app, keyCrypto).apply {
        // 目录版本落地后记下来：下次只在随包目录更新时才替换
        onVersionApplied = { version -> prefs.setCatalogVersion(version) }
    }
    val draftWriter = DraftWriter(chatRepository, appScope)
    val generationCoordinator = GenerationCoordinator(
        repo = chatRepository,
        configRepo = configRepository,
        client = client,
        networkAvailable = { isNetworkAvailable(app) },
        scope = appScope
    )

    companion object {
        fun isNetworkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}

/**
 * 持久化设置核对。
 *
 * 只发一次 PRAGMA 不算数，必须读回真实生效的值并留存供验收比对。
 *
 * 注意 `synchronous` 是**连接级**设置：Room 的连接池在新建连接时会把 WAL 的同步级别
 * 重置为系统默认（NORMAL），因此这里对 onOpen 里的主（写）连接设置 FULL，
 * 而提交事务只走这条写连接，掉电持久性由它的同步级别决定。
 * 读连接的取值一并读回并如实记录，不假装全池都是 FULL。
 */
object DurabilityReport {

    const val TAG = "PolyglotDurability"

    data class Snapshot(val journalMode: String, val synchronous: String) {
        val walEnabled: Boolean get() = journalMode.equals("wal", ignoreCase = true)
        val fullSync: Boolean get() = synchronous == "2" || synchronous.equals("full", true)
    }

    fun read(db: AppDatabase): Snapshot = Snapshot(
        journalMode = pragma(db, "PRAGMA journal_mode").lowercase(),
        synchronous = pragma(db, "PRAGMA synchronous")
    )

    fun log(db: AppDatabase): Snapshot =
        read(db).also { Log.i(TAG, "journal_mode=${it.journalMode} synchronous=${it.synchronous}") }

    private fun pragma(db: AppDatabase, sql: String): String =
        db.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else "?"
        }
}

class PolyglotApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 退出前台主动结束客户端生成（旋转不会触发，见 AppLifecycleObserver）
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            AppLifecycleObserver { container.generationCoordinator.interruptForLifecycle() }
        )
        // 启动恢复：遗留“生成中”改为“已中断”，清理已过期软删除，剩余撤销时间交回界面。
        // 恢复完成前 GenerationCoordinator 会拒绝开始新的生成。
        appScope.launch {
            runCatching { container.generationCoordinator.recoverOnLaunch() }
            // 恢复完成后再核对并记录实际生效的持久化设置
            runCatching { DurabilityReport.log(container.db) }
        }
        // 历史版本的密钥密文搬进数据库，可重复执行、可中断续跑
        appScope.launch {
            runCatching { container.configRepository.migrateLegacyKeys() }
        }
        appScope.launch {
            // 公共目录：首次安装播种；随包目录版本更高时替换已生效的资料
            runCatching {
                val applied = container.prefs.catalogVersion.first()
                if (!container.catalogRepository.seedIfEmpty(applied)) {
                    container.catalogRepository.syncBuiltIn(applied)
                }
            }
            // App 级厂家 Key：首次安装播进数据库（加密，只补缺失的）
            runCatching { container.catalogRepository.seedAppKeysIfMissing() }
            // 首次还没有模型缓存时立即拉一次，不必等下一个 24h 周期
            runCatching {
                if (!container.catalogRepository.hasAppModelCache()) {
                    CatalogRefreshWorker.enqueueOnce(this@PolyglotApp)
                }
            }
        }
        // 每日定时拉取各家最新模型（前五），写入本地缓存；用户打开列表只读本地
        CatalogRefreshWorker.enqueue(this)
    }
}
