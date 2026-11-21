package my.umarh.paudio.service.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import my.umarh.paudio.audio.effect.toEqConfig
import my.umarh.paudio.core.audiometry.MeasurementModel
import my.umarh.paudio.core.audiometry.ThresholdPoint
import my.umarh.paudio.core.dsp.DpEqPlan
import my.umarh.paudio.core.dsp.DpEqPlanner
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import javax.inject.Inject
import javax.inject.Singleton

/** HL estimates (dB, [MeasurementModel.compute] `hlEst`, unclamped) of the latest complete test of a profile. */
data class HlSource(
    val testId: Long,
    val algoVersion: Int,
    val hlLeft: List<Double>,
    val hlRight: List<Double>,
)

/**
 * Raw thresholds -> [MeasurementModel] -> HL -> compensation curve -> DP plan -> [EqConfig] (SPEC §2.1.3-§2.1.4).
 *
 * [config] is pure and memoized by (testId, strength, gB, gP, gT, algoVersion, fs, ladder), so features and the
 * engine pipeline may call it repeatedly. A profile with no complete test gets the preference-only curve
 * (HL = 0 everywhere, so only gB/gP/gT act); with all of those at 0 that is a flat plan (DECISIONS §20).
 */
@Singleton
class ProfileCurveService @Inject constructor(private val tests: HearingTestRepository) {

    private data class Key(
        val testId: Long?,
        val strength: Float,
        val gB: Float,
        val gP: Float,
        val gT: Float,
        val algoVersion: Int,
        val fs: Int,
        val ladder: DpLadderStep,
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, EqConfig>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, EqConfig>?) = size > CACHE_SIZE
    }

    /** Number of real plan computations so far (cache misses); for tests. */
    @Volatile
    var plansComputed: Int = 0
        private set

    /**
     * The newest COMPLETE test of [profileId] as HL estimates (at most the [MAX_CANDIDATES] newest tests are
     * considered, so an unfinished/imported partial one never hides an older complete one); null = none.
     * Re-emits on changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSource(profileId: Long): Flow<HlSource?> =
        tests.observeForProfile(profileId).flatMapLatest { all ->
            val newest = all.sortedWith(compareByDescending<HearingTestEntity> { it.startedAt }.thenByDescending { it.id })
                .take(MAX_CANDIDATES)
            if (newest.isEmpty()) {
                flowOf(null)
            } else {
                combine(newest.map { t -> tests.observeThresholds(t.id).map { toSource(t.id, t.algoVersion, it) } }) { sources ->
                    sources.firstOrNull { it != null }
                }
            }
        }.distinctUntilChanged()

    /** One-shot variant of [observeSource] for features that need the curve once. */
    suspend fun loadSource(profileId: Long): HlSource? {
        val newest = tests.observeForProfile(profileId).first()
            .sortedWith(compareByDescending<HearingTestEntity> { it.startedAt }.thenByDescending { it.id })
            .take(MAX_CANDIDATES)
        for (t in newest) toSource(t.id, t.algoVersion, tests.thresholds(t.id))?.let { return it }
        return null
    }

    /** The memoized backend config for [profile] (all of its settings are part of the cache key). */
    fun config(profile: ProfileEntity, source: HlSource?, fs: Int, ladder: DpLadderStep): EqConfig {
        val key = Key(source?.testId, profile.compStrength, profile.gB, profile.gP, profile.gT, source?.algoVersion ?: 0, fs, ladder)
        synchronized(lock) { cache[key]?.let { return it } }
        val cfg = try {
            plan(profile, source, fs, ladder).toEqConfig()
        } catch (_: IllegalArgumentException) {
            // Corrupt stored values (non-finite): never crash the engine pipeline, process flat instead.
            DpEqPlanner.flat(fs, ladder = ladder).toEqConfig()
        }
        synchronized(lock) {
            plansComputed++
            cache[key] = cfg
        }
        return cfg
    }

    /** The uncached plan, e.g. for drawing the EQ graph (`plan.curve`). */
    fun plan(profile: ProfileEntity, source: HlSource?, fs: Int, ladder: DpLadderStep): DpEqPlan {
        if (source == null && profile.gB == 0f && profile.gP == 0f && profile.gT == 0f) {
            return DpEqPlanner.flat(fs, ladder = ladder) // no data at all: exactly flat, no -0.5 dB margin preamp
        }
        val zeros = DoubleArray(FrequencyGrid.TEST_FREQUENCIES_HZ.size)
        return DpEqPlanner.plan(
            hlLeft = source?.hlLeft?.toDoubleArray() ?: zeros,
            hlRight = source?.hlRight?.toDoubleArray() ?: zeros,
            strength = profile.compStrength.toDouble().coerceIn(0.0, 1.0),
            gB = profile.gB.toDouble(),
            gP = profile.gP.toDouble(),
            gT = profile.gT.toDouble(),
            fs = fs,
            ladder = ladder,
        )
    }

    private fun toSource(testId: Long, algoVersion: Int, thresholds: List<ThresholdEntity>): HlSource? {
        val byEar = Ear.entries.associateWith { ear ->
            thresholds.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
        }
        if (!byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }) return null
        val points = byEar.mapValues { (_, pts) ->
            FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
                val t = pts.getValue(f)
                ThresholdPoint(f, t.levelDbfs!!.toDouble(), t.censored)
            }
        }
        val hl = try {
            MeasurementModel.compute(points).hlEst
        } catch (_: IllegalArgumentException) {
            return null
        }
        val left = hl.getValue(Ear.LEFT)
        val right = hl.getValue(Ear.RIGHT)
        if (!(left + right).all { it.isFinite() }) return null
        return HlSource(testId, algoVersion, left, right)
    }

    private companion object {
        const val CACHE_SIZE = 16
        const val MAX_CANDIDATES = 5
    }
}
