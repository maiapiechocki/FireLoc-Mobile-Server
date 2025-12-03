@file:Suppress("DEPRECATION") // Suppress Google Sign-In deprecation warnings

package com.fireloc.fireloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit // Use KTX for SharedPreferences
import androidx.lifecycle.lifecycleScope
import com.fireloc.fireloc.camera.BoundingBoxOverlay
import com.fireloc.fireloc.camera.Detection
import com.fireloc.fireloc.camera.DetectionProcessor
import com.fireloc.fireloc.camera.FireDetectionType
import com.fireloc.fireloc.network.*
import com.fireloc.fireloc.utils.Prefs
import com.fireloc.fireloc.utils.ImageUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
// **** REMOVED Play Integrity import ****
// import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
// **** ADDED Debug Provider import ****
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
// Remove any unused imports flagged by IDE

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FireLocMainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        private const val REGISTER_DEVICE_URL = "https://registerdevice-pppkiwepma-uc.a.run.app"
        private const val DETECT_URL = "https://detect-pppkiwepma-uc.a.run.app"
        private const val KEY_IS_DEVICE_REGISTERED = "is_device_registered"
        // Cooldown period for cloud detection calls in milliseconds
        // Keep cooldown, maybe reduce for debug testing?
        private const val CLOUD_CALL_COOLDOWN_MS = 5000L // 5 seconds for debug
    }

    // Views, Detection, State, Auth, Networking, Location, Permissions...
    private lateinit var viewFinder: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var registerDeviceButton: Button
    private lateinit var detectionProcessor: DetectionProcessor
    private lateinit var cameraExecutor: ExecutorService
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0
    private var sourceRotationDegrees: Int = 0
    private var isDeviceRegistered: Boolean = false
    private val isCloudDetectionInProgress = AtomicBoolean(false)
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInLauncher: ActivityResultLauncher<android.content.Intent>
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener
    private lateinit var appCheck: FirebaseAppCheck
    private lateinit var apiService: ApiService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionsResult(permissions)
    }
    // State for cooldown
    private var lastCloudCallAttemptMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        FirebaseApp.initializeApp(this)
        initializeFirebaseAppCheck() // Init with Debug Provider now
        initializeFirebase()
        setupAuthStateListener()
        initializeGoogleSignIn()
        initializeNetworking()
        initializeLocationClient()
        loadRegistrationStatus()
        setupButtonClickListeners()

        detectionProcessor = DetectionProcessor(this) { localDetections, sourceBitmap, width, height ->
            sourceImageWidth = width
            sourceImageHeight = height
            // sourceRotationDegrees set in startCamera analyzer

            runOnUiThread {
                updateOverlayWithLocalDetections(localDetections) // Call updated function
                if (localDetections.isNotEmpty() && sourceBitmap != null) {
                    triggerCloudDetection(sourceBitmap, localDetections)
                } else {
                    updateStatusTextBasedOnLocalDetections(localDetections)
                }
            }
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // --- Initialization Methods ---
    private fun initializeViews() { try { viewFinder = findViewById(R.id.viewFinder); boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay); statusText = findViewById(R.id.statusText); signInButton = findViewById(R.id.signInButton); signOutButton = findViewById(R.id.signOutButton); registerDeviceButton = findViewById(R.id.registerDeviceButton); statusText.text = getString(R.string.initializing) } catch (e: IllegalStateException) { Log.e(TAG, "Error finding views.", e); Toast.makeText(this, "Layout Error", Toast.LENGTH_LONG).show(); finish() } }

    // **** INITIALIZE WITH DEBUG PROVIDER ****
    private fun initializeFirebaseAppCheck() {
        appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance() // Use Debug Factory
        )
        Log.i(TAG, "Firebase App Check initialized with DEBUG provider.") // Log change
    }

    private fun initializeFirebase() { auth = Firebase.auth }
    private fun setupAuthStateListener() { authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth -> val user = firebaseAuth.currentUser; Log.d(TAG, "AuthStateListener triggered. User: ${user?.uid}"); updateUI(user); if (user != null) { if (allPermissionsGranted()) { startCamera() } else { if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { requestPermissions() } } } else { try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { Log.e(TAG, "Error unbinding camera on sign out", e) }; statusText.text = getString(R.string.signed_out) } }; }
    private fun initializeGoogleSignIn() { val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build(); googleSignInClient = GoogleSignIn.getClient(this, gso); googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == RESULT_OK) { val task = GoogleSignIn.getSignedInAccountFromIntent(result.data); try { val account = task.getResult(ApiException::class.java)!!; Log.d(TAG, "Google Sign In successful."); val idToken = account.idToken; if (idToken != null) { firebaseAuthWithGoogle(idToken) } else { Log.w(TAG, "Google ID Token was null."); statusText.text = getString(R.string.sign_in_failed_reason_fmt, "No Token"); updateUI(null) } } catch (e: ApiException) { Log.w(TAG, "Google sign in failed: ${e.statusCode}", e); statusText.text = getString(R.string.sign_in_failed_api_fmt, e.statusCode); updateUI(null) } } else { Log.w(TAG, "Google sign in activity cancelled/failed: ${result.resultCode}"); statusText.text = getString(R.string.sign_in_failed_result_fmt, result.resultCode); updateUI(null) } } }
    private fun initializeNetworking() { apiService = ApiClient.instance }
    private fun initializeLocationClient() { fusedLocationClient = LocationServices.getFusedLocationProviderClient(this); Log.d(TAG, "FusedLocationProviderClient initialized.") }
    private fun loadRegistrationStatus() { val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE); isDeviceRegistered = prefs.getBoolean(KEY_IS_DEVICE_REGISTERED, false); Log.d(TAG, "Loaded registration status: $isDeviceRegistered") }
    private fun saveRegistrationStatus(registered: Boolean) { isDeviceRegistered = registered; val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE); prefs.edit(commit = true) { putBoolean(KEY_IS_DEVICE_REGISTERED, registered) }; Log.d(TAG, "Saved registration status: $registered") }
    private fun setupButtonClickListeners() { signInButton.setOnClickListener { signIn() }; signOutButton.setOnClickListener { signOut() }; registerDeviceButton.setOnClickListener { registerDeviceWithBackend() } }


    // --- Permissions Handling ---
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPermissions() { Log.i(TAG, "Requesting permissions: ${REQUIRED_PERMISSIONS.joinToString()}"); permissionsLauncher.launch(REQUIRED_PERMISSIONS) }
    private fun handlePermissionsResult(permissions: Map<String, Boolean>) { val allGranted = allPermissionsGranted(); if (allGranted) { Log.i(TAG, "All required permissions granted."); if (auth.currentUser != null) { startCamera() } } else { Log.w(TAG, "One or more permissions were denied."); val deniedPermissions = REQUIRED_PERMISSIONS.filterNot { permissions.getOrDefault(it, false) }; val message = "Required permissions denied: ${deniedPermissions.joinToString { it.substringAfterLast('.') }}"; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); statusText.text = getString(R.string.permissions_required); } }


    // --- Authentication Logic ---
    private fun signIn() { Log.i(TAG, "Initiating Google Sign-In."); statusText.text = getString(R.string.signing_in); val signInIntent = googleSignInClient.signInIntent; googleSignInLauncher.launch(signInIntent) }
    private fun signOut() { Log.i(TAG, "Initiating Sign Out."); statusText.text = getString(R.string.signed_out); auth.signOut(); googleSignInClient.signOut().addOnCompleteListener(this) { Log.d(TAG, "Google Sign-Out task complete."); Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show(); } }
    private fun firebaseAuthWithGoogle(idToken: String) { val credential = GoogleAuthProvider.getCredential(idToken, null); Log.d(TAG, "Calling Firebase auth.signInWithCredential..."); statusText.text = getString(R.string.authenticating_firebase); auth.signInWithCredential(credential).addOnCompleteListener(this) { task -> Log.d(TAG, "Firebase signInWithCredential complete. Task Successful: ${task.isSuccessful}"); if (!task.isSuccessful) { Log.w(TAG, "Firebase signInWithCredential:failure", task.exception); statusText.text = getString(R.string.sign_in_failed_firebase_auth); Toast.makeText(baseContext, "Firebase Authentication Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show(); updateUI(null) } } }
    private fun updateUI(user: FirebaseUser?) { /* ... (Implementation using getString as before) ... */ Log.d(TAG, "Updating UI for user: ${user?.uid}"); if (user != null) { statusText.text = getString(R.string.signed_in_fmt, user.email ?: "User"); signInButton.visibility = View.GONE; signOutButton.visibility = View.VISIBLE; registerDeviceButton.visibility = View.VISIBLE; registerDeviceButton.isEnabled = !isDeviceRegistered; registerDeviceButton.alpha = if (isDeviceRegistered) 0.5f else 1.0f } else { statusText.text = getString(R.string.signed_out); signInButton.visibility = View.VISIBLE; signOutButton.visibility = View.GONE; registerDeviceButton.visibility = View.GONE }; boundingBoxOverlay.updateDetections(emptyList()) }


    // --- Device Registration Logic ---
    private fun registerDeviceWithBackend() { val currentUser = auth.currentUser; if (currentUser == null) { Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show(); return }; if (isDeviceRegistered) { Toast.makeText(this, R.string.device_already_registered, Toast.LENGTH_SHORT).show(); return }; val deviceId = Prefs.getDeviceId(this); Log.d(TAG, "Device ID: $deviceId"); statusText.text = getString(R.string.getting_auth_token); lifecycleScope.launch { try { val idTokenResult = currentUser.getIdToken(true).await(); val idToken = idTokenResult?.token ?: throw Exception("Failed to get ID token."); statusText.text = getString(R.string.registering_device); val requestBody = DeviceRegistrationRequest(deviceId = deviceId); val authHeader = "Bearer $idToken"; val response = withContext(Dispatchers.IO) { apiService.registerDevice(REGISTER_DEVICE_URL, authHeader, requestBody) }; if (response.isSuccessful) { Log.i(TAG, "Device registration successful (HTTP ${response.code()})"); saveRegistrationStatus(true); updateUI(currentUser); Toast.makeText(applicationContext, R.string.device_registered, Toast.LENGTH_SHORT).show(); statusText.text = getString(R.string.scanning_status) } else { val errorBody = response.errorBody()?.string() ?: "?"; Log.e(TAG, "Device registration failed (HTTP ${response.code()}): $errorBody"); statusText.text = getString(R.string.registration_failed); Toast.makeText(applicationContext, getString(R.string.registration_failed_code_fmt, response.code()), Toast.LENGTH_LONG).show(); updateUI(currentUser) } } catch (e: Exception) { Log.e(TAG, "Exception during registration token fetch or API call", e); statusText.text = getString(R.string.registration_error_fmt, e.localizedMessage ?: "Unknown error"); Toast.makeText(applicationContext, R.string.registration_network_error, Toast.LENGTH_LONG).show(); updateUI(currentUser) } } }


    // --- CameraX Logic ---
    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() { if (!allPermissionsGranted()) { return }; Log.d(TAG, "Attempting to start CameraX."); if (auth.currentUser != null) statusText.text = getString(R.string.initializing); val cameraProviderFuture = ProcessCameraProvider.getInstance(this); cameraProviderFuture.addListener({ try { val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }; val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build().also { it.setAnalyzer(cameraExecutor) { imageProxy -> sourceRotationDegrees = imageProxy.imageInfo.rotationDegrees; detectionProcessor.processImageProxy(imageProxy) } }; val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA; cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer); Log.i(TAG, "CameraX bound successfully."); if (auth.currentUser != null) statusText.text = getString(R.string.scanning_status) } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc); statusText.text = getString(R.string.camera_error); Toast.makeText(this, getString(R.string.camera_error_detail_fmt, exc.message ?: "Unknown"), Toast.LENGTH_LONG).show() } }, ContextCompat.getMainExecutor(this)) }


    // --- Location Fetching ---
    private fun hasLocationPermission(): Boolean = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED


    // --- Detection Result Handling & Cloud Trigger ---
    // **** KEPT YOUR REVISED calculateTransformMatrix ****
    private fun calculateTransformMatrix(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, rotation: Int): Matrix { val matrix = Matrix(); val uprightSrcWidth: Float; val uprightSrcHeight: Float; if (rotation == 90 || rotation == 270) { uprightSrcWidth = srcHeight.toFloat(); uprightSrcHeight = srcWidth.toFloat() } else { uprightSrcWidth = srcWidth.toFloat(); uprightSrcHeight = srcHeight.toFloat() }; if (uprightSrcWidth <= 0 || uprightSrcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) { Log.e(TAG, "Invalid dimensions for matrix calculation."); return matrix }; val scaleX = dstWidth.toFloat() / uprightSrcWidth; val scaleY = dstHeight.toFloat() / uprightSrcHeight; val scale = min(scaleX, scaleY); val scaledWidth = uprightSrcWidth * scale; val scaledHeight = uprightSrcHeight * scale; val dx = (dstWidth - scaledWidth) / 2f; val dy = (dstHeight - scaledHeight) / 2f; matrix.postScale(scaledWidth, scaledHeight); matrix.postTranslate(dx, dy); return matrix }
    // **** KEPT YOUR REVISED updateOverlayWithLocalDetections ****
    private fun updateOverlayWithLocalDetections(localDetections: List<Detection>) { if (auth.currentUser == null) { boundingBoxOverlay.updateDetections(emptyList()); return }; if (boundingBoxOverlay.width == 0 || boundingBoxOverlay.height == 0 || sourceImageWidth == 0 || sourceImageHeight == 0) { return }; val transform = calculateTransformMatrix(sourceImageWidth, sourceImageHeight, boundingBoxOverlay.width, boundingBoxOverlay.height, sourceRotationDegrees); val viewDetections = localDetections.mapNotNull { detection -> val viewRect = RectF(); transform.mapRect(viewRect, detection.boundingBox); viewRect.left = max(0f, viewRect.left); viewRect.top = max(0f, viewRect.top); viewRect.right = min(boundingBoxOverlay.width.toFloat(), viewRect.right); viewRect.bottom = min(boundingBoxOverlay.height.toFloat(), viewRect.bottom); if (viewRect.width() > 0 && viewRect.height() > 0) { Detection(detection.type, detection.confidence, viewRect) } else { null } }; boundingBoxOverlay.updateDetections(viewDetections) }
    private fun updateStatusTextBasedOnLocalDetections(localDetections: List<Detection>) { if (auth.currentUser == null) { statusText.text = getString(R.string.signed_out); return }; if (localDetections.isNotEmpty()) { val fireCount = localDetections.count { it.type == FireDetectionType.FIRE }; val smokeCount = localDetections.count { it.type == FireDetectionType.SMOKE }; val maxConfidence = localDetections.maxOfOrNull { it.confidence } ?: 0f; statusText.text = getString(R.string.local_detection_status_fmt, fireCount, smokeCount, (maxConfidence * 100).toInt()) } else { val processingTime = detectionProcessor.getLastProcessingTimeMs(); statusText.text = getString(R.string.scanning_status_time_fmt, getString(R.string.scanning_status), processingTime) } }

    // **** Includes cooldown and tries cached token first ****
    private fun triggerCloudDetection(sourceBitmap: Bitmap, localDetections: List<Detection>) {
        val currentTimeMs = System.currentTimeMillis()
        // Cooldown Check
        if (currentTimeMs - lastCloudCallAttemptMs < CLOUD_CALL_COOLDOWN_MS) {
            Log.v(TAG, "Skipping cloud detection trigger due to cooldown.")
            return
        }
        if (!isCloudDetectionInProgress.compareAndSet(false, true)) { return }
        lastCloudCallAttemptMs = currentTimeMs // Record attempt time

        val currentUser = auth.currentUser ?: run { Log.w(TAG,"User signed out"); isCloudDetectionInProgress.set(false); return }
        if (!isDeviceRegistered) { Toast.makeText(this, R.string.device_not_registered, Toast.LENGTH_SHORT).show(); isCloudDetectionInProgress.set(false); return }
        if (!hasLocationPermission()) { Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show(); isCloudDetectionInProgress.set(false); return }

        statusText.text = getString(R.string.getting_data_for_cloud)
        lifecycleScope.launch {
            var idToken: String? = null
            var appCheckTokenValue: String? = null
            var locationData: LocationData? = null
            var base64Image: String? = null
            var success = false
            try {
                statusText.text = getString(R.string.getting_auth_token)
                idToken = currentUser.getIdToken(true).await()?.token ?: throw Exception("Failed to get User ID token.")
                val authHeader = "Bearer $idToken"

                statusText.text = getString(R.string.getting_app_check_token)
                // Try cached token first
                appCheckTokenValue = appCheck.getAppCheckToken(false).await()?.token
                    ?: run {
                        Log.w(TAG, "Cached App Check token invalid/missing, forcing refresh.")
                        appCheck.getAppCheckToken(true).await()?.token // Fallback to refresh
                    }
                            ?: throw Exception("Failed to get App Check token even after forced refresh.")

                statusText.text = getString(R.string.getting_location)
                @SuppressLint("MissingPermission")
                val locationResult: Location? = try {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation failed ($e), trying lastLocation")
                    try { fusedLocationClient.lastLocation.await() } catch (e2: Exception) { null }
                }
                locationData = locationResult?.let { LocationData(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
                if (locationData == null) Log.w(TAG, "Proceeding without location data.")

                statusText.text = getString(R.string.preparing_image)
                val deviceId = Prefs.getDeviceId(this@MainActivity)
                val timestamp = System.currentTimeMillis()
                base64Image = withContext(Dispatchers.IO) { ImageUtils.bitmapToBase64(sourceBitmap) } ?: throw Exception("Base64 image encoding failed")

                val detectRequest = DetectRequest( deviceId = deviceId, imageBase64 = base64Image, timestampMs = timestamp, location = locationData, mobileDetected = true )
                statusText.text = getString(R.string.sending_to_cloud)

                val response = withContext(Dispatchers.IO) { apiService.detect(DETECT_URL, authHeader, appCheckTokenValue, detectRequest) }

                if (response.isSuccessful) {
                    val respBody = response.body(); Log.i(TAG, "/detect SUCCESS (HTTP ${response.code()})")
                    val confirmationText = if (respBody?.detected == true) getString(R.string.cloud_confirmed) else getString(R.string.cloud_not_confirmed)
                    statusText.text = getString(R.string.cloud_status_fmt, respBody?.status ?: getString(R.string.cloud_processed), confirmationText)
                    success = true
                } else {
                    val errorBody = response.errorBody()?.string() ?: "?"; Log.e(TAG, "/detect FAILED (HTTP ${response.code()}): $errorBody")
                    if (response.code() == 401 && errorBody.contains("App Check", ignoreCase = true)) {
                        statusText.text = getString(R.string.app_check_failed_error)
                        Toast.makeText(applicationContext, R.string.app_check_failed_toast, Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = getString(R.string.cloud_detect_failed_code_fmt, response.code())
                        Toast.makeText(applicationContext, getString(R.string.cloud_error_code_fmt, response.code()), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception in cloud detection trigger coroutine", e)
                val errorMsgId = when {
                    e.message?.contains("Too many attempts", ignoreCase = true) == true -> R.string.app_check_failed_error
                    idToken == null -> R.string.auth_token_error
                    appCheckTokenValue == null -> R.string.app_check_failed_error
                    else -> R.string.cloud_process_error
                }
                val detail = if(errorMsgId == R.string.app_check_failed_error) "Rate Limited" else e.localizedMessage?.take(50) ?: "Unknown"
                statusText.text = getString(R.string.cloud_process_error_detail_fmt, detail)
                Toast.makeText(applicationContext, errorMsgId, Toast.LENGTH_LONG).show()

            } finally {
                isCloudDetectionInProgress.set(false)
                if (!success) { updateStatusTextBasedOnLocalDetections(localDetections) }
            }
        }
    }


    // --- Lifecycle ---
    override fun onStart() { super.onStart(); auth.addAuthStateListener(authStateListener); Log.d(TAG, "AuthStateListener registered."); if (allPermissionsGranted() && auth.currentUser != null) { startCamera() } else if (!allPermissionsGranted()) { requestPermissions() } }
    override fun onStop() { super.onStop(); auth.removeAuthStateListener(authStateListener); Log.d(TAG, "AuthStateListener unregistered."); try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { Log.e(TAG,"Error unbinding camera onStop", e) } }
    override fun onDestroy() { super.onDestroy(); if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) { cameraExecutor.shutdown() }; if (::detectionProcessor.isInitialized) { detectionProcessor.release() }; Log.d(TAG, "MainActivity onDestroy completed.") }

} // End of MainActivity class