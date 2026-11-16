import { useEffect, useRef } from 'react'
import type { Device, Location } from './api'

function circleFeature(lat: number, lon: number, meters: number) {
  const pts: [number, number][] = []
  const R = 6378137
  for (let i = 0; i <= 64; i++) {
    const a = (i / 64) * 2 * Math.PI
    const dLat = (meters / R) * Math.cos(a)
    const dLon = (meters / (R * Math.cos((lat * Math.PI) / 180))) * Math.sin(a)
    pts.push([lon + (dLon * 180) / Math.PI, lat + (dLat * 180) / Math.PI])
  }
  return { type: 'Feature', geometry: { type: 'Polygon', coordinates: [pts] } }
}

export default function MapPanel({
  devices,
  selected,
  historyLocs,
  onSelect,
}: {
  devices: Device[]
  selected: string | null
  historyLocs: Location[]
  onSelect: (id: string) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<any>(null)
  const markersRef = useRef<Record<string, any>>({})
  const readyRef = useRef(false)
  const stateRef = useRef({ devices, selected, historyLocs, onSelect })
  stateRef.current = { devices, selected, historyLocs, onSelect }

  function drawMarkers() {
    if (!readyRef.current) return
    Object.values(markersRef.current).forEach((m) => m.remove())
    markersRef.current = {}
    stateRef.current.devices.forEach((d) => {
      if (!d._latest) return
      const m = new maplibregl.Marker().setLngLat([d._latest.longitude, d._latest.latitude]).addTo(mapRef.current)
      m.getElement().addEventListener('click', () => stateRef.current.onSelect(d.id))
      markersRef.current[d.id] = m
    })
  }

  function drawAccuracy(loc?: Location | null) {
    if (!readyRef.current) return
    const feats = loc ? [circleFeature(loc.latitude, loc.longitude, loc.accuracy || 50)] : []
    mapRef.current.getSource('accuracy').setData({ type: 'FeatureCollection', features: feats })
  }

  function drawPath() {
    if (!readyRef.current) return
    const coords = stateRef.current.historyLocs.map((l) => [l.longitude, l.latitude])
    const feats =
      coords.length > 1 ? [{ type: 'Feature', geometry: { type: 'LineString', coordinates: coords } }] : []
    mapRef.current.getSource('path').setData({ type: 'FeatureCollection', features: feats })
  }

  // Init once, on mount.
  useEffect(() => {
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: {
        version: 8,
        sources: {
          osm: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '© OpenStreetMap contributors',
          },
        },
        layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
      },
      center: [90.4, 23.8],
      zoom: 5,
    })
    map.addControl(new maplibregl.NavigationControl())
    map.addControl(new maplibregl.AttributionControl({ compact: true }))
    map.on('load', () => {
      map.addSource('accuracy', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      map.addLayer({ id: 'accuracy', type: 'fill', source: 'accuracy', paint: { 'fill-color': '#1a5fb4', 'fill-opacity': 0.15 } })
      map.addSource('path', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      map.addLayer({ id: 'path', type: 'line', source: 'path', paint: { 'line-color': '#1a5fb4', 'line-width': 2 } })
      readyRef.current = true
      drawMarkers()
      // A device may already be selected (and history loaded) before the map
      // finished loading; those draws no-op'd on ready, so redo them now.
      const sel = stateRef.current.devices.find((x) => x.id === stateRef.current.selected)
      if (sel && sel._latest) drawAccuracy(sel._latest)
      drawPath()
    })
    mapRef.current = map
    return () => map.remove()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => drawMarkers(), [devices])
  useEffect(() => drawPath(), [historyLocs])

  useEffect(() => {
    const d = devices.find((x) => x.id === selected)
    if (d && d._latest && mapRef.current) {
      mapRef.current.flyTo({ center: [d._latest.longitude, d._latest.latitude], zoom: 14 })
      drawAccuracy(d._latest)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected])

  return (
    <section className="card">
      <h2>Map</h2>
      <div id="map" ref={containerRef}></div>
      <p className="muted">Map data © OpenStreetMap contributors. Select a device to centre it and see its accuracy circle.</p>
    </section>
  )
}
