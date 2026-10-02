package com.danilkinkin.buckwheat.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilyMembersCache
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chooses whose budget a spend is recorded against, for a shared device.
 *
 * Deliberately not a stored setting. On a family tablet "who is at the keyboard" changes minute to
 * minute, so a device-level default would be wrong as soon as somebody else sat down, and a spend
 * recorded against the wrong person is worse than no spend at all — it quietly moves money between
 * people in a ledger they can see. A null choice means "whoever is signed in", which is the common
 * case and the one everyone has already agreed to attribute automatically.
 *
 * Deliberately per-compose and not persisted anywhere. It is a statement about one keystroke, not a
 * claim about the device.
 */
@HiltViewModel
class ActingAsViewModel @Inject constructor(
    sessionStore: FamilySessionStore,
    private val membersCache: FamilyMembersCache,
) : ViewModel() {

    /**
     * The member whose budget new spends land on, or null for the signed-in one.
     *
     * Reset to null whenever the session changes. A family change invalidates the choice: somebody
     * who is no longer a member must not stay selected, or the next spend would be attributed to a
     * stranger and would sync that way to everyone.
     */
    private val _actingAs: MutableStateFlow<String?> = MutableStateFlow(null)

    val actingAs: StateFlow<String?> = _actingAs

    init {
        viewModelScope.launch {
            sessionStore.session()
                .map { it?.memberId }
                .collect { _actingAs.value = null }
        }
    }

    /** Everyone except the signed-in member, who records their own spends without choosing. */
    val candidates: StateFlow<List<FamilyMember>> =
        combine(sessionStore.session(), membersCache.members()) { session, roster ->
            val me = session?.memberId
            roster.filter { it.id != me }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * False when there is nobody to act as, so the control is hidden rather than shown disabled.
     *
     * A single-member family has no shared-device problem, and a greyed-out row would imply one.
     */
    val hasChoices: StateFlow<Boolean> =
        candidates.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun select(memberId: String?) {
        _actingAs.value = memberId
    }

    /** The member id to pass to `addSpent`, or null to attribute to the signed-in member. */
    fun memberIdOrNull(): String? = _actingAs.value
}