// AGP 9 يحتوي Kotlin مدمج؛ هذا السطر يحدد نسخة Kotlin المستخدمة (نفس إعداد NewPipe)
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
