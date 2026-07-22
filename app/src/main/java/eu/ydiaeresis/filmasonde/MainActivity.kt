package eu.ydiaeresis.filmasonde

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import eu.ydiaeresis.filmasonde.ui.theme.FilmasondeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.use
import java.io.File
import java.util.Locale
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import androidx.camera.core.Preview as CamPreview

fun formatRecordingDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
/**
 * Returns true if the physical hardware device is currently oriented horizontally
 * (Landscape or Reverse Landscape), independent of system auto-rotate lock.
 */
fun isDevicePhysicallyHorizontal(context: Context): Boolean {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // On Android 11 (API 30) and above, use context.display
    val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }

    return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
}

data class CameraFov(val horizontal: Float, val vertical: Float)

fun getCameraFieldOfView(context: Context): CameraFov {
    val defaultFallback = CameraFov(horizontal = 60f, vertical = 45f)
    try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (cameraId in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                // 💡 FIX: Reads as raw FloatArray? and checks size safely without collections extensions
                val focalLengths: FloatArray? =
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
                    val focalLength = focalLengths[0] // Main default camera lens focal length

                    // Trigonometry calculation
                    val hFov = 2 * atan(sensorSize.width / (2 * focalLength))
                    val vFov = 2 * atan(sensorSize.height / (2 * focalLength))

                    return CameraFov(
                        horizontal = Math.toDegrees(hFov.toDouble()).toFloat(),
                        vertical = Math.toDegrees(vFov.toDouble()).toFloat()
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e("CAMERA_FOV", "Failed to retrieve hardware specs, falling back.", e)
    }
    return defaultFallback
}

/**
 * Converts FusedLocationProviderClient's callback system into a cold Coroutine Flow stream.
 */
@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
fun getLocationFlow(
    fusedLocationClient: FusedLocationProviderClient,
    locationRequest: LocationRequest
): Flow<Location> = callbackFlow {

    val callback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                // Emit the fresh location coordinates into the Flow stream
                trySend(location)
            }
        }
    }

    // Start streaming updates from the hardware
    fusedLocationClient.requestLocationUpdates(
        locationRequest,
        callback,
        Looper.getMainLooper()
    )

    // IMPORTANT: Automatically cleans up hardware listeners when the ViewModel
    // coroutine scope is cancelled or when the user leaves the screen!
    awaitClose {
        fusedLocationClient.removeLocationUpdates(callback)
    }
}

// Container for Earth-Centered, Earth-Fixed 3D coordinates
private data class EcefPoint(val x: Double, val y: Double, val z: Double)

fun Location.euclideanDistanceTo(other: Location): Double {
    val ecef1 = this.toEcef()
    val ecef2 = other.toEcef()

    return sqrt(
        (ecef2.x - ecef1.x).pow(2) +
                (ecef2.y - ecef1.y).pow(2) +
                (ecef2.z - ecef1.z).pow(2)
    )
}

