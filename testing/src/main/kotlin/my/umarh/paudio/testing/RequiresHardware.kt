package my.umarh.paudio.testing

/**
 * Marks an instrumented test that needs real audio hardware / a real device (not an emulator without
 * audio). CI filters these out via `notAnnotation=my.umarh.paudio.testing.RequiresHardware`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresHardware
