package com.suzuri.lmdroid.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** [address] is a best-effort reverse-geocoded line (e.g. "東京都千代田区...") — null when offline or unsupported; the coordinates alone are still useful to the model in that case. */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
)

/**
 * Fetches the device's current location for the "get_current_location" tool (see
 * ConversationRepository) — plain android.location APIs rather than Google Play Services'
 * FusedLocationProviderClient, since this project doesn't otherwise depend on Play Services.
 * Prefers a fresh fix but falls back to the last-known location (see [DeviceLocationProvider]'s
 * private requestLocation()) when one can't be obtained in time — GPS commonly can't complete
 * within [LOCATION_TIMEOUT_MS] indoors, and this device may have no other provider registered.
 * Returns null (never throws) only when a location genuinely can't be obtained at all: permission
 * not granted, no provider enabled, or no fix (fresh or last-known) exists — the caller feeds that
 * back to the model as "unavailable" so the reply doesn't fail outright over something as
 * inherently flaky as a location fix.
 */
class DeviceLocationProvider(private val context: Context) {

    suspend fun getCurrentLocation(): DeviceLocation? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "getCurrentLocation: no location permission granted")
            return null
        }
        val location = requestLocation()
        if (location == null) {
            Log.w(TAG, "getCurrentLocation: no fix obtained (disabled provider, no signal, or timed out)")
            return null
        }
        return DeviceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            address = reverseGeocode(location.latitude, location.longitude),
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * A fresh GPS fix commonly can't be obtained within [LOCATION_TIMEOUT_MS] at all — GPS needs a
     * relatively clear sky view, which most indoor use (the common case for a chat app) doesn't
     * have, and this project deliberately avoids Play Services' FusedLocationProviderClient (see
     * the class doc comment), so there's no network/wifi-based fallback provider on many modern
     * devices, where the legacy LocationManager.NETWORK_PROVIDER is often no longer registered at
     * all. So: try [LocationManager.getLastKnownLocation] first (instant, no timeout needed) and
     * return it immediately if recent enough; otherwise attempt a fresh fix, but fall back to that
     * same last-known location (even if stale) if the fresh attempt times out — approximate-but-
     * present beats failing outright.
     */
    private suspend fun requestLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.w(TAG, "requestLocation: no LocationManager system service")
            return null
        }
        val provider = bestProvider(locationManager)
        if (provider == null) {
            Log.w(TAG, "requestLocation: no usable provider is enabled (is Location turned on in system settings?)")
            return null
        }

        val lastKnown = runCatching { locationManager.getLastKnownLocation(provider) }
            .onFailure { e -> Log.w(TAG, "getLastKnownLocation failed", e) }
            .getOrNull()
        val lastKnownAgeMs = lastKnown?.let { System.currentTimeMillis() - it.time }
        if (lastKnown != null && lastKnownAgeMs != null && lastKnownAgeMs in 0..FRESH_ENOUGH_MS) {
            Log.d(TAG, "requestLocation: using last-known fix from $provider (${lastKnownAgeMs}ms old)")
            return lastKnown
        }

        Log.d(TAG, "requestLocation: requesting a fresh update from $provider")
        val freshLocation = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { awaitSingleUpdate(locationManager, provider) }
        if (freshLocation != null) return freshLocation

        if (lastKnown != null) {
            Log.d(TAG, "requestLocation: fresh fix unavailable, falling back to stale last-known (${lastKnownAgeMs}ms old)")
        }
        return lastKnown
    }

    @Suppress("DEPRECATION") // requestSingleUpdate has no replacement usable down to minSdk 26 (getCurrentLocation() is API 30+).
    private suspend fun awaitSingleUpdate(locationManager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = LocationListener { location ->
                Log.d(TAG, "requestLocation: got a fresh fix from $provider")
                if (continuation.isActive) continuation.resume(location)
            }
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Permission was checked just above, but could theoretically be revoked in the
                // gap before this call actually reaches the system service.
                Log.w(TAG, "requestSingleUpdate denied", e)
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
        }

    /**
     * Prefers GPS for precision, falling back to network-based positioning (faster indoors/
     * without a clear sky view) — whichever the user actually has enabled. GPS_PROVIDER requires
     * ACCESS_FINE_LOCATION specifically: on Android 12+, choosing "Approximate" in the permission
     * dialog grants only ACCESS_COARSE_LOCATION even though fine was requested, and asking
     * LocationManager for GPS_PROVIDER in that state throws SecurityException rather than just
     * being less precise — this was silently swallowing every request down to "unavailable" for
     * anyone who picked approximate.
     */
    private fun bestProvider(locationManager: LocationManager): String? {
        val enabledProviders = locationManager.getProviders(true)
        Log.d(TAG, "bestProvider: enabled=$enabledProviders, hasFine=$hasFineLocationPermission")
        if (hasFineLocationPermission && enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER
        }
        return enabledProviders.firstOrNull { it == LocationManager.NETWORK_PROVIDER }
    }

    private val hasFineLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION") // The synchronous overload is deprecated since API 33; the callback-based replacement needs API 33+, below this project's minSdk.
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.onFailure { e -> Log.w(TAG, "Reverse geocoding failed", e) }.getOrNull()
    }

    private companion object {
        const val TAG = "DeviceLocationProvider"
        const val LOCATION_TIMEOUT_MS = 10_000L
        const val FRESH_ENOUGH_MS = 5 * 60 * 1000L
    }
}
