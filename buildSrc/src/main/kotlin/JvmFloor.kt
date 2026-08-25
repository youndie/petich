// The oldest Java a consumer of petich may be on. A decision rather than a side effect of whatever
// JDK the build ran on, which is why it is a named constant and not a literal at each use site.
//
// It has to reach three places that cannot see each other: the toolchain every module compiles
// with, the org.gradle.jvm.version attribute its published variants advertise, and the audit that
// checks the two agree (tools/jvm-floor-audit.py, which reads this file). A number kept in three
// places drifts in two of them.
const val JVM_FLOOR = 25
