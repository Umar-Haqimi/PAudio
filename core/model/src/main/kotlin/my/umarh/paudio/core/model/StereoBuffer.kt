package my.umarh.paudio.core.model

/** A block of float PCM, one array per ear, samples in [-1, 1] (full scale = 1.0). */
class StereoBuffer(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    init {
        require(left.size == right.size) { "left/right length mismatch: ${left.size} vs ${right.size}" }
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val frames: Int get() = left.size

    fun channel(ear: Ear): FloatArray = if (ear == Ear.LEFT) left else right

    override fun equals(other: Any?): Boolean =
        other is StereoBuffer && sampleRate == other.sampleRate &&
            left.contentEquals(other.left) && right.contentEquals(other.right)

    override fun hashCode(): Int =
        31 * (31 * left.contentHashCode() + right.contentHashCode()) + sampleRate

    override fun toString(): String = "StereoBuffer(frames=$frames, sampleRate=$sampleRate)"
}
