package io.github.zapretkvn.android.network

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.network.geo.DohHostAddressResolver
import io.github.zapretkvn.android.network.geo.GeoIpCountry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerCountryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedGeoIpAssetLoadsThroughAssetManager() {
        val geo = GeoIpCountry { context.assets.open("geoip/country.bin") }
        assertTrue(geo.databaseId.startsWith("zgc1-"))
        assertEquals("US", geo.lookupLiteral("8.8.8.8"))
        assertEquals("RU", geo.lookupLiteral("[2a02:6b8::feed:ff]"))
        assertNull(geo.lookupLiteral("192.168.1.1"))
    }

    @Test
    fun dohResolverFindsAServerNameWithoutSystemDns() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val resolver = DohHostAddressResolver(physicalNetwork = { connectivity.activeNetwork })
        val addresses = resolver.resolve("dns.google")
        assumeTrue("no DoH path from this device", !addresses.isNullOrEmpty())
        val geo = GeoIpCountry { context.assets.open("geoip/country.bin") }
        assertTrue(addresses!!.any { geo.lookup(it.address) == "US" })
    }
}
