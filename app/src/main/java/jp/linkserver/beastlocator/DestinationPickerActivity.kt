package jp.linkserver.beastlocator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.Locale

class DestinationPickerActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var selectedCoordinatesView: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var confirmButton: Button
    private var map: MapLibreMap? = null
    private var selectedDestination = Destination(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
    private var searchRequest: ForwardGeocoder.Request? = null
    private var restoreMapCamera = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DestinationStore(this)
        if (!store.isExperimentalDestinationEditingEnabled()) {
            Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val initialDestination = readInitialDestination(store)
        restoreMapCamera = savedInstanceState?.getBoolean(STATE_HAS_CAMERA, false) == true
        selectedDestination = savedInstanceState?.let {
            Destination(
                it.getDouble(STATE_LATITUDE, initialDestination.lat),
                it.getDouble(STATE_LONGITUDE, initialDestination.lng)
            )
        }?.takeIf { it.isValidCoordinate() } ?: initialDestination

        MapLibre.getInstance(applicationContext)
        MapHttpConfiguration.configureOnce()
        setContentView(R.layout.activity_destination_picker)
        SystemBarInsetApplier.apply(findViewById(R.id.destinationPickerRoot))

        mapView = findViewById(R.id.destinationMapView)
        selectedCoordinatesView = findViewById(R.id.selectedCoordinatesView)
        searchInput = findViewById(R.id.destinationSearchInput)
        searchButton = findViewById(R.id.destinationSearchButton)
        searchProgress = findViewById(R.id.destinationSearchProgress)
        confirmButton = findViewById(R.id.confirmDestinationButton)

        findViewById<ImageButton>(R.id.destinationPickerBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.currentLocationButton).setOnClickListener {
            val current = DestinationStore(this).getLastKnownLocation()
            if (current == null) {
                Toast.makeText(
                    this,
                    R.string.destination_current_location_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                moveMapTo(current)
            }
        }
        findViewById<TextView>(R.id.osmAttributionView).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.osm_attribution_url).toUri()))
            }.onFailure {
                Toast.makeText(this, R.string.external_link_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        searchButton.setOnClickListener { searchForPlace() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchForPlace()
                true
            } else {
                false
            }
        }
        confirmButton.isEnabled = false
        confirmButton.setOnClickListener {
            if (!DestinationStore(this).isExperimentalDestinationEditingEnabled()) {
                Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
                finish()
                return@setOnClickListener
            }
            val target = map?.cameraPosition?.target
            val destination = target?.let { Destination(it.latitude, it.longitude) }
                ?.takeIf { it.isValidCoordinate() }
                ?: selectedDestination
            val result = Intent()
                .putExtra(EXTRA_LATITUDE, destination.lat)
                .putExtra(EXTRA_LONGITUDE, destination.lng)
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        updateSelectedCoordinates()
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(::configureMap)
    }

    private fun configureMap(loadedMap: MapLibreMap) {
        map = loadedMap
        loadedMap.setPrefetchZoomDelta(0)

        val tileSet = TileSet(MAP_TILESET_VERSION, getString(R.string.osm_tile_url)).apply {
            scheme = "xyz"
            attribution = getString(R.string.osm_attribution_html)
            setMinZoom(0f)
            setMaxZoom(19f)
        }
        val source = RasterSource(OSM_SOURCE_ID, tileSet, 256).apply {
            setVolatile(false)
        }
        loadedMap.setStyle(
            Style.Builder()
                .withSource(source)
                .withLayer(RasterLayer(OSM_LAYER_ID, OSM_SOURCE_ID))
        ) {
            if (!restoreMapCamera) {
                loadedMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(selectedDestination.lat, selectedDestination.lng))
                    .zoom(DEFAULT_ZOOM)
                    .build()
            }
            confirmButton.isEnabled = true
        }
        loadedMap.addOnCameraIdleListener {
            val target = loadedMap.cameraPosition.target ?: return@addOnCameraIdleListener
            val candidate = Destination(target.latitude, target.longitude)
            if (candidate.isValidCoordinate()) {
                selectedDestination = candidate
                updateSelectedCoordinates()
                confirmButton.isEnabled = true
            }
        }
        loadedMap.addOnCameraMoveStartedListener {
            confirmButton.isEnabled = false
        }
    }

    private fun searchForPlace() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            searchInput.error = getString(R.string.destination_search_empty)
            searchInput.requestFocus()
            return
        }
        searchInput.error = null
        hideKeyboard()
        searchRequest?.cancel()
        applySearchLoading(true)
        searchRequest = ForwardGeocoder.searchAsync(this, query) { outcome ->
            if (isFinishing || isDestroyed) return@searchAsync
            searchRequest = null
            applySearchLoading(false)
            val results = when (outcome) {
                PlaceSearchOutcome.Failed,
                PlaceSearchOutcome.Unavailable -> {
                    Toast.makeText(this, R.string.destination_search_failed, Toast.LENGTH_LONG).show()
                    return@searchAsync
                }

                is PlaceSearchOutcome.Success -> outcome.places
            }
            if (results.isEmpty()) {
                Toast.makeText(this, R.string.destination_search_no_results, Toast.LENGTH_LONG).show()
                return@searchAsync
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.destination_search_results_title)
                .setItems(results.map { it.label }.toTypedArray()) { _, index ->
                    results.getOrNull(index)?.let { moveMapTo(it.destination) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun moveMapTo(destination: Destination) {
        selectedDestination = destination
        updateSelectedCoordinates()
        confirmButton.isEnabled = false
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(destination.lat, destination.lng),
                DEFAULT_ZOOM
            )
        )
    }

    private fun applySearchLoading(loading: Boolean) {
        searchButton.isEnabled = !loading
        searchInput.isEnabled = !loading
        searchProgress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateSelectedCoordinates() {
        selectedCoordinatesView.text = getString(
            R.string.destination_selected_format,
            formatCoordinate(selectedDestination.lat),
            formatCoordinate(selectedDestination.lng)
        )
    }

    private fun readInitialDestination(store: DestinationStore): Destination {
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        return Destination(latitude, longitude).takeIf { it.isValidCoordinate() }
            ?: store.getUserDestination()
            ?: store.getDefaultDestination()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
            if (!DestinationStore(this).isExperimentalDestinationEditingEnabled()) {
                Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        searchRequest?.cancel()
        searchRequest = null
        if (::searchProgress.isInitialized) applySearchLoading(false)
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        map?.cameraPosition?.target?.let { target ->
            val cameraDestination = Destination(target.latitude, target.longitude)
            if (cameraDestination.isValidCoordinate()) {
                selectedDestination = cameraDestination
            }
        }
        outState.putDouble(STATE_LATITUDE, selectedDestination.lat)
        outState.putDouble(STATE_LONGITUDE, selectedDestination.lng)
        if (::mapView.isInitialized) {
            outState.putBoolean(STATE_HAS_CAMERA, map != null)
            mapView.onSaveInstanceState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroy() {
        searchRequest?.cancel()
        searchRequest = null
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LATITUDE = "destination_picker_latitude"
        const val EXTRA_LONGITUDE = "destination_picker_longitude"
        private const val STATE_LATITUDE = "selected_latitude"
        private const val STATE_LONGITUDE = "selected_longitude"
        private const val STATE_HAS_CAMERA = "has_saved_map_camera"
        private const val OSM_SOURCE_ID = "osm-source"
        private const val OSM_LAYER_ID = "osm-layer"
        private const val MAP_TILESET_VERSION = "2.2.0"
        private const val DEFAULT_ZOOM = 15.0
        private const val DEFAULT_LATITUDE = 35.665554
        private const val DEFAULT_LONGITUDE = 139.669717
    }
}
