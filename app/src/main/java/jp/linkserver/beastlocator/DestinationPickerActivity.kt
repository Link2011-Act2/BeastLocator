package jp.linkserver.beastlocator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import java.util.Locale

class DestinationPickerActivity : AppCompatActivity(), DestinationMapController.Listener {
    private lateinit var mapController: DestinationMapController
    private lateinit var selectedCoordinatesView: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var confirmButton: Button
    private var selectedDestination = Destination(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
    private var searchRequest: ForwardGeocoder.Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DestinationStore(this)
        if (!store.isExperimentalDestinationEditingEnabled()) {
            Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val initialDestination = readInitialDestination(store)
        selectedDestination = savedInstanceState?.let {
            Destination(
                it.getDouble(STATE_LATITUDE, initialDestination.lat),
                it.getDouble(STATE_LONGITUDE, initialDestination.lng)
            )
        }?.takeIf { it.isValidCoordinate() } ?: initialDestination

        setContentView(R.layout.activity_destination_picker)
        SystemBarInsetApplier.apply(findViewById(R.id.destinationPickerRoot))

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
        confirmButton.setOnClickListener { confirmSelectedDestination() }

        updateSelectedCoordinates()
        mapController = DestinationMapControllerFactory.create(this)
        mapController.attach(
            container = findViewById<FrameLayout>(R.id.destinationMapContainer),
            savedInstanceState = savedInstanceState,
            initialDestination = selectedDestination,
            initialZoom = DEFAULT_ZOOM,
            listener = this
        )
    }

    override fun onReady(destination: Destination) {
        if (!destination.isValidCoordinate()) return
        selectedDestination = destination
        updateSelectedCoordinates()
        confirmButton.isEnabled = true
    }

    override fun onMoveStarted() {
        confirmButton.isEnabled = false
    }

    override fun onMoveFinished(destination: Destination) {
        if (!destination.isValidCoordinate()) return
        selectedDestination = destination
        updateSelectedCoordinates()
        confirmButton.isEnabled = true
    }

    override fun onLoadFailed() {
        confirmButton.isEnabled = false
        Toast.makeText(this, R.string.destination_map_load_failed, Toast.LENGTH_LONG).show()
    }

    private fun confirmSelectedDestination() {
        if (!DestinationStore(this).isExperimentalDestinationEditingEnabled()) {
            Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        confirmButton.isEnabled = false
        mapController.readCenter { center ->
            if (isFinishing || isDestroyed) return@readCenter
            val destination = center?.takeIf { it.isValidCoordinate() } ?: selectedDestination
            val result = Intent()
                .putExtra(EXTRA_LATITUDE, destination.lat)
                .putExtra(EXTRA_LONGITUDE, destination.lng)
            setResult(Activity.RESULT_OK, result)
            finish()
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
            moveMapTo(results.first().destination)
        }
    }

    private fun moveMapTo(destination: Destination) {
        selectedDestination = destination
        updateSelectedCoordinates()
        confirmButton.isEnabled = false
        mapController.moveTo(destination, DEFAULT_ZOOM)
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
        if (::mapController.isInitialized) mapController.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapController.isInitialized) {
            mapController.onResume()
            if (!DestinationStore(this).isExperimentalDestinationEditingEnabled()) {
                Toast.makeText(this, R.string.destination_editing_disabled, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onPause() {
        if (::mapController.isInitialized) mapController.onPause()
        super.onPause()
    }

    override fun onStop() {
        searchRequest?.cancel()
        searchRequest = null
        if (::searchProgress.isInitialized) applySearchLoading(false)
        if (::mapController.isInitialized) mapController.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDouble(STATE_LATITUDE, selectedDestination.lat)
        outState.putDouble(STATE_LONGITUDE, selectedDestination.lng)
        if (::mapController.isInitialized) mapController.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapController.isInitialized) mapController.onLowMemory()
    }

    override fun onDestroy() {
        searchRequest?.cancel()
        searchRequest = null
        if (::mapController.isInitialized) mapController.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LATITUDE = "destination_picker_latitude"
        const val EXTRA_LONGITUDE = "destination_picker_longitude"
        private const val STATE_LATITUDE = "selected_latitude"
        private const val STATE_LONGITUDE = "selected_longitude"
        private const val DEFAULT_ZOOM = 15.0
        private const val DEFAULT_LATITUDE = 35.665554
        private const val DEFAULT_LONGITUDE = 139.669717
    }
}
