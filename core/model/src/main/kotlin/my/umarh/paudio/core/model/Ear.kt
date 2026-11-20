package my.umarh.paudio.core.model

/**
 * Which ear a stimulus or a curve belongs to.
 *
 * [channelIndex] is the index used for per-channel `DynamicsProcessing` configuration and for the
 * channel order of [StereoBuffer]/[EqConfig]. TENTATIVE: LEFT=0, RIGHT=1 (standard stereo order);
 * the M0 research confirms the DP channel-to-speaker mapping (docs/DECISIONS.md).
 */
enum class Ear(val channelIndex: Int) {
    LEFT(0),
    RIGHT(1),
    ;

    val other: Ear get() = if (this == LEFT) RIGHT else LEFT
}
