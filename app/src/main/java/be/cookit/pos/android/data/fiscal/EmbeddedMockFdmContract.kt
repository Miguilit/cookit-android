package be.cookit.pos.android.data.fiscal

data class EmbeddedMockFdmStatus(
    val available: Boolean = false,
    val running: Boolean = false,
    val host: String = EmbeddedMockFdmContract.HOST,
    val port: Int = EmbeddedMockFdmContract.PORT,
    val lastError: String? = null
)

object EmbeddedMockFdmContract {
    const val HOST = "127.0.0.1"
    const val PORT = 8787
    const val PATH = "/graphql"
}
