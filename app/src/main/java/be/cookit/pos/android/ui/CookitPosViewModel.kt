package be.cookit.pos.android.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.cookit.pos.android.data.*
import be.cookit.pos.android.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PosUiState(
    val authenticated: Boolean = false,
    val demoMode: Boolean = false,
    val loading: Boolean = false,
    val online: Boolean = false,
    val error: String? = null,
    val user: UserSession = DemoRepository.user,
    val policy: NativePolicy = DemoRepository.policy,
    val categories: List<Category> = DemoRepository.categories,
    val products: List<Product> = DemoRepository.products,
    val orders: List<PosOrder> = DemoRepository.orders,
    val unreadInbound: Int = 0,
    val lastSyncEpochMs: Long? = null
)

class CookitPosViewModel(application: Application) : AndroidViewModel(application) {
    private val api = CookitHttpClient()
    private val sessionStore = SessionStore(application)
    private val _ui = MutableStateFlow(PosUiState())
    val ui: StateFlow<PosUiState> = _ui.asStateFlow()

    private var token: String? = null
    private var pollingJob: Job? = null
    private val knownOrderIds = linkedSetOf<Long>()
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    init {
        val savedToken = sessionStore.token()
        val savedEmail = sessionStore.email()
        if (!savedToken.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
            token = savedToken
            viewModelScope.launch { bootstrap(savedEmail, firstLogin = false) }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _ui.update { it.copy(error = "E-mail et mot de passe requis.") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val newToken = api.login(email, password)
                token = newToken
                sessionStore.save(newToken, email.trim())
                bootstrap(email.trim(), firstLogin = true)
            }.onFailure { throwable ->
                token = null
                sessionStore.clear()
                _ui.update {
                    it.copy(
                        authenticated = false,
                        loading = false,
                        online = false,
                        error = readableError(throwable)
                    )
                }
            }
        }
    }

    fun enterDemo() {
        pollingJob?.cancel()
        _ui.value = PosUiState(authenticated = true, demoMode = true, online = true)
    }

    fun logout() {
        pollingJob?.cancel()
        token = null
        knownOrderIds.clear()
        sessionStore.clear()
        _ui.value = PosUiState()
    }

    fun refresh() {
        val currentToken = token ?: return
        val email = sessionStore.email() ?: "user@cookit.be"
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { refreshLiveData(currentToken, email, primeOrders = true) }
                .onFailure { error -> _ui.update { it.copy(loading = false, online = false, error = readableError(error)) } }
        }
    }

    fun markInboundRead() {
        _ui.update { state -> state.copy(unreadInbound = 0, orders = state.orders.map { it.copy(unread = false) }) }
    }

    private suspend fun bootstrap(email: String, firstLogin: Boolean) {
        val currentToken = token ?: return
        _ui.update { it.copy(loading = true, error = null) }
        refreshLiveData(currentToken, email, primeOrders = true)
        _ui.update { it.copy(authenticated = true, demoMode = false, loading = false, online = true) }
        startPolling()
    }

    private suspend fun refreshLiveData(currentToken: String, email: String, primeOrders: Boolean) {
        val platform = api.platform(currentToken, email)
        val catalog = api.catalog(currentToken)
        val liveOrders = api.orders(currentToken)

        if (primeOrders) {
            knownOrderIds.clear()
            knownOrderIds.addAll(liveOrders.map { it.id })
        }

        _ui.update {
            it.copy(
                authenticated = true,
                online = true,
                loading = false,
                user = platform.user,
                policy = platform.policy,
                categories = catalog.categories.ifEmpty { DemoRepository.categories },
                products = catalog.products.ifEmpty { DemoRepository.products },
                orders = liveOrders,
                lastSyncEpochMs = System.currentTimeMillis(),
                error = null
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2_000)
                val currentToken = token ?: break
                runCatching { api.orders(currentToken) }
                    .onSuccess { fresh ->
                        val newInboundIds = fresh
                            .filter { it.id !in knownOrderIds && !it.channel.equals("POS", ignoreCase = true) }
                            .map { it.id }
                            .toSet()

                        if (newInboundIds.isNotEmpty()) {
                            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 240) }
                        }

                        knownOrderIds.addAll(fresh.map { it.id })
                        _ui.update { state ->
                            state.copy(
                                online = true,
                                orders = fresh.map { it.copy(unread = it.id in newInboundIds) },
                                unreadInbound = state.unreadInbound + newInboundIds.size,
                                lastSyncEpochMs = System.currentTimeMillis(),
                                error = null
                            )
                        }
                    }
                    .onFailure { error ->
                        _ui.update { it.copy(online = false, error = readableError(error)) }
                    }
            }
        }
    }

    private fun readableError(error: Throwable): String = when (error) {
        is CookitApiException -> when (error.statusCode) {
            401, 422 -> "Identifiants refusés ou session expirée."
            403 -> "Accès POS refusé pour ce compte ou ce forfait."
            404 -> "Endpoint Cookit introuvable (${error.statusCode})."
            else -> error.message ?: "Erreur API ${error.statusCode}"
        }
        else -> error.message ?: "Erreur réseau Cookit."
    }

    override fun onCleared() {
        pollingJob?.cancel()
        tone.release()
        super.onCleared()
    }
}
