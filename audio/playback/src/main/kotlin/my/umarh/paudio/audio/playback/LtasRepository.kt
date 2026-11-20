package my.umarh.paudio.audio.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.dsp.Ltas
import my.umarh.paudio.core.preference.DemoClip
import javax.inject.Inject
import javax.inject.Singleton

/** Loads (and caches) the bundled clip LTAS files and the pink-noise reference LTAS from assets. */
@Singleton
class LtasRepository internal constructor(private val readAsset: (String) -> String) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this({ path -> context.assets.open(path).bufferedReader().use { it.readText() } })

    private val cache = HashMap<String, Ltas>()
    private val lock = Mutex()

    suspend fun forClip(clip: DemoClip): Ltas = load(clip.ltasAssetPath)

    /** Pink-noise LTAS, used for user-picked files. */
    suspend fun pink(): Ltas = load(DemoClip.PINK_LTAS_ASSET_PATH)

    private suspend fun load(path: String): Ltas = lock.withLock {
        cache[path] ?: withContext(Dispatchers.IO) { Ltas.parse(readAsset(path)) }.also { cache[path] = it }
    }
}
