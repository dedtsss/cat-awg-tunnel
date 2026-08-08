plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlinxSerialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

sourceSets {
    named("test") {
        resources.srcDir("../contracts")
    }
}
