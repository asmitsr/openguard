package com.openguard.feature.tracking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openguard.core.network.LocationDto
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * Raster style using OpenStreetMap tiles directly (no API key, no account).
 * The source carries the OSM attribution, which MapLibre shows in its
 * attribution control; a visible text attribution is also shown below
 * the map. For production use at scale, switch to a dedicated tile
 * provider — see the README.
 */
private const val OSM_STYLE_JSON = """
{
  "version": 8,
  "name": "OpenStreetMap",
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors",
      "maxzoom": 19
    }
  },
  "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
}
"""

private const val MARKER_SOURCE_ID = "marker-source"
private const val MARKER_LAYER_ID = "marker-layer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    deviceId: String,
    deviceName: String,
    viewModel: TrackingViewModel,
    onBack: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    val state by viewModel.mapState.collectAsState()
    LaunchedEffect(deviceId) { viewModel.loadLatest(deviceId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.padding(16.dp))
                    state.error != null -> Text(
                        state.error ?: "Could not load the location.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    state.latest == null -> Text(
                        "No locations recorded for this device yet.",
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> MapLibreView(location = state.latest)
                }
            }
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Button(
                onClick = onHistoryClick,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("View history")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * MapLibre MapView hosted in Compose via AndroidView, with the MapView
 * lifecycle forwarded from the Compose lifecycle owner.
 */
@Composable
private fun MapLibreView(location: LocationDto?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val lastAnimated = remember { mutableStateOf<LocationDto?>(null) }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                    destroyed = true
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // The observer only sees future events; catch up on the ones that
        // already happened before this composable entered the composition.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) mapView.onCreate(null)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    mapRef.value = map
                    map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                        style.addSource(GeoJsonSource(MARKER_SOURCE_ID))
                        style.addLayer(
                            CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                                PropertyFactory.circleRadius(8f),
                                PropertyFactory.circleColor(android.graphics.Color.RED),
                                PropertyFactory.circleStrokeWidth(2f),
                                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                            ),
                        )
                    }
                }
            }
        },
        update = {
            val map = mapRef.value
            val latest = location
            if (map != null && latest != null && latest != lastAnimated.value) {
                lastAnimated.value = latest
                map.getStyle { style ->
                    (style.getSource(MARKER_SOURCE_ID) as? GeoJsonSource)
                        ?.setGeoJson(Point.fromLngLat(latest.longitude, latest.latitude))
                }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(latest.latitude, latest.longitude), 15.0,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
