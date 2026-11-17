package com.openguard.feature.geofence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.network.GeofenceDto
import com.openguard.core.network.GeofenceEventDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardOutlinedButton
import com.openguard.ui.GuardTopBar
import com.openguard.ui.SectionHeader
import com.openguard.ui.TintedIcon
import kotlinx.coroutines.launch

/**
 * Geofences for one device: the list (tap to edit, trash icon to
 * delete), the add/edit form, and recent enter/exit events. New
 * geofences are centred on the last known location — there is no
 * map-tap picker, by design.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeofenceScreen(
    deviceId: String,
    deviceName: String,
    viewModel: GeofenceViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GeofenceDto?>(null) }
    var geofenceToDelete by remember { mutableStateOf<GeofenceDto?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(500f) }
    var action by remember { mutableStateOf(GeofenceViewModel.ACTION_NOTIFY) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(deviceId) { viewModel.load(deviceId) }

    fun openAddForm() {
        editing = null
        name = ""
        radius = 500f
        action = GeofenceViewModel.ACTION_NOTIFY
        showForm = true
    }

    fun openEditForm(geofence: GeofenceDto) {
        editing = geofence
        name = geofence.name
        radius = geofence.radiusM.toFloat()
        action = geofence.action
        showForm = true
    }

    Scaffold(
        topBar = { GuardTopBar(title = "Geofences — $deviceName", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isLoading && state.geofences.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { ErrorText(it) }

            // ---- Geofence list ----
            SectionHeader(iconRes = R.drawable.ic_radar, title = stringResource(R.string.geofence_title))
            if (state.geofences.isEmpty() && !state.isLoading) {
                Text(
                    stringResource(R.string.geofence_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.geofences.forEach { geofence ->
                GeofenceRow(
                    geofence = geofence,
                    inside = state.inside[geofence.id],
                    onClick = { openEditForm(geofence) },
                    onDeleteClick = { geofenceToDelete = geofence },
                )
            }
            if (!showForm) {
                GuardOutlinedButton(
                    onClick = ::openAddForm,
                    text = stringResource(R.string.geofence_add_title),
                    iconRes = R.drawable.ic_plus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Add / edit form ----
            if (showForm) {
                GuardCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionHeader(
                            iconRes = R.drawable.ic_map_pin,
                            title = stringResource(
                                if (editing == null) R.string.geofence_add_title
                                else R.string.geofence_edit_title,
                            ),
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                if (it.length <= GeofenceViewModel.NAME_MAX_LENGTH) name = it
                            },
                            label = { Text(stringResource(R.string.geofence_name_label)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.geofence_radius_label, radius.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = GeofenceViewModel.RADIUS_MIN_M.toFloat()..
                                GeofenceViewModel.RADIUS_MAX_M.toFloat(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GeofenceActionOption(
                                value = GeofenceViewModel.ACTION_NOTIFY,
                                labelRes = R.string.geofence_action_notify,
                                selected = action,
                                onSelect = { action = it },
                            )
                            GeofenceActionOption(
                                value = GeofenceViewModel.ACTION_LOCK,
                                labelRes = R.string.geofence_action_lock,
                                selected = action,
                                onSelect = { action = it },
                            )
                            GeofenceActionOption(
                                value = GeofenceViewModel.ACTION_LOST_MODE,
                                labelRes = R.string.geofence_action_lost,
                                selected = action,
                                onSelect = { action = it },
                            )
                        }
                        Text(
                            when (action) {
                                GeofenceViewModel.ACTION_LOCK ->
                                    stringResource(R.string.geofence_action_lock_desc)
                                GeofenceViewModel.ACTION_LOST_MODE ->
                                    stringResource(R.string.geofence_action_lost_desc)
                                else -> stringResource(R.string.geofence_action_notify_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val lastKnown = state.lastKnown
                        if (lastKnown == null) {
                            ErrorText(stringResource(R.string.geofence_no_location))
                        } else {
                            Text(
                                stringResource(
                                    R.string.geofence_center,
                                    "%.4f".format(lastKnown.latitude),
                                    "%.4f".format(lastKnown.longitude),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GuardButton(
                            onClick = {
                                saving = true
                                scope.launch {
                                    val target = editing
                                    val ok = if (target == null) {
                                        viewModel.create(name, radius.toInt(), action)
                                    } else {
                                        viewModel.update(target.id, name, radius.toInt(), action)
                                    }
                                    saving = false
                                    if (ok) {
                                        showForm = false
                                        editing = null
                                    }
                                }
                            },
                            text = stringResource(
                                if (editing == null) R.string.geofence_save
                                else R.string.geofence_update,
                            ),
                            loading = saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { showForm = false; editing = null }) {
                            Text(stringResource(R.string.geofence_cancel))
                        }
                    }
                }
            }

            // ---- Recent events ----
            SectionHeader(
                iconRes = R.drawable.ic_history,
                title = stringResource(R.string.geofence_events_title),
            )
            if (state.events.isEmpty()) {
                Text(
                    stringResource(R.string.geofence_events_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.events.forEach { event ->
                GeofenceEventRow(event)
            }

            GuardButton(
                onClick = { viewModel.refresh() },
                text = stringResource(R.string.geofence_refresh),
                loading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    geofenceToDelete?.let { geofence ->
        AlertDialog(
            onDismissRequest = { geofenceToDelete = null },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_trash_2),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.geofence_delete_title)) },
            text = { Text(stringResource(R.string.geofence_delete_text, geofence.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(geofence.id)
                        geofenceToDelete = null
                    },
                ) { Text(stringResource(R.string.geofence_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { geofenceToDelete = null }) {
                    Text(stringResource(R.string.geofence_cancel))
                }
            },
        )
    }
}

@Composable
private fun GeofenceRow(
    geofence: GeofenceDto,
    inside: Boolean?,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    GuardCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(R.drawable.ic_radar, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(geofence.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.geofence_row_detail,
                        geofence.radiusM,
                        actionLabel(geofence.action),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (inside) {
                        true -> stringResource(R.string.geofence_inside)
                        false -> stringResource(R.string.geofence_outside)
                        null -> stringResource(R.string.geofence_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (inside) {
                        true -> MaterialTheme.colorScheme.tertiary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    painterResource(R.drawable.ic_trash_2),
                    contentDescription = stringResource(R.string.geofence_delete),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GeofenceActionOption(
    value: String,
    labelRes: Int,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun GeofenceEventRow(event: GeofenceEventDto) {
    val name = event.geofenceName ?: stringResource(R.string.geofence_title)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            when (event.transition) {
                "enter" -> stringResource(R.string.geofence_entered, name)
                "exit" -> stringResource(R.string.geofence_left, name)
                else -> "${event.transition} $name"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            relativeTime(event.recordedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Plain-language action for the row detail line; unknown values pass through. */
@Composable
private fun actionLabel(action: String): String = when (action) {
    GeofenceViewModel.ACTION_NOTIFY -> stringResource(R.string.geofence_action_notify_on_exit)
    GeofenceViewModel.ACTION_LOCK -> stringResource(R.string.geofence_action_lock_on_exit)
    GeofenceViewModel.ACTION_LOST_MODE -> stringResource(R.string.geofence_action_lost_on_exit)
    else -> action
}
