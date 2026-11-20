package my.umarh.paudio.testing

import my.umarh.paudio.core.model.EnvSnapshot
import my.umarh.paudio.core.model.EnvironmentChecks

class FakeEnvironmentChecks(var value: EnvSnapshot = EnvSnapshot(false, 0f, false, false)) : EnvironmentChecks {
    override suspend fun snapshot(): EnvSnapshot = value
}
