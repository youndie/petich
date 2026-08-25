// The oldest Java a consumer of petich may be on. A decision rather than a side effect of whatever
// JDK the build ran on, which is why it is a named constant and not a literal at each use site.
//
// It has to reach three places that cannot see each other: the toolchain every module compiles
// with, the org.gradle.jvm.version attribute its published variants advertise, and the audit that
// checks the two agree (tools/jvm-floor-audit.py, which reads this file). A number kept in three
// places drifts in two of them.
// 21 rather than 25: nothing in petich needs a Java newer than 21 — there is no java.* call in any
// source set — and 25 was the JDK the build happened to run on, which is precisely the accident the
// paragraph above is about. It cost every consumer on 21 the ability to use the library at all, and
// cost this repository the only check that can see an `implementation` which should be `api`: the
// tool that runs that check compiles a consumer on 21 and could not resolve petich at 25 (#11).
const val JVM_FLOOR = 21
