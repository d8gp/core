import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The one framework the iOS app links. Every shared module is exported here so
// Swift sees its public API directly; add new modules to `exported`.
val exported = listOf(":plugin-protocol")

kotlin {
    val xcframework = XCFramework("D8gpCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "D8gpCore"
            isStatic = true
            exported.forEach { export(project(it)) }
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            exported.forEach { api(project(it)) }
        }
    }
}
