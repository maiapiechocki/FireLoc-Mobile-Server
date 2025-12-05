@file:Suppress("DEPRECATION")

package com.fireloc.fireloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.fireloc.fireloc.camera.BoundingBoxOverlay
import com.fireloc.fireloc.camera.CameraManager // <-- IMPORT CAMERA MANAGER
import com.fireloc.fireloc.camera.Detection
import com.fireloc.fireloc.camera.DetectionProcessor
import com.fireloc.fireloc.network.*
import com.fireloc.fireloc.utils.Prefs
import com.fireloc.fireloc.utils.SensorFusionManager // <-- IMPORT NEW SENSOR MANAGER
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() { // <-- REMOVED SensorEventListener

    companion object {
        private const val TAG = "FireLocMainActivity"
        // ADDED LOCATION_COARSE as a fallback
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private const val REGISTER_DEVICE_URL = "https://registerdevice-pppkiwepma-uc.a.run.app"
        private const val DETECT_URL = "https://detect-pppkiwepma-uc.a.run.app"
        private const val KEY_IS_DEVICE_REGISTERED = "is_device_registered"
        private const val CLOUD_CALL_COOLDOWN_MS = 5000L
    }

    // --- Components ---
    private lateinit var viewFinder: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var registerDeviceButton: Button

    private lateinit var cameraManager: CameraManager // <-- INITIALIZE CameraManager
    private lateinit var detectionProcessor: DetectionProcessor
    private lateinit var sensorFusionManager: SensorFusionManager // <-- INITIALIZE SensorFusionManager
    private lateinit var cloudVerificationService: CloudVerificationService // <-- INITIALIZE CloudVerificationService

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
    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { handlePermissionsResult(it) }
    private var lastCloudCallAttemptMs: Long = 0L

    // --- REDUNDANT FIELDS REMOVED ---
    // Removed sensorManager, accelerometer, magnetometer, gravity, geomagnetic, currentHeading, currentPitch, cameraFov*, lastKnownLocation
    // These are now managed internally by SensorFusionManager and CameraManager.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize core setup
        initializeViews()
        FirebaseApp.initializeApp(this)
        initializeFirebaseAppCheck()
        initializeFirebase()
        initializeNetworking()
        initializeGoogleSignIn()
        setupAuthStateListener() // Depends on Firebase/Google Sign-In
        setupButtonClickListeners()
        loadRegistrationStatus()

        // --- NEW/MODIFIED INITIALIZATION ORDER ---
        sensorFusionManager = SensorFusionManager(this) // Initialize sensor fusion
        cameraManager = CameraManager(this) // Initialize camera manager
        cloudVerificationService = CloudVerificationService(apiService) // Initialize cloud service

        // Initialize DetectionProcessor with all required dependencies
        detectionProcessor = DetectionProcessor(
            this,
            sensorFusionManager,
            cameraManager,
            cloudVerificationService,
            Prefs.getDeviceId(this) // Pass device ID
        ) { localDetections, sourceBitmap, width, height ->
            sourceImageWidth = width
            sourceImageHeight = height
            runOnUiThread {
                updateOverlayWithLocalDetections(localDetections)
                updateStatusTextBasedOnLocalDetections(localDetections)
                // Cloud detection is now triggered *internally* by DetectionProcessor if needed.
                // No need to manually trigger it here.
            }
        }
    }

    // --- ARCHITECTURAL METHODS ---

    private fun initializeViews() { try { viewFinder = findViewById(R.id.viewFinder); boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay); statusText = findViewById(R.id.statusText); signInButton = findViewById(R.id.signInButton); signOutButton = findViewById(R.id.signOutButton); registerDeviceButton = findViewById(R.id.registerDeviceButton); statusText.text = getString(R.string.initializing) } catch (e: IllegalStateException) { Log.e(TAG, "Error finding views.", e); finish() } }
    private fun initializeFirebaseAppCheck() { appCheck = FirebaseAppCheck.getInstance(); appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance()) }
    private fun initializeFirebase() { auth = Firebase.auth }
    private fun initializeNetworking() { apiService = ApiClient.instance }
    private fun setupAuthStateListener() { authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth -> val user = firebaseAuth.currentUser; updateUI(user); if (user != null) { if (allPermissionsGranted()) { startCameraAndSensors() } else { requestPermissions() } } else { try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) {}; statusText.text = getString(R.string.signed_out) } }; }
    private fun initializeGoogleSignIn() { val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build(); googleSignInClient = GoogleSignIn.getClient(this, gso); googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == RESULT_OK) { val task = GoogleSignIn.getSignedInAccountFromIntent(result.data); try { val account = task.getResult(ApiException::class.java)!!; firebaseAuthWithGoogle(account.idToken!!) } catch (e: ApiException) { statusText.text = getString(R.string.sign_in_failed_api_fmt, e.statusCode); updateUI(null) } } else { updateUI(null) } } }
    private fun loadRegistrationStatus() { val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE); isDeviceRegistered = prefs.getBoolean(KEY_IS_DEVICE_REGISTERED, false) }
    private fun saveRegistrationStatus(registered: Boolean) { isDeviceRegistered = registered; getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE).edit(commit = true) { putBoolean(KEY_IS_DEVICE_REGISTERED, registered) } }
    private fun setupButtonClickListeners() { signInButton.setOnClickListener { signIn() }; signOutButton.setOnClickListener { signOut() }; registerDeviceButton.setOnClickListener { registerDeviceWithBackend() } }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPermissions() { permissionsLauncher.launch(REQUIRED_PERMISSIONS) }
    private fun handlePermissionsResult(permissions: Map<String, Boolean>) { if (allPermissionsGranted()) { if (auth.currentUser != null) startCameraAndSensors() } else { Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show() } }
    private fun signIn() { googleSignInLauncher.launch(googleSignInClient.signInIntent) }
    private fun signOut() { auth.signOut(); googleSignInClient.signOut().addOnCompleteListener { updateUI(null) } }
    private fun firebaseAuthWithGoogle(idToken: String) { auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).addOnCompleteListener { if (!it.isSuccessful) updateUI(null) } }
    private fun updateUI(user: FirebaseUser?) { if (user != null) { statusText.text = getString(R.string.signed_in_fmt, user.email); signInButton.visibility = View.GONE; signOutButton.visibility = View.VISIBLE; registerDeviceButton.visibility = View.VISIBLE; registerDeviceButton.isEnabled = !isDeviceRegistered; registerDeviceButton.alpha = if (isDeviceRegistered) 0.5f else 1.0f } else { statusText.text = getString(R.string.signed_out); signInButton.visibility = View.VISIBLE; signOutButton.visibility = View.GONE; registerDeviceButton.visibility = View.GONE }; boundingBoxOverlay.updateDetections(emptyList()) }
    private fun registerDeviceWithBackend() { val user = auth.currentUser ?: return; if (isDeviceRegistered) return; lifecycleScope.launch { try { val token = user.getIdToken(true).await().token!!; val resp = withContext(Dispatchers.IO) { apiService.registerDevice(REGISTER_DEVICE_URL, "Bearer $token", DeviceRegistrationRequest(Prefs.getDeviceId(this@MainActivity))) }; if (resp.isSuccessful) saveRegistrationStatus(true).also { updateUI(user); Toast.makeText(this@MainActivity, "Registered", Toast.LENGTH_SHORT).show() } else Toast.makeText(this@MainActivity, "Failed", Toast.LENGTH_SHORT).show() } catch (e: Exception) { Toast.makeText(this@MainActivity, "Error", Toast.LENGTH_SHORT).show() } } }

    private fun startCameraAndSensors() {
        startCamera()
        sensorFusionManager.startListening() // <-- START SENSOR FUSION MANAGER
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        if (!allPermissionsGranted()) return

        // Use the CameraManager to bind the lifecycle and extract FOV (CameraManager handles its own setup)
        cameraManager.startCamera(
            this,
            viewFinder.surfaceProvider,
            ImageAnalysis.Analyzer { img ->
                // This is the core frame processing path
                sourceRotationDegrees = img.imageInfo.rotationDegrees
                detectionProcessor.processImageProxy(img)
            }
        )
        if (auth.currentUser != null) statusText.text = getString(R.string.scanning_status)
    }

    // --- REDUNDANT METHOD REMOVED: triggerCloudDetection ---
    // The complex logic previously here is now encapsulated in DetectionProcessor.kt

    // --- LIFECYCLE MANAGEMENT (Simplified) ---

    override fun onResume() {
        super.onResume()
        // If authenticated and permissions granted, restart sensors
        if (auth.currentUser != null && allPermissionsGranted()) {
            sensorFusionManager.startListening()
        }
        auth.addAuthStateListener(authStateListener)
    }

    override fun onPause() {
        super.onPause()
        sensorFusionManager.stopListening() // <-- STOP SENSORS
        auth.removeAuthStateListener(authStateListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.releaseCamera() // Release camera resources
        // Note: DetectionProcessor.release() is called internally by CameraManager when it unbinds
    }

    // --- UI/HELPER METHODS ---
    private fun calculateTransformMatrix(sw: Int, sh: Int, dw: Int, dh: Int, rot: Int): Matrix { val m = Matrix(); val usw = if (rot%180==90) sh.toFloat() else sw.toFloat(); val ush = if (rot%180==90) sw.toFloat() else sh.toFloat(); val s = min(dw/usw, dh/ush); m.postScale(usw*s, ush*s); m.postTranslate((dw-usw*s)/2f, (dh-ush*s)/2f); return m }
    private fun updateOverlayWithLocalDetections(detections: List<Detection>) { if (auth.currentUser == null) return; val m = calculateTransformMatrix(sourceImageWidth, sourceImageHeight, boundingBoxOverlay.width, boundingBoxOverlay.height, sourceRotationDegrees); boundingBoxOverlay.updateDetections(detections.mapNotNull { d -> val r = RectF(); m.mapRect(r, d.boundingBox); if(r.width()>0) Detection(d.type, d.confidence, r) else null }) }
    private fun updateStatusTextBasedOnLocalDetections(localDetections: List<Detection>) { if (auth.currentUser == null) return; if (localDetections.isNotEmpty()) { statusText.text = "Detected!" } else { statusText.text = "Scanning..." } }

}