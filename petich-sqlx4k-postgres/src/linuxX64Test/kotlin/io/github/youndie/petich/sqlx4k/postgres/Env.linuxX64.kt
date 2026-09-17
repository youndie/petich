package io.github.youndie.petich.sqlx4k.postgres

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun testEnv(name: String): String? = getenv(name)?.toKString()

internal actual val testTarget: String = "linuxx64"
