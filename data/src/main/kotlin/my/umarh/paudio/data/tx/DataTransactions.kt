package my.umarh.paudio.data.tx

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import my.umarh.paudio.data.db.PAudioDatabase

/**
 * Runs several repository writes atomically. Everything done through the `:data` repositories inside [withTransaction]
 * commits together, or (if [block] throws, including by cancellation) is rolled back. Repository methods that open
 * their own transaction simply join the outer one. Do not hold the transaction across slow work (audio, network, UI).
 */
interface DataTransactions {
    suspend fun <T> withTransaction(block: suspend () -> T): T
}

@Singleton
class RoomDataTransactions @Inject constructor(private val db: PAudioDatabase) : DataTransactions {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}
