package com.zdownloader.app.di

import android.content.Context
import androidx.room.Room
import com.zdownloader.app.data.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ZDatabase = Room.databaseBuilder(context, ZDatabase::class.java, "z-downloads.db").addMigrations(ZDatabase.MIGRATION_1_2,ZDatabase.MIGRATION_2_3,ZDatabase.MIGRATION_3_4,ZDatabase.MIGRATION_4_5,ZDatabase.MIGRATION_5_6,ZDatabase.MIGRATION_6_7,ZDatabase.MIGRATION_7_8).build()
    @Provides fun dao(db: ZDatabase): DownloadDao = db.downloads()
}
