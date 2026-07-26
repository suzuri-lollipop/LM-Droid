package com.suzuri.lmdroid.data.location

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric so android.util.Log calls inside the provider are shadowed rather than throwing,
// and so a real (permission-less by default) Context is available.
@RunWith(RobolectricTestRunner::class)
class DeviceLocationProviderTest {

    @Test
    fun `getCurrentLocation returns null when location permission has not been granted`() = runTest {
        // Robolectric's default test environment starts with no runtime permissions granted,
        // matching a fresh install that hasn't gone through the Settings toggle's permission
        // prompt yet — this must fail closed (null), never throw a SecurityException up to the
        // tool-calling loop in ConversationRepository.
        val provider = DeviceLocationProvider(ApplicationProvider.getApplicationContext())

        val location = provider.getCurrentLocation()

        assertNull(location)
    }
}
