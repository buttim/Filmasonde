package eu.ydiaeresis.filmasonde

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.use
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import androidx.camera.core.Preview as CamPreview

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
data class SondePosition(
    val frame: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double,
)

class MainActivity : ComponentActivity() {
    val mqttJson = Json {
        ignoreUnknownKeys = true // Prevents crashes if your broker adds new fields
        coerceInputValues = true // Helps if, for example, a number is sent as a string
    }
    val client = MqttClient("TrovaLaSonda") {
        keepAliveSeconds = 30
        autoReconnect = true
    }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val snackbarHostState = SnackbarHostState()

    private var serial: String? = null

    fun getLocationFlow(
        fusedLocationClient: FusedLocationProviderClient,
        locationRequest: LocationRequest
    ) = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        try {
            // Request ongoing background updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e) // Safely shut down flow if permissions are missing
        }

        // Automatically clears the GPS listener when the composable leaves the screen
        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO])
    @Composable
    fun MainScreen(
        hasLocationPermission: Boolean,
        hasCameraPermission: Boolean,
        hasRecordingAudioPermission: Boolean,
        onLocationPermissionRequest: () -> Unit,
        onRecordingAudioPermissionRequest: () -> Unit,
        onCameraPermissionRequest: () -> Unit
    ) {
        val context = LocalContext.current

        // User GPS States
        var isLocationReady by remember { mutableStateOf(false) }
        var userLatitude by remember { mutableDoubleStateOf(0.0) }
        var userLongitude by remember { mutableDoubleStateOf(0.0) }
        var userAltitude by remember { mutableDoubleStateOf(0.0) }
        var isTargetLoaded by remember { mutableStateOf(false) }
        var noSonde by remember { mutableStateOf(false) }
        var serial by remember { mutableStateOf("[no sonde]") }

        var statusText by remember { mutableStateOf("Initializing") }

        // Moving Target States
        var targetLatitude by remember { mutableDoubleStateOf(0.0) }
        var targetLongitude by remember { mutableDoubleStateOf(0.0) }
        var targetAltitude by remember { mutableDoubleStateOf(0.0) }

        var isRecording by remember { mutableStateOf(false) }

        // Initialized as an Identity Matrix so it never defaults to NaN or freezes on emulators.
        var rotationMatrix by remember {
            mutableStateOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        }

        val fusedLocationClient = remember {
            LocationServices.getFusedLocationProviderClient(context)
        }

        // 1. COMPASS & ROTATION MATRIX LISTENER
        DisposableEffect(Unit) {
            val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val matrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(matrix, event.values)
                        rotationMatrix = matrix // Stream matrix data directly to our canvas
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

        // 2. GPS LIVE STREAM CONTROL
        LaunchedEffect(hasLocationPermission) {
            if (!hasLocationPermission) return@LaunchedEffect
            statusText = "Waiting for GPS..."
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                5000L // Check for updates every 5 seconds
            ).build()
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
                                maxDistance = 100000,
                                maxSeconds = 0
                            )
                            Log.i("MQTT", "Sonde: $sonde")
                            if (sonde == null) {
                                noSonde = true
                                statusText = "No nearby sonde. Please retry later"
                            } else {
                                serial = sonde.serial
                                this@MainActivity.serial = serial
                                targetLatitude = sonde.lat
                                targetLongitude = sonde.lon
                                targetAltitude = sonde.alt
                                statusText = "Contacting Sondehub server..."
                                delay(500.milliseconds)
                                isTargetLoaded = true
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
                        statusText = "Network sync failed."
                    }
                }
        }

        // --- SCREEN HUD RENDERING ---
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
                    Text(resources.getString(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(120.dp))
                    Text("Permissions required", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onCameraPermissionRequest, enabled = !hasCameraPermission) {
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = serial,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Green,
                        modifier = Modifier
                            .padding(top = 40.dp)
                            .clickable {
                                Log.i("INFO", "click su $serial")
                                startActivity(Intent(Intent.ACTION_VIEW).setData("http://sondehub.org/$serial".toUri()))
                            }
                    )
                    val distance = if (userLatitude == 0.0 || userLongitude == 0.0 || targetLatitude==0.0 || targetLongitude==0.0) 0
                    else Location("manual").apply {
                        latitude = userLatitude; longitude = userLongitude; altitude = userAltitude
                    }
                        .euclideanDistanceTo(Location("manual").apply {
                            latitude = targetLatitude; longitude = targetLongitude; altitude =
                            targetAltitude
                        })
                        .toInt()
                    Text(
                        if (distance==0) "---" else if (distance > 5000) "%.1fkm".format(distance / 1000.0) else "${distance}m",
                        fontSize = 15.sp,
                        color = Color.Yellow,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                SondeAROverlay(
                    userLatitude = { userLatitude },
                    userLongitude = { userLongitude },
                    userAltitude = { userAltitude },
                    targetLatitude = { targetLatitude },
                    targetLongitude = { targetLongitude },
                    targetAltitude = { targetAltitude },
                    rotationMatrix = { rotationMatrix },
                    modifier = Modifier.fillMaxSize()
                )
                RecordButton(
                    isRecording = isRecording,
                    onClick = {
                        isRecording = !isRecording
                        if (isRecording) {
                            startRecordingVideo()
                        } else {
                            stopRecordingVideo()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp) // Pushes it up slightly from the edge
                )
                if (!isTargetLoaded) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable {}
                            .background(Color.Black.copy(alpha = 0.7f))) {
                        Text(
                            text = statusText,
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
                    snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
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

    @Composable
    fun SondeAROverlay(
        userLatitude: () -> Double,
        userLongitude: () -> Double,
        userAltitude: () -> Double,
        targetLatitude: () -> Double,
        targetLongitude: () -> Double,
        targetAltitude: () -> Double,
        rotationMatrix: () -> FloatArray,
        modifier: Modifier = Modifier
    ) {
        Box(modifier = modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Reading state lambdas strictly inside the Canvas block
                    // tells Compose to skip Recomposition entirely and only perform a Redraw.
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

                        val horizontalFov = Math.toRadians(60.0)
                        val verticalFov = Math.toRadians(45.0)
                        val forwardDistance = -zDevice

                        val centerX = size.width / 2f
                        val centerY = size.height / 2f

                        val angleX = atan2(xDevice.toDouble(), forwardDistance.toDouble())
                        val angleY = atan2(yDevice.toDouble(), forwardDistance.toDouble())

                        val rectCenterX =
                            centerX + ((angleX / horizontalFov).toFloat() * size.width)
                        val rectCenterY = centerY - ((angleY / verticalFov).toFloat() * size.height)

                        val isOnScreen = zDevice < 0 &&
                                rectCenterX in 0f..size.width &&
                                rectCenterY in 0f..size.height

// Inside SondeAROverlay's Canvas block, replace the 'if (isOnScreen)' block with this:

                        if (isOnScreen) {
                            // --- Define the FPV Style ---
                            val targetColor = Color(0xFF00FFCC) // Keep that nice neon teal
                            val totalSize = 75.dp.toPx()       // The boundary of the overall target
                            val cornerLen = 18.dp.toPx()       // How long the L-shaped arms are
                            val strokeW = 2.5.dp.toPx()        // Thin, sharp lines for a HUD look

                            // Calculate the bounding box based on totalSize
                            val left = rectCenterX - totalSize / 2
                            val top = rectCenterY - totalSize / 2
                            val right = rectCenterX + totalSize / 2
                            val bottom = rectCenterY + totalSize / 2

                            // --- Draw the 4 Corners (FPV Style) ---

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
                                val length = 32
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
        // Smoothly animate the corner radius and size when clicked
        val cornerRadius by animateDpAsState(
            targetValue = if (isRecording) 12.dp else 40.dp,
            label = "shape"
        )
        val innerCirclePadding by animateDpAsState(
            targetValue = if (isRecording) 20.dp else 6.dp,
            label = "size"
        )

        Box(
            modifier = modifier
                .size(80.dp)
                .border(width = 4.dp, color = Color.White, shape = CircleShape) // Outer white ring
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) // Removes default grey ripple for a cleaner feel
                { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerCirclePadding)
                    .background(
                        color = Color.Red,
                        shape = RoundedCornerShape(cornerRadius) // Morphing inner red indicator
                    )
            )
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecordingVideo() {
        val recording = activeRecording
        if (recording != null) return // A recording is already active

        val filename = "${serial}_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Filmasonde")
            }
        }
        // 2. Create the MediaStoreOutputOptions target container
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // 3. Setup the builder pipeline using your updated MediaStore targets
        val recordingBuilder = videoCapture?.output?.prepareRecording(this, mediaStoreOutputOptions)
        // Trigger capture configuration pipeline
        // 4. Start the recording stream
        activeRecording =
            recordingBuilder?.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d("CameraX", "Public gallery recording started")
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                snackbarHostState.showSnackbar(
                                    message = "Video saved to Gallery!",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        } else {
                            Log.e("CameraX", "Recording error: ${recordEvent.error}")
                        }
                        activeRecording = null
                    }
                }
            }
    }

    fun stopRecordingVideo() {
        activeRecording?.stop()
        activeRecording = null
    }
}
