package com.viperdam.kidsprayer.di

import android.content.Context
// Remove Room imports if no longer needed by other parts of the module
// import androidx.room.Room
// import com.viperdam.kidsprayer.database.quran.QuranDao
// import com.viperdam.kidsprayer.database.quran.QuranDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // Remove QuranDatabase provider
    /*
    @Provides
    @Singleton
    fun provideQuranDatabase(@ApplicationContext appContext: Context): QuranDatabase {
        return Room.databaseBuilder(
            appContext,
            QuranDatabase::class.java,
            QuranDatabase.DATABASE_NAME
        )
            // Copy the pre-packaged database from assets
            .createFromAsset("database/${QuranDatabase.DATABASE_NAME}")
            // Optional: Add migrations if you change the schema later
            // .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration() // Use only during development if you don't need migrations
            .build()
    }
    */

    // Remove QuranDao provider
    /*
    @Provides
    @Singleton // Or ActivityRetainedComponent if DAO lifecycle is shorter
    fun provideQuranDao(database: QuranDatabase): QuranDao {
        return database.quranDao()
    }
    */

    // Provide other DAOs here if you have more databases/tables
} 