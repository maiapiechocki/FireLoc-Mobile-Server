const axios = require('axios');
const admin = require('firebase-admin');

// Note: Ensure this URL is accessible from Firebase Functions (e.g., use ngrok for local dev, or the real Cloud Run URL for prod)
const PYTHON_LOCALIZATION_URL = 'https://jimmy-sobersided-carson.ngrok-free.dev/localize'; 
const DEM_TIFF_PATH = 'USGS_one_meter_x36y378_CA_LosAngeles_2016.tif';

async function writeFireAlert(fireId, locationData) {
    const db = admin.firestore();
    const lat = Number(locationData.lat) || 0;
    const lon = Number(locationData.lon) || 0;
    
    // Matches Screenshot Collection "FireAlerts"
    const fireAlertRef = db.collection('FireAlerts').doc(fireId);
    const geoPoint = new admin.firestore.GeoPoint(lat, lon);

    await fireAlertRef.set({
        location: geoPoint,
        elevation: locationData.elevation || 0,
        confidence: locationData.confidence || 0,
        timestamp: admin.firestore.FieldValue.serverTimestamp(),
        method: locationData.method || 'Unknown',
        status: 'active'
    }, { merge: true });

    console.log(`[Firestore] FireAlert written: ${fireId} -> [${lat}, ${lon}]`);
}

async function runLocalizationOrchestrator(singleLocation, boundingBox, deviceId, detectionMetadata) {
    if (!detectionMetadata) detectionMetadata = {};

    // *** CHANGED: Added FOV fields to payload ***
    const singleDetection = {
        cameraId: deviceId,
        lat: singleLocation.latitude,
        lon: singleLocation.longitude,
        elevation: detectionMetadata.elevation || 0,
        // These are now TRUE NORTH headings from Android
        heading: detectionMetadata.heading || 0,
        pitch: detectionMetadata.pitch || 0,
        // *** NEW: Pass FOV to Python for Ray Casting ***
        verticalFov: detectionMetadata.verticalFov || 45.0,   // Default approx if missing
        horizontalFov: detectionMetadata.horizontalFov || 60.0, // Default approx if missing
        // Bounding box
        xmin: boundingBox[0],
        ymin: boundingBox[1],
        xmax: boundingBox[2],
        ymax: boundingBox[3],
        confidence: detectionMetadata.confidence || 0
    };

    const ensemblePayload = {
        detections: [singleDetection], 
        dem_path: DEM_TIFF_PATH,
    };
    
    try {
        console.log(`[Orchestrator] Sending payload to Python:`, JSON.stringify(ensemblePayload));
        
        const response = await axios.post(PYTHON_LOCALIZATION_URL, ensemblePayload, {
            timeout: 60000 
        });

        const finalLocation = response.data;

        if (finalLocation && typeof finalLocation.lat === 'number') {
            const fireId = `fire_${deviceId}`; 
            finalLocation.confidence = finalLocation.confidence || detectionMetadata.confidence;
            
            await writeFireAlert(fireId, finalLocation);
            return finalLocation;
        } else {
            console.warn("[Orchestrator] Python returned invalid data:", finalLocation);
            return null;
        }

    } catch (error) {
        console.error("[Orchestrator] Localization request failed:", error.message);
        return null; 
    }
}

module.exports = { runLocalizationOrchestrator };