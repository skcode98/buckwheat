package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.FamilySyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _inviteCode = MutableStateFlow("")
    val inviteCode: StateFlow<String> = _inviteCode.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _mintedInvite = MutableStateFlow<String?>(null)
    val mintedInvite: StateFlow<String?> = _mintedInvite.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.baseUrl()?.let { _serverUrl.value = it }
        }
    }

    fun onServerUrlChange(value: String) {
        _serverUrl.value = value
    }

    fun onDisplayNameChange(value: String) {
        _displayName.value = value
    }

    fun onInviteCodeChange(value: String) {
        _inviteCode.value = value.trim()
    }

    fun clearMintedInvite() {
        _mintedInvite.value = null
    }

    fun enrol() {
        val url = validatedUrl() ?: return
        val name = validatedName() ?: return
        run(
            action = { coordinator.enrol(baseUrl = url, displayName = name) },
            success = appContext.getString(R.string.family_sync_created),
        )
    }

    fun join() {
        val url = validatedUrl() ?: return
        val name = validatedName() ?: return
        val code = _inviteCode.value.trim()
        if (code.isEmpty()) {
            _messages.tryEmit(appContext.getString(R.string.family_sync_error_code))
            return
        }
        run(
            action = { coordinator.join(baseUrl = url, code = code, displayName = name) },
            success = appContext.getString(R.string.family_sync_joined),
        ) { _inviteCode.value = "" }
    }

    fun mintInvite() {
        if (session.value == null) {
            _messages.tryEmit(appContext.getString(R.string.family_sync_error_no_session))
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { coordinator.invite() }
                .onSuccess { minted ->
                    if (minted == null) {
                        _messages.emit(appContext.getString(R.string.family_sync_error_no_session))
                    } else {
                        _mintedInvite.value = minted.code
                    }
                }
                .onFailure { failure -> _messages.emit(describe(failure)) }
            _busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _busy.value = true
            _mintedInvite.value = null
            runCatching { coordinator.signOut() }
                .onSuccess { _messages.emit(appContext.getString(R.string.family_sync_signed_out)) }
                .onFailure { failure -> _messages.emit(describe(failure)) }
            _busy.value = false
        }
    }

    private fun validatedUrl(): String? {
        val url = _serverUrl.value.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _messages.tryEmit(appContext.getString(R.string.family_sync_error_url))
            return null
        }
        return url
    }

    private fun validatedName(): String? {
        val name = _displayName.value.trim()
        if (name.isEmpty()) {
            _messages.tryEmit(appContext.getString(R.string.family_sync_error_name))
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
                    _messages.emit(success)
                }
                .onFailure { failure -> _messages.emit(describe(failure)) }
            _busy.value = false
        }
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.family_sync_error_generic)
}
