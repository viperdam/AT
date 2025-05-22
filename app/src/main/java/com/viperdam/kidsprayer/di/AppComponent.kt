package com.viperdam.kidsprayer.di

import android.content.Context
import com.viperdam.kidsprayer.ui.language.LanguageManager
import dagger.BindsInstance
import dagger.Component
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Singleton
@Component(modules = [LanguageModule::class])
interface AppComponent {
    fun languageManager(): LanguageManager

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @ApplicationContext context: Context): AppComponent
    }
} 