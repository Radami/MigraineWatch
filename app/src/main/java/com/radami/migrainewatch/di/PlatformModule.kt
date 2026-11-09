package com.radami.migrainewatch.di

import android.content.Context
import com.radami.migrainewatch.BuildConfig
import com.radami.migrainewatch.R
import com.radami.migrainewatch.notifications.AndroidAlertDebugTools
import com.radami.migrainewatch.notifications.AndroidNotificationPermissions
import com.radami.migrainewatch.ui.AppInfo
import com.radami.migrainewatch.ui.BuildType
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** The Android side of what the shared screens ask of the platform. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {

    @Binds
    abstract fun bindNotificationPermissions(permissions: AndroidNotificationPermissions): NotificationPermissions

    @Binds
    abstract fun bindAlertDebugTools(tools: AndroidAlertDebugTools): AlertDebugTools

    @Binds
    abstract fun bindUiDependencies(dependencies: AndroidUiDependencies): UiDependencies

    companion object {

        /** The name, version and build type this APK was built with. */
        @Provides
        fun provideAppInfo(@ApplicationContext context: Context): AppInfo = AppInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
            buildType = if (BuildConfig.DEBUG) BuildType.DEBUG else BuildType.RELEASE
        )
    }
}
