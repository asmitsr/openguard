package com.openguard.feature.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openguard.R
import com.openguard.core.common.relativeTime
import com.openguard.core.network.LocationDto
import com.openguard.ui.ErrorText
import com.openguard.ui.GuardButton
import com.openguard.ui.GuardCard
import com.openguard.ui.GuardTopBar
import com.openguard.ui.TintedIcon

@Composable
fun HistoryScreen(
    deviceId: String,
    deviceName: String,
    viewModel: TrackingViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.historyState.collectAsState()
    LaunchedEffect(deviceId) { viewModel.loadHistory(deviceId) }

    Scaffold(
        topBar = { GuardTopBar(title = "History — $deviceName", onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            if (state.isLoading && state.locations.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                ErrorText(error)
            }
            if (!state.isLoading && state.locations.isEmpty() && state.error == null) {
                Text("No locations recorded for this device yet.")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.locations, key = { it.id }) { location ->
                    LocationRow(location)
                }
            }
            if (state.nextBefore != null) {
                Spacer(Modifier.height(12.dp))
                GuardButton(
                    onClick = { viewModel.loadHistory(deviceId, loadMore = true) },
                    text = "Load more",
                    enabled = !state.isLoading,
                    loading = state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LocationRow(location: LocationDto) {
    GuardCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(R.drawable.ic_map_pin, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "%.5f, %.5f".format(location.latitude, location.longitude),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("±${location.accuracy.toInt()} m", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    relativeTime(location.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                location.provider?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


