package my.umarh.paudio.testing

/**
 * Marks an instrumented test that needs a real audio stack (DynamicsProcessing, real AudioTrack...).
 * Exclude on emulators/CI with `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=my.umarh.paudio.testing.RequiresHardware`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresHardware(val reason: String = "")
