package app.polyglot.dev.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ProviderKeyEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        DraftEntity::class,
        CatalogModelEntity::class,
        ConfigModelSnapshotEntity::class,
        ModelVerificationEntity::class,
        AppModelListEntity::class,
        AppProviderKeyEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun providerKeyDao(): ProviderKeyDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun draftDao(): DraftDao
    abstract fun catalogDao(): CatalogDao
    abstract fun configModelDao(): ConfigModelDao
    abstract fun verificationDao(): VerificationDao
    abstract fun appModelListDao(): AppModelListDao
    abstract fun appProviderKeyDao(): AppProviderKeyDao
}
