package io.github.youndie.petich.sqlx4k.postgres

internal actual fun testEnv(name: String): String? = System.getenv(name)

internal actual val testTarget: String = "jvm"
