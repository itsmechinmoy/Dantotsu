plugins {
    alias(libs.plugins.android) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google) apply false
    alias(libs.plugins.crashlytics) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}