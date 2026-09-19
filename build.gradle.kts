plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Consumers (d8gp-app-android, d8gp/server) pull modules in with
// includeBuild("../d8gp-core"); Gradle substitutes "com.d8gp.core:<module>"
// with the project of the same name, so group + project name are the contract.
allprojects {
    group = "com.d8gp.core"
    version = "0.1.0"
}
