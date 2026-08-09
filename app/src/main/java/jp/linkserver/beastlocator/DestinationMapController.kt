package jp.linkserver.beastlocator

import android.os.Bundle
import android.widget.FrameLayout

internal interface DestinationMapController {
    interface Listener {
        fun onReady(destination: Destination)
        fun onMoveStarted()
        fun onMoveFinished(destination: Destination)
        fun onLoadFailed()
    }

    fun attach(
        container: FrameLayout,
        savedInstanceState: Bundle?,
        initialDestination: Destination,
        initialZoom: Double,
        listener: Listener
    )

    fun moveTo(destination: Destination, zoom: Double)

    fun readCenter(onResult: (Destination?) -> Unit)

    fun onStart() = Unit
    fun onResume() = Unit
    fun onPause() = Unit
    fun onStop() = Unit
    fun onSaveInstanceState(outState: Bundle) = Unit
    fun onLowMemory() = Unit
    fun onDestroy() = Unit
}
