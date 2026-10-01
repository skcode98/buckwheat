package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.FamilySyncCoordinator
import com.danilkinkin.buckwheat.sync.syncBaseUrlStoreKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SERVER_URL_PERSIST_DELAY_MS = 400L

@HiltViewModel
class FamilySyncViewModel @Inject constructor(
    private val coordinator: FamilySyncCoordinator,
    private val sessionStore: FamilySessionStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val session: StateFlow<FamilySession?> = sessionStore.session()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _memberName = MutableStateFlow("")
    val memberName: StateFlow<String> = _memberName.asStateFlow()

    private val _inviteCode = MutableStateFlow("")
    val inviteCode: StateFlow<String> = _inviteCode.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = Channel<String>(Channel.CONFLATED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _mintedInvite = MutableStateFlow<String?>(null)
    val mintedInvite: StateFlow<String?> = _mintedInvite.asStateFlow()

    private val _mintedInviteExpiresAt = MutableStateFlow<String?>(null)
    val mintedInviteExpiresAt: StateFlow<String?> = _mintedInviteExpiresAt.asStateFlow()

    private var persistServerUrlJob: Job? = null

    init {
        viewModelScope.launch { prefillServerUrl() }
        viewModelScope.launch {
            session
                .filterNotNull()
                .map { it.memberId }
                .distinctUntilChanged()
                .collect { refreshMemberName() }
        }
    }

    fun onServerUrlChange(value: String) {
        _serverUrl.value = value
        persistServerUrlJob?.cancel()
        persistServerUrlJob = viewModelScope.launch {
            delay(SERVER_URL_PERSIST_DELAY_MS)
            sessionStore.setBaseUrl(value)
        }
    }

    fun onDisplayNameChange(value: String) {
        _displayName.value = value
    }

    fun onInviteCodeChange(value: String) {
        _inviteCode.value = value.trim()
    }

    fun clearMintedInvite() {
        _mintedInvite.value = null
        _mintedInviteExpiresAt.value = null
    }

    fun enrol() {
        if (_busy.value) return
        val url = validatedUrl() ?: return
        val name = validatedName() ?: return
        rememberServerUrl(url)
        run(
            action = { coordinator.enrol(baseUrl = url, displayName = name) },
            success = appContext.getString(R.string.family_sync_created),
        )
    }

    fun join() {
        if (_busy.value) return
        val url = validatedUrl() ?: return
        val name = validatedName() ?: return
        val code = _inviteCode.value.trim()
        if (code.isEmpty()) {
            _messages.trySend(appContext.getString(R.string.family_sync_error_code))
            return
        }
        rememberServerUrl(url)
        run(
            action = { coordinator.join(baseUrl = url, code = code, displayName = name) },
            success = appContext.getString(R.string.family_sync_joined),
        ) { _inviteCode.value = "" }
    }

    fun mintInvite() {
        if (_busy.value) return
        if (session.value == null) {
            _messages.trySend(appContext.getString(R.string.family_sync_error_no_session))
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { coordinator.invite() }
                .onSuccess { minted ->
                    if (minted == null) {
                        _messages.send(appContext.getString(R.string.family_sync_error_no_session))
                    } else {
                        _mintedInvite.value = minted.code
                        _mintedInviteExpiresAt.value = minted.expiresAt
                    }
                }
                .onFailure { failure -> _messages.send(describe(failure)) }
            _busy.value = false
        }
    }

    fun signOut() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            clearMintedInvite()
            _memberName.value = ""
            runCatching { coordinator.signOut() }
                .onSuccess { _messages.send(appContext.getString(R.string.family_sync_signed_out)) }
                .onFailure { failure -> _messages.send(describe(failure)) }
            _busy.value = false
        }
    }

    private suspend fun prefillServerUrl() {
        if (_serverUrl.value.isNotBlank()) return
        val url = sessionStore.baseUrl() ?: storedBaseUrl() ?: return
        if (url.isNotBlank()) _serverUrl.value = url
    }

    private suspend fun storedBaseUrl(): String? = try {
        appContext.settingsDataStore.data.first()[syncBaseUrlStoreKey]
    } catch (e: Exception) {
        null
    }

    private fun rememberServerUrl(url: String) {
        persistServerUrlJob?.cancel()
        persistServerUrlJob = viewModelScope.launch { sessionStore.setBaseUrl(url) }
    }

    private suspend fun refreshMemberName() {
        _memberName.value = runCatching { coordinator.whoami()?.displayName }.getOrNull().orEmpty()
    }

    private fun validatedUrl(): String? {
        val url = _serverUrl.value.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _messages.trySend(appContext.getString(R.string.family_sync_error_url))
            return null
        }
        return url
    }

    private fun validatedName(): String? {
        val name = _displayName.value.trim()
        if (name.isEmpty()) {
            _messages.trySend(appContext.getString(R.string.family_sync_error_name))
            return null
        }
        return name
    }

    private fun run(
        action: suspend () -> Any?,
        success: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { action() }
                .onSuccess {
                    onSuccess()
                    _messages.send(success)
                }
                .onFailure { failure -> _messages.send(describe(failure)) }
            _busy.value = false
        }
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.family_sync_error_generic)
}