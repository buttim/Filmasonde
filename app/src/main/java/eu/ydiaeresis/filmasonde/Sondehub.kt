package eu.ydiaeresis.filmasonde

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.internal.platform.PlatformRegistry.applicationContext
import org.json.JSONObject
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SondeData(
    val serial: String,
    @JsonNames("launch_site") val launchSite: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Site(
    val station: String,
    @JsonNames("station_name") val stationName: String,
    @JsonNames("burst_altitude") val burstAltitude: Float? = null,
    @JsonNames("ascent_rate") val ascentRate: Float? = null,
    @JsonNames("descent_rate") val descentRate: Float? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class ChasePosition(
    @JsonNames("software_version") val softwareVersion: String = "",
    @JsonNames("uploader_callsign") val uploaderCallsign: String,
    @JsonNames("uploader_position") val uploaderPosition: Array<Double>,
    @JsonNames("uploader_antenna") val uploaderAntenna: String = "",
    val mobile: Boolean = false,
    @JsonNames("user-agent") val userAgent: String = "",
    @JsonNames("uploader_alt") val uploaderAlt: Float = 0F,
    @JsonNames("uploader_position_elk") val uploaderPositionElk: String = "",
    val ts: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChasePosition

        if (mobile != other.mobile) return false
        if (uploaderAlt != other.uploaderAlt) return false
        if (softwareVersion != other.softwareVersion) return false
        if (uploaderCallsign != other.uploaderCallsign) return false
        if (!uploaderPosition.contentEquals(other.uploaderPosition)) return false
        if (uploaderAntenna != other.uploaderAntenna) return false
        if (userAgent != other.userAgent) return false
        if (uploaderPositionElk != other.uploaderPositionElk) return false
        if (ts != other.ts) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mobile.hashCode()
        result = 31 * result + uploaderAlt.hashCode()
        result = 31 * result + softwareVersion.hashCode()
        result = 31 * result + uploaderCallsign.hashCode()
        result = 31 * result + uploaderPosition.contentHashCode()
        result = 31 * result + uploaderAntenna.hashCode()
        result = 31 * result + userAgent.hashCode()
        result = 31 * result + uploaderPositionElk.hashCode()
        result = 31 * result + ts.hashCode()
        return result
    }
}


@OptIn(ExperimentalSerializationApi::class)
@Serializable
@Suppress("PropertyName")
data class Sonde(
    val serial: String,
    val type: String,
    val frequency: Float? = null,
    val tx_frequency: Float? = null,
    val lat: Double,
    val lon: Double,
    val alt: Double
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Frame(
    val frame: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double
) {}

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
@Serializable
//@Suppress("PropertyName")
data class RecoveredSonde(
    val serial: String,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val recovered: Boolean = false,
    var planned: Boolean = false,
    @JsonNames("recovered_by") var recoveredBy: String = "",
    var description: String = "",
    var datetime: LocalDateTime,
    var position: Array<Float>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecoveredSonde

        if (lat != other.lat) return false
        if (lon != other.lon) return false
        if (alt != other.alt) return false
        if (recovered != other.recovered) return false
        if (planned != other.planned) return false
        if (serial != other.serial) return false
        if (recoveredBy != other.recoveredBy) return false
        if (description != other.description) return false
        if (datetime != other.datetime) return false
        if (!position.contentEquals(other.position)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lat.hashCode()
        result = 31 * result + lon.hashCode()
        result = 31 * result + alt.hashCode()
        result = 31 * result + recovered.hashCode()
        result = 31 * result + planned.hashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + recoveredBy.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + datetime.hashCode()
        result = 31 * result + position.contentHashCode()
        return result
    }
}

data class AppVersion(
    val versionName: String,
    val versionNumber: Long,
)

fun getAppVersion(context: Context): AppVersion? {
    return try {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        AppVersion(
            versionName = packageInfo.versionName ?: "",
            versionNumber = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    } catch (e: Exception) {
        Log.d("getAppVersion", e.toString())
        null
    }
}

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
abstract class Sondehub {
    companion object {
        const val URI = "https://api.v2.sondehub.org/"
        private val json1 = Json {
            ignoreUnknownKeys = true
        }

        fun getUserAgent(): String {
            if (applicationContext==null) return "???"
            val v = getAppVersion(applicationContext!!) ?: return "???"

            return "${applicationContext!!.packageName} ${v.versionName}"
        }

        suspend fun <T> callAPI(
            api: String,
            ser: DeserializationStrategy<T>,
            params: Map<String, Any>? = null
        ): T? {
            try {
                HttpClient(CIO) {
                    install(ContentEncoding) {
                        gzip()
                    }
                    install(UserAgent) {
                        agent = getUserAgent()
                    }
                }.use { http ->
                    val response = http.get(URI + api) {
                        url {
                            params?.forEach { parameters.append(it.key, it.value.toString()) }
                        }
                    }
                    return when (response.status) {
                        HttpStatusCode.OK -> {
                            Log.d("Sondehub", response.bodyAsText())
                            json1.decodeFromString(
                                ser, response.bodyAsText()
                            )
                        }

                        else -> {
                            Log.d(
                                "Sondehub",
                                "Error in callAPI($api): ${response.status} (${response.bodyAsText()})"
                            )
                            null
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("Sondehub", "Exception in callAPI($api): $ex")
                throw(ex)
                //return null
            }
        }

        suspend fun getTrack(
            sondeType: String,
            sondeId: String,
            lastSeen: Instant?,
        ): List<Location> {
            val points = mutableListOf<Location>()
            var duration = "3h"
            if (lastSeen != null) {
                val delta = (Clock.System.now() - lastSeen).toLong(DurationUnit.SECONDS)
                if (delta < 60) duration = "1m"
                else if (delta < 60 * 30) duration = "30m"
                else if (delta < 60 * 60) duration = "1h"
            }
            val res = callAPI(
                "sondes/telemetry",
                MapSerializer(
                    String.serializer(),
                    MapSerializer(Instant.serializer(), Frame.serializer())
                ),
                mapOf("duration" to duration, "serial" to getSondehubId(sondeType, sondeId))
            )
            //if (res==null || res.size==0) return points
            res?.get(sondeId)?.filter { lastSeen == null || it.key > lastSeen }?.forEach {
                points.add(Location("manual").apply {
                    latitude = it.value.lat
                    longitude = it.value.lon
                    altitude = it.value.alt
                })
            }
            return points
        }

        fun getSondehubId(sondeType: String, sondeId: String): String = when (sondeType) {
            "M10", "M20" -> sondeId.take(3) + "-" + sondeId[3] + "-" + sondeId.substring(4)
            else -> sondeId
        }

        suspend fun recovered(
            context: Context,
            user: String,
            serial: String,
            lat: Double,
            lon: Double,
            alt: Double,
            description: String,
        ): String? {
            val data = buildJsonObject {
                put("serial", serial)
                put("recovered", true)
                put("recovered_by", user)
                put("description", description)
                put("lat", lat)
                put("lon", lon)
                put("alt", alt)
            }

            Log.i("Sondehub", "JSON: $data")

            try {
                HttpClient(CIO) {
                    install(UserAgent) {
                        agent = getUserAgent()
                    }
                }.use {
                    val response = it.put {
                        setBody(data.toString())
                        contentType(ContentType.Application.Json)
                        url(URI + "recovered")
                    }
                    Log.i(
                        "Sondehub",
                        "RESPONSE: (${response.status}) ${response.bodyAsText()}"
                    )
                    return when (response.status) {
                        HttpStatusCode.OK -> null
                        HttpStatusCode.BadRequest -> {
                            try {
                                val json = JSONObject(response.bodyAsText())
                                json.getString("message")
                            } catch (_: Exception) {
                                context.getString(R.string.unknown_error, response.bodyAsText())
                            }
                        }

                        else -> context.getString(
                            R.string.error_sending_report_status,
                            response.status
                        )
                    }
                }
            } catch (ex: Exception) {
                Log.i("Sondehub", ex.toString())
                return context.getString(R.string.failed_to_send_report, ex)
            }
        }

        suspend fun stationFromSerial(sondeType: String, serial: String): String? {
            val id = getSondehubId(sondeType, serial)
            val res = callAPI(
                "predictions/reverse",
                MapSerializer(String.serializer(), SondeData.serializer()),
                mapOf("vehicles" to id)
            )
            return res?.get(serial)?.launchSite
        }

        suspend fun getChaseCar(lat: Double, lng: Double, dist: Double): String? {
            val chaseCars = callAPI(
                "listeners/telemetry",
                MapSerializer(
                    String.serializer(),
                    MapSerializer(String.serializer(), ChasePosition.serializer())
                ),
                mapOf("duration" to "3h")
            )
            if (chaseCars == null) return null
            var chaseCar: String? = null
            var minDistance: Float? = null
            val point = Location("manual").apply {
                latitude = lat
                longitude = lng
            }
            chaseCars.forEach { (name, positions: Map<String, ChasePosition>) ->
                val lastPosition =
                    positions.filter { it.value.mobile }.maxByOrNull { it.key }
                if (lastPosition != null) {
                    val p = Location("manual").apply {
                        latitude = lastPosition.value.uploaderPosition[0]
                        longitude = lastPosition.value.uploaderPosition[1]
                    }
                    val d = point.distanceTo(p)
                    if (d < dist && (minDistance === null || d < minDistance)) {
                        minDistance = d
                        chaseCar = name
                    }
                }
            }
            Log.i("Sondehub", "getChaseCar -> $chaseCar")
            return chaseCar
        }

        suspend fun sites(): Map<String, Site>? {
            return callAPI(
                "sites", MapSerializer(
                    String.serializer(),
                    Site.serializer()
                )
            )
        }

        suspend fun getRecovered(serial: String): RecoveredSonde? {
            val res = callAPI(
                "recovered",
                ListSerializer(RecoveredSonde.serializer()),
                mapOf("serial" to serial)
            )
            return res?.firstOrNull()
        }

        //find most likely sonde type and frequency from current position
        suspend fun getNearbySonde(
            lat: Double,
            lng: Double,
            maxDistance: Int = 200000,
            maxSeconds: Int = 72000
        ): Sonde? {
            val sondes = callAPI(
                "sondes",
                MapSerializer(String.serializer(), Sonde.serializer()),
                mapOf(
                    "lat" to lat,
                    "lon" to lng,
                    "distance" to maxDistance,
                    "last" to maxSeconds
                )
            )
            if (sondes == null) return null
            var minDistance: Float? = null
            val point = Location("manual").apply {
                latitude = lat
                longitude = lng
            }
            var sonde: Sonde? = null
            sondes.forEach { entry ->
                val p = Location("manual").apply {
                    latitude = entry.value.lat
                    longitude = entry.value.lon
                }
                val d = point.distanceTo(p)
                if (minDistance === null || d < minDistance) {
                    minDistance = d
                    sonde = entry.value
                }
            }
            if (sonde != null)
                Log.i(
                    "Sondehub",
                    "getNearbySonde -> ${sonde.serial} ${sonde.type} ${sonde.frequency} ${sonde.tx_frequency}"
                )
            else
                Log.d("Sondehub", "getNearbySonde -> null")
            return sonde
        }
    }
}