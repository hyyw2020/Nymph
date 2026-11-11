plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // Compose 编译器随 Kotlin 版本走（2.0+ 不再需要独立 composeOptions）
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
