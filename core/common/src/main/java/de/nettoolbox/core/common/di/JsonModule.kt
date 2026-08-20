package de.nettoolbox.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object JsonModule {

    /**
     * One shared Json instance for tool parameters and results in the run history.
     *
     * `ignoreUnknownKeys` matters here specifically: a history entry written by an
     * older version of a tool must still open after the tool gains an option.
     */
    @Provides
    @Singleton
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }
}
