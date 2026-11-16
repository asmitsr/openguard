package com.openguard.feature.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openguard.core.network.LocationDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = { Text("History — $deviceName") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (state.isLoading && state.locations.isEmpty()) {
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (!state.isLoading && state.locations.isEmpty() && state.error == null) {
                Text("No locations recorded for this device yet.")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.locations, key = { it.id }) { location ->
                    LocationRow(location)
                }
            }
            if (state.nextBefore != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.loadHistory(deviceId, loadMore = true) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load more")
                }
            }
        }
    }
}

@Composable
private fun LocationRow(location: LocationDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "%.5f, %.5f".format(location.latitude, location.longitude),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("±${location.accuracy.toInt()} m", style = MaterialTheme.typography.bodySmall)
            }
            Text(formatTime(location.recordedAt), style = MaterialTheme.typography.bodySmall)
            location.provider?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

private fun formatTime(iso: String): String = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFormatter)
} catch (e: Exception) {
    iso
}
