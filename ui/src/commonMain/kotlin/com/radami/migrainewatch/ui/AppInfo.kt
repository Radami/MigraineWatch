package com.radami.migrainewatch.ui

/** Which kind of build is running. Debug builds show tools a release never does. */
enum class BuildType { DEBUG, RELEASE }

/** The app's own name, version and build, as each platform's build reports them. */
data class AppInfo(
    val name: String,
    val version: String,
    val buildType: BuildType
)