private fun Location.toEcef(): EcefPoint {
    // WGS-84 Ellipsoid Constants
    val a = 6378137.0           // Semi-major axis (meters)
    val f = 1.0 / 298.257223563 // Flattening
    val e2 = 2 * f - f * f      // First eccentricity squared

    val latRad = Math.toRadians(this.latitude)
    val lonRad = Math.toRadians(this.longitude)
    val alt = this.altitude     // Standard Android Location altitude is in meters

    val sinLat = sin(latRad)
    val cosLat = cos(latRad)
    val sinLon = sin(lonRad)
    val cosLon = cos(lonRad)

    // Radius of curvature in the prime vertical
    val n = a / sqrt(1.0 - e2 * sinLat * sinLat)

    // Transform to Cartesian 3D Space
    val x = (n + alt) * cosLat * cosLon
    val y = (n + alt) * cosLat * sinLon
    val z = (n * (1.0 - e2) + alt) * sinLat

    return EcefPoint(x, y, z)
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SondePosition constructor(
    val frame: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    //val datetime: Instant,
    @JsonNames("time_received")
    val timeReceived: Instant
)

class LocationViewModel : ViewModel() {
    val mqttJson = Json {
        ignoreUnknownKeys = true // Prevents crashes if your broker adds new fields
        coerceInputValues = true // Helps if, for example, a number is sent as a string
    }
    val client = MqttClient("TrovaLaSonda") {
        keepAliveSeconds = 30
        autoReconnect = true
    }

    // Persistent State Machine trackers
    var isLocationReady by mutableStateOf(false)
        private set
    var isTargetLoaded by mutableStateOf(false)
        private set
    var noSonde by mutableStateOf(false)
        private set
    var serial by mutableStateOf("[no sonde]")
        private set
    var statusText by mutableStateOf("Initializing")
        private set

    // User GPS States
    var userLatitude by mutableDoubleStateOf(0.0)
        private set
    var userLongitude by mutableDoubleStateOf(0.0)
        private set
    var userAltitude by mutableDoubleStateOf(0.0)
        private set

    // Moving Target States
    var targetLatitude by mutableDoubleStateOf(0.0)
        private set
    var targetLongitude by mutableDoubleStateOf(0.0)
        private set
    var targetAltitude by mutableDoubleStateOf(0.0)
        private set

    private var isTrackingStarted = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    /**
     * Updates recording status. Resets timer to 0 when recording stops.
     */
    fun onRecordingStarted() {
        _isRecording.value = true
    }

    /**
     * Called continuously by CameraX VideoRecordEvent.Status
     */
    fun onRecordingDurationUpdated(durationMs: Long) {
        _recordingDurationMs.value = durationMs
        Log.d("REC", "Recording Duration: ${formatRecordingDuration(durationMs)}")
    }

    /**
     * Called when recording stops or fails, resetting state and timer back to zero.
     */
    fun onRecordingFinalized() {
        _isRecording.value = false
        _recordingDurationMs.value = 0L
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startTracking(
        fusedLocationClient: FusedLocationProviderClient,
        locationRequest: LocationRequest,
        onSerialAcquired: (String) -> Unit, // Callback to update MainActivity.serial
        onNewTargetLocation: (Instant, Int, Double, Double, Double) -> Unit = { _, _, _, _, _ -> }
    ) {
        if (isTrackingStarted) return
        isTrackingStarted = true

        statusText = "Waiting for GPS..."

        viewModelScope.launch {
            getLocationFlow(fusedLocationClient, locationRequest)
                .flowOn(Dispatchers.IO)
                .collect { location ->
                    if (noSonde) return@collect
                    try {
                        statusText = "Searching for nearest sonde..."
                        userLatitude = location.latitude
                        userLongitude = location.longitude
                        userAltitude = location.altitude

                        if (!isLocationReady) {
                            val sonde = Sondehub.getNearbySonde(
                                userLatitude,
                                userLongitude,
                                maxDistance = 50_000_000,
                                maxSeconds = 0//3600
                            )
                            Log.i("MQTT", "Sonde: $sonde")

                            if (sonde == null) {
                                noSonde = true
                                statusText = "No nearby sonde. Please retry later"
                            } else {
                                serial = sonde.serial
                                onSerialAcquired(serial)

                                targetLatitude = sonde.lat
                                targetLongitude = sonde.lon
                                targetAltitude = sonde.alt
                                statusText = "Contacting Sondehub server..."
                                delay(500.milliseconds)
                                isTargetLoaded = true

                                // Run MQTT streaming collection
                                client.use(MqttEndpoint.parse("wss://ws-reader.v2.sondehub.org/")) { c ->
                                    Log.i("MQTT", "Connected")
                                    statusText = ""
                                    c.subscribe("sondes/${sonde.serial}")
                                    c.messages.collect { msg ->
                                        Log.i(
                                            "MQTT",
                                            "Message: ${msg.topic}: ${msg.payload.decodeToString()}"
                                        )
                                        val jsonString = msg.payload.decodeToString()
                                        val data =
                                            mqttJson.decodeFromString<SondePosition>(jsonString)
                                        Log.i(
                                            "MQTT",
                                            "lat: ${data.lat}, lon: ${data.lon}, alt: ${data.alt}"
                                        )
                                        onNewTargetLocation(
                                            data.timeReceived,
                                            data.frame,
                                            data.lat,
                                            data.lon,
                                            data.alt
                                        )

                                        targetLatitude = data.lat
                                        targetLongitude = data.lon
                                        targetAltitude = data.alt
                                    }
                                }
                            }
                            isLocationReady = true
                        }
                    } catch (e: Exception) {
                        Log.e("GPS_Pipeline", "API Error on location shift", e)
                        statusText = "Error accessing Sondehub. Retry later"
                    }
                }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val snackbarHostState = SnackbarHostState()
    private var serial: String? = null
    private var subBuilder: TelemetrySubtitleBuilder? = null
    private var currentTempVideoFile: File? = null

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO])
    @Composable
    fun MainScreen(
        hasLocationPermission: Boolean,
        hasCameraPermission: Boolean,
        hasRecordingAudioPermission: Boolean,
        onLocationPermissionRequest: () -> Unit,
        onRecordingAudioPermissionRequest: () -> Unit,
        onCameraPermissionRequest: () -> Unit,
        viewModel: LocationViewModel = viewModel()
    ) {
        val context = LocalContext.current
        val realCameraFov = remember(context) { getCameraFieldOfView(context) }

        // Keep layout-only temporary states locally
        //var isRecording by remember { mutableStateOf(false) }
        val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
        val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithLifecycle()

        // Initialized as an Identity Matrix so it never defaults to NaN or freezes on emulators.
        var rotationMatrix by remember {
            mutableStateOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        }

        val fusedLocationClient = remember {
            LocationServices.getFusedLocationProviderClient(context)
        }

        var startRecordingTime = Instant.DISTANT_PAST

        // 1. COMPASS & ROTATION MATRIX LISTENER (Unchanged, operates on UI configuration)
        DisposableEffect(Unit) {
            val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val matrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(matrix, event.values)

                        val windowManager =
                            context.getSystemService(WINDOW_SERVICE) as WindowManager
                        val displayRotation = windowManager.defaultDisplay.rotation

                        val remappedMatrix = FloatArray(9)

                        when (displayRotation) {
                            Surface.ROTATION_90 -> {
                                SensorManager.remapCoordinateSystem(
                                    matrix,
                                    SensorManager.AXIS_Y,
                                    SensorManager.AXIS_MINUS_X,
                                    remappedMatrix
                                )

                                // 💡 MICRO-CORRECTION: Neutralize the wide landscape pitch distortion
                                // We damp down the subtle Z-to-Y skew matrix indices (indices 5 and 7)
                                remappedMatrix[5] = remappedMatrix[5] * 0.92f
                                remappedMatrix[7] = remappedMatrix[7] * 0.92f
                            }

                            Surface.ROTATION_270 -> {
                                SensorManager.remapCoordinateSystem(
                                    matrix,
                                    SensorManager.AXIS_MINUS_Y,
                                    SensorManager.AXIS_X,
                                    remappedMatrix
                                )

                                // Mirror the layout scaling for Reverse Landscape
                                remappedMatrix[5] = remappedMatrix[5] * 0.92f
                                remappedMatrix[7] = remappedMatrix[7] * 0.92f
                            }

                            else -> {
                                // Portrait stays completely stock, maintaining your working baseline
                                System.arraycopy(matrix, 0, remappedMatrix, 0, 9)
                            }
                        }

                        rotationMatrix = remappedMatrix
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(
                sensorListener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            onDispose { sensorManager.unregisterListener(sensorListener) }
        }

        // 2. GPS LIVE STREAM CONTROL (Delegated to the ViewModel)
        LaunchedEffect(hasLocationPermission) {
            if (hasLocationPermission) {
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    5000L
                ).build()

                var lastFrame = 0
                viewModel.startTracking(
                    fusedLocationClient, locationRequest,
                    onSerialAcquired = { acquiredSerial ->
                        this@MainActivity.serial = acquiredSerial
                    }
                ) { timestamp, frame, lat, lon, alt ->
                    if (isRecording && frame > lastFrame) {
                        lastFrame = frame
                        //TODO: check su timestamp
                        val elapsedMilliseconds =
                            timestamp.minus(startRecordingTime).inWholeMilliseconds
                        if (subBuilder != null && elapsedMilliseconds > 0) {
                            Log.d(
                                "SUBS",
                                "$timestamp: $frame, $lat, $lon, $alt ($elapsedMilliseconds)"
                            )
                            subBuilder!!.addFrame(
                                elapsedMilliseconds,
                                frame,
                                lat,
                                lon,
                                alt
                            )
                        }
                    }
                }
            }
        }

        // --- SCREEN HUD RENDERING ---
        @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        modifier = Modifier.padding(12.dp),
                        actionOnNewLine = true,
                        // 1. Let the system handle the container color naturally based on theme
                        action = {
                            TextButton(onClick = { data.performAction() }) {
                                Text(
                                    text = data.visuals.actionLabel ?: "Open",
                                    // 2. Uses your theme's high-visibility accent color for the primary action
                                    color = MaterialTheme.colorScheme.inversePrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissAction = {
                            TextButton(onClick = { data.dismiss() }) {
                                Text(
                                    text = "Dismiss",
                                    // 3. Uses the standard text color matching the container context
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    ) {
                        Text(
                            text = data.visuals.message,
                            // 4. Matches the container text standard profile automatically
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasLocationPermission || !hasCameraPermission || !hasRecordingAudioPermission) {
                    // Permission Guard Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            resources.getString(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 40.sp
                        )
                        Spacer(modifier = Modifier.height(120.dp))
                        Text("Permissions required", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onCameraPermissionRequest,
                            enabled = !hasCameraPermission
                        ) {
                            Text(if (hasCameraPermission) "Camera Ready ✓" else "Grant Camera")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRecordingAudioPermissionRequest,
                            enabled = !hasRecordingAudioPermission
                        ) {
                            Text(if (hasRecordingAudioPermission) "Microphone Ready ✓" else "Grant Microphone")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onLocationPermissionRequest,
                            enabled = !hasLocationPermission
                        ) {
                            Text(if (hasLocationPermission) "Location Ready ✓" else "Grant Location")
                        }
                    }
                } else {
                    CameraPreview()
                    CompassRulerHUD(
                        rotationMatrix = { rotationMatrix },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 180.dp) // 💡 Pushed higher up to give breathing room above the button
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = viewModel.serial, // Read from ViewModel
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Green,
                            modifier = Modifier
                                .padding(top = 40.dp)
                                .clickable {
                                    Log.i("INFO", "click su ${viewModel.serial}")
                                    startActivity(Intent(Intent.ACTION_VIEW).setData("http://sondehub.org/${viewModel.serial}".toUri()))
                                }
                        )

                        // Read coordinate data dynamically from ViewModel
                        val distance =
                            if (viewModel.userLatitude == 0.0 || viewModel.userLongitude == 0.0 || viewModel.targetLatitude == 0.0 || viewModel.targetLongitude == 0.0) 0
                            else Location("manual").apply {
                                latitude = viewModel.userLatitude
                                longitude = viewModel.userLongitude
                                altitude = viewModel.userAltitude
                            }
                                .euclideanDistanceTo(Location("manual").apply {
                                    latitude = viewModel.targetLatitude
                                    longitude = viewModel.targetLongitude
                                    altitude = viewModel.targetAltitude
                                })
                                .toInt()

                        Text(
                            if (distance == 0) "---" else if (distance > 5000) "%.1fkm / H:%.0fm".format(
                                distance / 1000.0,
                                viewModel.targetAltitude
                            ) else "${distance}m",
                            fontSize = 15.sp,
                            color = Color.Yellow,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }

                    SondeAROverlay(
                        userLatitude = { viewModel.userLatitude },
                        userLongitude = { viewModel.userLongitude },
                        userAltitude = { viewModel.userAltitude },
                        targetLatitude = { viewModel.targetLatitude },
                        targetLongitude = { viewModel.targetLongitude },
                        targetAltitude = { viewModel.targetAltitude },
                        rotationMatrix = { rotationMatrix },
                        horizontalFov = realCameraFov.horizontal,
                        verticalFov = realCameraFov.vertical,
                        modifier = Modifier.fillMaxSize(),
                    )

                    RecordButtonContainer(
                        isRecording = isRecording,
                        recordingDurationMs = recordingDurationMs,
                        onRecordClick = {
                            if (!isRecording) {
                                startRecordingTime = Clock.System.now()
                                Log.d("SUBS", "Start recording: $startRecordingTime")
                                startRecordingVideo(
                                    latitude = viewModel.userLatitude,
                                    longitude = viewModel.userLongitude,
                                    isHorizontal = isDevicePhysicallyHorizontal(context),
                                    viewModel
                                )
                            } else
                                stopRecordingVideo()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter) // 💡 Locks it back to the bottom center
                            .padding(bottom = 54.dp)
                    )
                    if (!viewModel.isTargetLoaded) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable {}
                                .background(Color.Black.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = viewModel.statusText,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Yellow,
                                textAlign = TextAlign.Center,
                                lineHeight = 40.sp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val context = LocalContext.current

// 1. Initialize permission states based on current device settings
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasRecordingAudioPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasLocationPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                )
            }


// 2. Register the System Permission Dialog Launchers
            val cameraLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasCameraPermission = isGranted
            }
            val recordAudioLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasRecordingAudioPermission = isGranted
            }
            val locationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                hasLocationPermission = fineGranted || coarseGranted
            }
            FilmasondeTheme {
                @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    /*snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                modifier = Modifier.padding(12.dp),
                                action = {
                                    TextButton(onClick = { data.performAction() }) {
                                        Text(text = data.visuals.actionLabel ?: "Open", color = Color.Green)
                                    }
                                },
                                dismissAction = {
                                    IconButton(onClick = { data.dismiss() }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = Color.White
                                        )
                                    }
                                }
                            ) {
                                Text(text = data.visuals.message)
                            }
                        }
                    }*/
                ) { _ ->
                    MainScreen(
                        hasLocationPermission = hasLocationPermission,
                        hasRecordingAudioPermission = hasRecordingAudioPermission,
                        hasCameraPermission = hasCameraPermission,
                        onCameraPermissionRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                        onRecordingAudioPermissionRequest = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onLocationPermissionRequest = {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        })
                }
            }
        }
    }

    @Composable
    fun CameraPreview(modifier: Modifier = Modifier) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                // Create a paint object for the watermark styling
                val watermarkPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 40f
                    alpha = 180 // Semi-transparent (0-255)
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

// 1. Explicitly pass an empty error listener lambda at the end
                val watermarkEffect = OverlayEffect(
                    CameraEffect.VIDEO_CAPTURE,
                    0,
                    Handler(Looper.getMainLooper())
                ) { throwable ->
                    Log.e("CameraX", "Overlay Effect Error", throwable)
                }

// 2. Clear any existing listeners and explicitly return true
                watermarkEffect.clearOnDrawListener()
                watermarkEffect.setOnDrawListener { frame ->
                    val canvas = frame.overlayCanvas
                    val text = "FILMASONDE"

                    val x = canvas.width - watermarkPaint.measureText(text) - 40f
                    val y = canvas.height - 50f

                    canvas.drawText(text, x, y, watermarkPaint)

                    true // 👈 FIXED: Returns a boolean telling CameraX the frame rendering completed
                }

                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    val preview = CamPreview.Builder().build()
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)

                    preview.surfaceProvider = surfaceProvider

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()

                        // 3. Wrap your existing use cases into a modern group
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(videoCapture!!)
                            .addEffect(watermarkEffect) // 👈 This injects your watermark into the loop!
                            .build()

                        // 4. Bind the entire UseCaseGroup to the lifecycle instead of separate items
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            useCaseGroup // Passed seamlessly here
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )
    }

    /**
     * HUD-style horizontal compass ruler for the bottom overlay.
     * * @param rotationMatrix Provider for the current 3D rotation matrix array.
     * @param modifier Custom positioning layout bounds.
     * @param rulerHeight Vertical layout footprint constraint.
     * @param pixelsPerDegree Horizontal spacing spread (higher values spread ticks out further).
     * @param visibleHalfWindow Total degrees visible to the left and right of the center tick.
     */
    @OptIn(ExperimentalTextApi::class)
    @Composable
    fun CompassRulerHUD(
        rotationMatrix: () -> FloatArray,
        modifier: Modifier = Modifier,
        rulerHeight: Dp = 100.dp,
        pixelsPerDegree: Dp = 6.dp,
        visibleHalfWindow: Int = 45
    ) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current

        // 1. Density conversions
        val pixelsPerDegreePx = with(density) { pixelsPerDegree.toPx() } * 3f
        val majorTickLengthPx = with(density) { 22.dp.toPx() }
        val minorTickLengthPx = with(density) { 12.dp.toPx() }
        val majorTickStrokePx = with(density) { 2.5.dp.toPx() }
        val minorTickStrokePx = with(density) { 1.25.dp.toPx() }
        val textOffsetPx = with(density) { 6.dp.toPx() }
        val redHairlineStrokePx = with(density) { 2.dp.toPx() }

        // 2. Dynamic heading calculation
        val matrix = rotationMatrix()
        val rawHeadingRadians = atan2(matrix[1].toDouble(), matrix[4].toDouble())
        var targetHeading = Math.toDegrees(rawHeadingRadians).toFloat()
        if (targetHeading < 0) targetHeading += 360f

        var smoothedHeading by remember { mutableFloatStateOf(targetHeading) }

        // 💡 RESPONSIVE SMOOTHING: 0.35f eliminates shaky jitter while remaining instant
        val alpha = 0.35f

        LaunchedEffect(targetHeading) {
            var diff = targetHeading - smoothedHeading
            if (diff < -180f) {
                diff += 360f
            } else if (diff > 180f) {
                diff -= 360f
            }

            var nextHeading = smoothedHeading + (alpha * diff)
            nextHeading = (nextHeading + 360f) % 360f
            smoothedHeading = nextHeading
        }

        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(rulerHeight)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height * 0.35f

            // 3. Draw the centered 30% width semi-transparent gray plate
            val plateWidth = size.width * 0.30f
            val plateLeft = centerX - (plateWidth / 2f)
            drawRect(
                color = Color(0x664A4A4A), // ~40% opacity mid-tone gray
                topLeft = Offset(plateLeft, 0f),
                size = Size(plateWidth, size.height)
            )

            // 4. Draw Full-Width horizontal guide rail
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.5.dp.toPx()
            )

            // 5. Draw graduation ticks and text across the ENTIRE width of the screen
            val minDegree = (smoothedHeading.toInt() - visibleHalfWindow)
            val maxDegree = (smoothedHeading.toInt() + visibleHalfWindow)

            for (degreeLoop in minDegree..maxDegree) {
                val normalizedDegree = (degreeLoop + 360) % 360
                val degreeDifference = degreeLoop - smoothedHeading
                val tickX = centerX + (degreeDifference * pixelsPerDegreePx)

                // Clip drawing beyond physical screen edges
                if (tickX < 0 || tickX > size.width) continue

                if (normalizedDegree % 5 == 0) {
                    val isMajorTick = normalizedDegree % 10 == 0
                    val tickLength = if (isMajorTick) majorTickLengthPx else minorTickLengthPx

                    drawLine(
                        color = Color.White.copy(alpha = 0.7f),
                        start = Offset(tickX, centerY),
                        end = Offset(tickX, centerY + tickLength),
                        strokeWidth = if (isMajorTick) majorTickStrokePx else minorTickStrokePx
                    )

                    if (isMajorTick) {
                        val labelText = when (normalizedDegree) {
                            0 -> "N"
                            90 -> "E"
                            180 -> "S"
                            270 -> "W"
                            else -> normalizedDegree.toString()
                        }

                        val isLetter = normalizedDegree % 90 == 0
                        val textStyle = TextStyle(
                            color = if (isLetter) Color(0xFF00FFCC) else Color.White,
                            fontSize = if (isLetter) 18.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // 💡 FIXED: Supply explicit maximum layout width constraints during measurement
                        // to prevent string slicing and wrap failures near the canvas borders
                        val textLayout = textMeasurer.measure(
                            text = labelText,
                            style = textStyle,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            constraints = Constraints(
                                minWidth = 0,
                                maxWidth = size.width.toInt(),
                                minHeight = 0,
                                maxHeight = size.height.toInt()
                            )
                        )

                        drawText(
                            textMeasurer = textMeasurer,
                            text = labelText,
                            style = textStyle,
                            topLeft = Offset(
                                x = tickX - (textLayout.size.width / 2f),
                                y = centerY + tickLength + textOffsetPx
                            )
                        )
                    }
                }
            }

            // 6. Center Red Hairline Index Cursor (Z-ordered above canvas content)
            drawLine(
                color = Color(0xFFFF3333), // Pure instrument red
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = redHairlineStrokePx
            )
        }
    }

    @Composable
    fun SondeAROverlay(
        userLatitude: () -> Double,
        userLongitude: () -> Double,
        userAltitude: () -> Double,
        targetLatitude: () -> Double,
        targetLongitude: () -> Double,
        targetAltitude: () -> Double,
        rotationMatrix: () -> FloatArray,
        horizontalFov: Float, // Passed from hardware CameraManager
        verticalFov: Float,   // Passed from hardware CameraManager
        modifier: Modifier = Modifier
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lat = userLatitude()
                val lng = userLongitude()
                val alt = userAltitude()
                val tLat = targetLatitude()
                val tLng = targetLongitude()
                val tAlt = targetAltitude()

                if (lat != 0.0 && lng != 0.0 && tLat != 0.0 && tLng != 0.0) {
                    val matrix = rotationMatrix()

                    // 1. Local ENU coordinate transformation
                    val latDelta = tLat - lat
                    val lngDelta = tLng - lng

                    val targetNorth = latDelta * 111111.0
                    val targetEast = lngDelta * 111111.0 * cos(Math.toRadians(lat))
                    val targetUp = tAlt - alt

                    // 2. Local Space -> Device Camera Space Matrix Math
                    val xDevice =
                        (matrix[0] * targetEast + matrix[3] * targetNorth + matrix[6] * targetUp).toFloat()
                    val yDevice =
                        (matrix[1] * targetEast + matrix[4] * targetNorth + matrix[7] * targetUp).toFloat()
                    val zDevice =
                        (matrix[2] * targetEast + matrix[5] * targetNorth + matrix[8] * targetUp).toFloat()

                    val isLandscape = size.width > size.height
                    val activeFovX = if (isLandscape) horizontalFov else verticalFov
                    val activeFovY = if (isLandscape) verticalFov else horizontalFov

                    val hFovRadians = Math.toRadians(activeFovX.toDouble())
                    val vFovRadians = Math.toRadians(activeFovY.toDouble())
                    val forwardDistance = -zDevice

                    val centerX = size.width / 2f
                    val centerY = size.height / 2f

                    val angleX = atan2(xDevice.toDouble(), forwardDistance.toDouble())
                    val angleY = atan2(yDevice.toDouble(), forwardDistance.toDouble())

                    // Plug the dynamic radians directly into the pixel-mapping divisor
                    val rectCenterX =
                        centerX + ((angleX / hFovRadians).toFloat() * size.width)
                    val rectCenterY = centerY - ((angleY / vFovRadians).toFloat() * size.height)

                    val isOnScreen = zDevice < 0 &&
                            rectCenterX in 0f..size.width &&
                            rectCenterY in 0f..size.height

                    if (isOnScreen) {
                        // --- Define the FPV Style ---
                        val targetColor = Color(0xFF00FFCC)
                        val totalSize = 75.dp.toPx()
                        val cornerLen = 18.dp.toPx()
                        val strokeW = 2.5.dp.toPx()

                        val left = rectCenterX - totalSize / 2
                        val top = rectCenterY - totalSize / 2
                        val right = rectCenterX + totalSize / 2
                        val bottom = rectCenterY + totalSize / 2

                        // 1. Top-Left Corner
                        drawLine(
                            color = targetColor,
                            start = Offset(left, top),
                            end = Offset(left + cornerLen, top),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = targetColor,
                            start = Offset(left, top),
                            end = Offset(left, top + cornerLen),
                            strokeWidth = strokeW
                        )

                        // 2. Top-Right Corner
                        drawLine(
                            color = targetColor,
                            start = Offset(right, top),
                            end = Offset(right - cornerLen, top),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = targetColor,
                            start = Offset(right, top),
                            end = Offset(right, top + cornerLen),
                            strokeWidth = strokeW
                        )

                        // 3. Bottom-Left Corner
                        drawLine(
                            color = targetColor,
                            start = Offset(left, bottom),
                            end = Offset(left + cornerLen, bottom),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = targetColor,
                            start = Offset(left, bottom),
                            end = Offset(left, bottom - cornerLen),
                            strokeWidth = strokeW
                        )

                        // 4. Bottom-Right Corner
                        drawLine(
                            color = targetColor,
                            start = Offset(right, bottom),
                            end = Offset(right - cornerLen, bottom),
                            strokeWidth = strokeW
                        )
                        drawLine(
                            color = targetColor,
                            start = Offset(right, bottom),
                            end = Offset(right, bottom - cornerLen),
                            strokeWidth = strokeW
                        )
                    } else {
                        val screenAngle = if (zDevice < 0) atan2(
                            -yDevice.toDouble(),
                            xDevice.toDouble()
                        ) else if (xDevice >= 0) 0.0 else Math.PI
                        val arrowMargin = 35.dp.toPx()
                        val arrowX =
                            centerX + (centerX - arrowMargin) * cos(screenAngle).toFloat()
                        val arrowY =
                            centerY + (centerY - arrowMargin) * sin(screenAngle).toFloat()

                        val arrowPath = Path().apply {
                            moveTo(arrowX, arrowY)
                            val length = 35
                            lineTo(
                                arrowX - length.dp.toPx() * cos(screenAngle - 0.4).toFloat(),
                                arrowY - length.dp.toPx() * sin(screenAngle - 0.4).toFloat()
                            )
                            lineTo(
                                arrowX - length.dp.toPx() * cos(screenAngle + 0.4).toFloat(),
                                arrowY - length.dp.toPx() * sin(screenAngle + 0.4).toFloat()
                            )
                            close()
                        }
                        drawPath(path = arrowPath, color = Color(0xFFFF3366))
                    }
                }
            }
        }
    }

    @Composable
    fun RecordButton(
        isRecording: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        // 1. Smoothly animate the corner radius and size when clicked
        val cornerRadius by animateDpAsState(
            targetValue = if (isRecording) 12.dp else 40.dp,
            label = "shape"
        )
        val innerCirclePadding by animateDpAsState(
            targetValue = if (isRecording) 20.dp else 6.dp,
            label = "size"
        )

        // 2. Set up the infinite rotation animation
        val infiniteTransition = rememberInfiniteTransition(label = "rotation")
        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isRecording) 360f else 0f, // Only rotate up to 360 if recording
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2000,
                    easing = LinearEasing
                ) // 2 seconds per full rotation
            ),
            label = "angle"
        )

        Box(
            modifier = modifier
                .size(80.dp)
                .border(width = 4.dp, color = Color.White, shape = CircleShape) // Outer white ring
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }, // Removes default grey ripple for a cleaner feel
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerCirclePadding)
                    // 3. Apply the rotation modifier dynamically
                    .rotate(if (isRecording) rotationAngle else 0f)
                    .background(
                        color = Color.Red,
                        shape = RoundedCornerShape(cornerRadius) // Morphing inner red indicator
                    )
            )
        }
    }

    @Composable
    fun RecordButtonContainer(
        isRecording: Boolean,
        recordingDurationMs: Long,
        onRecordClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // 1. LEFT HALF CONTAINER (0.0 to 0.5 of screen width)
            // Confines the timer badge to the left side so it CANNOT touch the button
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .align(Alignment.CenterStart)
                    // 48.dp = approx half button width (~36dp) + desired gap (~12dp)
                    .padding(end = 48.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                AnimatedVisibility(
                    visible = isRecording,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.Red, shape = CircleShape)
                        )
                        Text(
                            text = formatRecordingDuration(recordingDurationMs),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = TextStyle(
                                fontFeatureSettings = "tnum",     // 1. Forces OpenType Tabular Numbers (equal digit widths)
                                textMotion = TextMotion.Animated  // 2. Disables subpixel re-measurement jitter on fast updates
                            )
                        )
                    }
                }
            }

            // 2. RECORD BUTTON (Locked to the exact horizontal center of the screen)
            RecordButton(
                isRecording = isRecording,
                onClick = onRecordClick
            )
        }
    }

    fun openVideoFolder(context: Context) {
        // 1. Build an absolute SAF document pointer directly to the target directory
        // %3A represents the root separator (:), and %2F represents folder directory slashes (/)
        val folderAuthorityPath =
            "content://com.android.externalstorage.documents/document/primary%3AMovies%2FFilmasonde"
        val folderUri = folderAuthorityPath.toUri()

        // 2. Intent built using ACTION_VIEW combined with standard document folder MIME targets
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback safety layer: open standard generalized files app if deep-link structure breaks
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                // Final fallback
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecordingVideo(
        latitude: Double? = null,
        longitude: Double? = null,
        isHorizontal: Boolean,
        viewModel: LocationViewModel
    ) {
        val recording = activeRecording
        if (recording != null) return // A recording is already active

        subBuilder = TelemetrySubtitleBuilder()

        // 1. Generate the single temporary file in cache and lock its reference locally
        val tempFile = TelemetrySubtitleBuilder.createTempRecordingFile(this)
        currentTempVideoFile = tempFile

        videoCapture!!.targetRotation = if (isHorizontal) {
            Surface.ROTATION_90
        } else {
            Surface.ROTATION_0
        }
        // 2. Setup FileOutputOptions targeting your private cache file
        val fileOutputOptions = FileOutputOptions.Builder(tempFile)
            .setLocation(Location("gps").apply {
                this.latitude = latitude ?: 0.0
                this.longitude = longitude ?: 0.0
            })
            .build()

        // 3. Prepare the recording session using the FileOutputOptions
        val recordingBuilder = videoCapture?.output?.prepareRecording(this, fileOutputOptions)

        recordingBuilder?.withAudioEnabled()

        // 4. Start the recording stream
        activeRecording =
            recordingBuilder?.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d("CameraX", "Temporary cache recording started")
                        viewModel.onRecordingStarted()
                    }
                    is VideoRecordEvent.Status -> {
                        // Get accurate duration straight from the encoder (in nanoseconds)
                        val durationNanos = recordEvent.recordingStats.recordedDurationNanos
                        val durationMs = durationNanos / 1_000_000L

                        // Update your ViewModel or Compose state with durationMs
                        viewModel.onRecordingDurationUpdated(durationMs)
                    }
                    is VideoRecordEvent.Finalize -> {
                        viewModel.onRecordingFinalized()
                        if (!recordEvent.hasError()) {
                            val actualVideoDurationMs: Long =
                                recordEvent.recordingStats.recordedDurationNanos / 1_000_000
                            // Get your generated in-memory subtitle string
                            val inMemorySrt = subBuilder!!.build(actualVideoDurationMs)

                            Log.d("SUBS", inMemorySrt)

                            // Trigger the decoupled subtitle processing function using our locked file reference
                            TelemetrySubtitleBuilder.finaliseVideoWithSubtitles(
                                context = this@MainActivity,
                                tempVideoFile = tempFile,
                                inMemorySrtText = inMemorySrt
                            ) { result ->
                                // The processing is done! Switch back to Main thread for the UI
                                lifecycleScope.launch(Dispatchers.Main) {
                                    result.onSuccess { finalPublicFile ->
                                        val snackbarResult = snackbarHostState.showSnackbar(
                                            message = "Video saved to Gallery!",
                                            actionLabel = "Open folder",
                                            duration = SnackbarDuration.Indefinite
                                        )
                                        if (snackbarResult == SnackbarResult.ActionPerformed) {
                                            openVideoFolder(this@MainActivity)
                                        }
                                    }.onFailure { exception ->
                                        Log.e(
                                            "CameraX",
                                            "FFmpeg embedding failed: ${exception.localizedMessage}"
                                        )
                                    }
                                }
                            }
                        } else {
                            Log.e("CameraX", "Recording error: ${recordEvent.error}")
                            // Clean up cache file on an outright camera recording crash
                            tempFile.delete()
                        }

                        // Reset state references
                        activeRecording = null
                        currentTempVideoFile = null
                    }
                }
            }
    }

    fun stopRecordingVideo() {
        activeRecording?.stop()
        activeRecording = null
        if (currentTempVideoFile == null) return
    }
}
