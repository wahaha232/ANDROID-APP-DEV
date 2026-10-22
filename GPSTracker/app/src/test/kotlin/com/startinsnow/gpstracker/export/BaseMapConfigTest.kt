package com.startinsnow.gpstracker.export

import com.startinsnow.gpstracker.data.prefs.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseMapConfigTest {

    @Test
    fun defaultBaseMapIsSimpleVectorMap() {
        assertEquals(BaseMapType.SIMPLE, BaseMapType.DEFAULT)
        assertEquals(BaseMapType.SIMPLE, AppSettings().baseMapType)
        assertEquals("https://tiles.openfreemap.org/styles/positron", BaseMapType.SIMPLE.vectorStyleUrl)
        assertEquals(BaseMapStyles.OPEN_FREE_MAP_POSITRON_URL, BaseMapType.SIMPLE.vectorStyleUrl)
        assertTrue(BaseMapType.SIMPLE.isVector)
    }

    @Test
    fun storedValueRoundTripsAndUnknownValueFallsBackToDefault() {
        BaseMapType.entries.forEach { type ->
            assertEquals(type, BaseMapType.fromStoredValue(type.storedValue))
        }
        assertEquals(BaseMapType.SIMPLE, BaseMapType.fromStoredValue(null))
        assertEquals(BaseMapType.SIMPLE, BaseMapType.fromStoredValue(""))
        assertEquals(BaseMapType.SIMPLE, BaseMapType.fromStoredValue("NOT_A_BASE_MAP"))
    }

    @Test
    fun streetsAndSatelliteKeepOriginalRasterProviders() {
        assertEquals(
            TileProviderConfig.DEFAULT_OSM.tileUrlTemplate,
            BaseMapType.STREETS.tileProvider?.tileUrlTemplate
        )
        assertEquals(
            TileProviderConfig.FREE_ESRI_SATELLITE.tileUrlTemplate,
            BaseMapType.SATELLITE.tileProvider?.tileUrlTemplate
        )
        assertNull(BaseMapType.STREETS.vectorStyleUrl)
        assertNull(BaseMapType.SATELLITE.vectorStyleUrl)
        assertNull(BaseMapStyles.rasterStyleJson(BaseMapType.SIMPLE, null))
    }

    @Test
    fun rasterStyleJsonUsesTileUrlAndAttribution() {
        val streets = BaseMapStyles.rasterStyleJson(BaseMapType.STREETS, null)
        assertNotNull(streets)
        assertTrue(streets!!.contains("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertTrue(streets.contains("OpenStreetMap"))
        assertTrue(streets.contains("\"base-tiles-layer\""))

        val satellite = BaseMapStyles.rasterStyleJson(
            BaseMapType.SATELLITE,
            TerrainProviderConfig.AWS_TERRARIUM
        )
        assertNotNull(satellite)
        assertTrue(satellite!!.contains("World_Imagery"))
        assertTrue(satellite.contains("Esri"))
        assertTrue(satellite.contains("raster-dem"))
        assertTrue(satellite.contains("terrain-dem"))
        assertTrue(satellite.contains("\"terrain\""))
    }

    @Test
    fun everyBaseMapShowsCorrectDataSource() {
        assertTrue(BaseMapType.SIMPLE.attributionText.contains("OpenFreeMap"))
        assertTrue(BaseMapType.SIMPLE.attributionText.contains("OpenMapTiles"))
        assertTrue(BaseMapType.SIMPLE.attributionText.contains("OpenStreetMap"))
        assertTrue(BaseMapType.STREETS.attributionText.contains("OpenStreetMap"))
        assertTrue(BaseMapType.SATELLITE.attributionText.contains("Esri"))
    }
}
