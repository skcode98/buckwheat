package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.DescriptionButton
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel

const val FAMILY_SYNC_SHEET = "familySync"

@Composable
fun FamilySyncSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    viewModel: FamilySyncViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    val session by viewModel.session.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val inviteCode by viewModel.inviteCode.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val mintedInvite by viewModel.mintedInvite.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> appViewModel.showSnackbar(message) }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.family_sync_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navigationBarHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = session
                if (current == null) {
                    Text(
                        text = stringResource(R.string.family_sync_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = viewModel::onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_server_url)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                        ),
                    )

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = viewModel::onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_display_name)) },
                        singleLine = true,
                        enabled = !busy,
                    )

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_create)) },
                        description = {
                            Text(stringResource(R.string.family_sync_create_description))
                        },
                        onClick = viewModel::enrol,
                    )

                    Text(
                        text = stringResource(R.string.family_sync_or_join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = viewModel::onInviteCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_invite_code)) },
                        singleLine = true,
                        enabled = !busy,
                    )

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_join)) },
                        description = {
                            Text(stringResource(R.string.family_sync_join_description))
                        },
                        onClick = viewModel::join,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.family_sync_connected),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.family_sync_family, current.familyId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.family_sync_member, current.memberId),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    mintedInvite?.let { code ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.family_sync_invite_created, code),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        DescriptionButton(
                            title = { Text(stringResource(R.string.family_sync_dismiss_invite)) },
                            onClick = viewModel::clearMintedInvite,
                        )
                    }

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_create_invite)) },
                        description = {
                            Text(stringResource(R.string.family_sync_create_invite_description))
                        },
                        onClick = viewModel::mintInvite,
                    )

                    Spacer(Modifier.height(8.dp))

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_sign_out)) },
                        description = {
                            Text(stringResource(R.string.family_sync_sign_out_description))
                        },
                        onClick = viewModel::signOut,
                    )
                }
            }
        }
    }
}
