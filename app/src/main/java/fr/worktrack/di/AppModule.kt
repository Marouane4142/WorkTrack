package fr.worktrack.di

import android.content.Context
import androidx.room.Room
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.worktrack.data.*
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("worktrack_preferences") }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): WorkDatabase =
        Room.databaseBuilder(context, WorkDatabase::class.java, "worktrack.db")
            .addMigrations(WorkDatabase.FROM_DOTNET).build()
}

@Module @InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun repository(impl: LocalWorkRepository): WorkRepository
}
