package my.umarh.paudio.testing

/**
 * Marks an instrumented test that needs real audio hardware (AudioTrack output, a real volume curve, a mic,
 * a headset). Such tests are skipped in CI by passing an instrumentation argument that excludes this annotation:
 *
 *     ./gradlew connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.notAnnotation=my.umarh.paudio.testing.RequiresHardware
 *
 * On a device with the required hardware run them with
 * `-Pandroid.testInstrumentationRunnerArguments.annotation=my.umarh.paudio.testing.RequiresHardware`.
 * Results feed HARDWARE_TEST.md.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresHardware(val reason: String = "")
