package my.umarh.paudio.feature.profiles

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportResult
import my.umarh.paudio.data.transfer.ProfileExporter
import my.umarh.paudio.data.transfer.ProfileImporter

internal class DataProfileTransfer @Inject constructor(
    private val exporter: ProfileExporter,
    private val importer: ProfileImporter,
) : ProfileTransfer {
    override suspend fun export(profileId: Long): String? = exporter.export(profileId)

    override suspend fun import(bytes: ByteArray, policy: ConflictPolicy): ImportResult = importer.import(bytes, policy)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProfilesModule {
    @Binds
    abstract fun transfer(impl: DataProfileTransfer): ProfileTransfer
}
