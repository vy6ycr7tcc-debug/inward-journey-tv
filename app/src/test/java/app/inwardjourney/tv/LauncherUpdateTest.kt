package app.inwardjourney.tv

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class LauncherUpdateTest {

    private lateinit var server: HttpServer
    private var serverBaseUrl: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val port = server.address.port
        serverBaseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun testCheckSuccessfulUpdateAvailable() {
        val jsonResponse = """
            {
                "versionCode": ${BuildConfig.VERSION_CODE + 1},
                "versionName": "3.0",
                "apkUrl": "https://example.com/download/app.apk"
            }
        """.trimIndent()

        server.createContext("/version.json") { exchange ->
            val responseBytes = jsonResponse.toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        val info = LauncherUpdate.check("$serverBaseUrl/version.json")
        assertNotNull(info)
        assertEquals(BuildConfig.VERSION_CODE + 1, info!!.versionCode)
        assertEquals("3.0", info.versionName)
        assertEquals("https://example.com/download/app.apk", info.apkUrl)
    }

    @Test
    fun testCheckVersionCodeLowerOrEqualReturnsNull() {
        val jsonResponse = """
            {
                "versionCode": ${BuildConfig.VERSION_CODE},
                "versionName": "2.5",
                "apkUrl": "https://example.com/download/app.apk"
            }
        """.trimIndent()

        server.createContext("/version.json") { exchange ->
            val responseBytes = jsonResponse.toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        val info = LauncherUpdate.check("$serverBaseUrl/version.json")
        assertNull(info)
    }

    @Test
    fun testCheckNonHttpsApkUrlReturnsNull() {
        val jsonResponse = """
            {
                "versionCode": ${BuildConfig.VERSION_CODE + 1},
                "versionName": "3.0",
                "apkUrl": "http://example.com/download/app.apk"
            }
        """.trimIndent()

        server.createContext("/version.json") { exchange ->
            val responseBytes = jsonResponse.toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        val info = LauncherUpdate.check("$serverBaseUrl/version.json")
        assertNull(info)
    }

    @Test
    fun testCheckInvalidJsonReturnsNull() {
        server.createContext("/version.json") { exchange ->
            val responseBytes = "invalid json body".toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        val info = LauncherUpdate.check("$serverBaseUrl/version.json")
        assertNull(info)
    }

    @Test
    fun testCheckHttpErrorReturnsNull() {
        server.createContext("/version.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }

        val info = LauncherUpdate.check("$serverBaseUrl/version.json")
        assertNull(info)
    }

    @Test
    fun testCheckNetworkFailureReturnsNull() {
        val port = server.address.port
        server.stop(0)
        val info = LauncherUpdate.check("http://127.0.0.1:$port/version.json")
        assertNull(info)
    }
}
