plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// The toolchain, the JVM floor and the org.gradle.jvm.version attribute used to be arranged here by
// hand, over `subprojects { afterEvaluate { … } }`. All three now come from `sborka.base` and
// `sborka.publish`, which set the attribute by looking at what a configuration IS — the
// java-api/java-runtime usage — rather than at the two names `jvmApiElements` and
// `jvmRuntimeElements`. Same result here, where every target is `jvm()`; a wider net the day a
// module declares `jvm("something")`.
//
// The numbers themselves did not move into a plugin. They live in `gradle.properties`, one line
// each, with the reason for 21 written beside them.
