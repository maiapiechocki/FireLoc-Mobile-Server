const axios = require('axios');
const admin = require('firebase-admin');

const PYTHON_LOCALIZATION_URL = 'https://jimmy-sobersided-carson.ngrok-free.dev/localize'; 

const DEM_TIFF_PATH = 'USGS_one_meter_x36y378_CA_LosAngeles_2016.tif';

async function writeFireAlert(fireId, locationData) {
    const db = admin.firestore();
    const lat = Number(locationData.lat) || 0;
    const lon = Number(locationData.lon) || 0;
    
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
    console.log(`[Orchestrator] Starting for device: ${deviceId}`);

    // Validate inputs to prevent crashes
    if (!detectionMetadata) detectionMetadata = {};

    const singleDetection = {
        cameraId: deviceId,
        lat: singleLocation.latitude,
        lon: singleLocation.longitude,
        elevation: detectionMetadata.elevation || 0,
        heading: detectionMetadata.heading || 0,
        pitch: detectionMetadata.pitch || 0,
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
        console.log(`[Orchestrator] Calling Python at: ${PYTHON_LOCALIZATION_URL}`);
        
        const response = await axios.post(PYTHON_LOCALIZATION_URL, ensemblePayload, {
            timeout: 60000 // 60s timeout for heavy math
        });

        const finalLocation = response.data;

        // Verify we got valid coordinates back
        if (finalLocation && typeof finalLocation.lat === 'number') {
            const fireId = `fire_${deviceId}`; 
            // Keep original confidence if Python didn't generate a new one
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

// Export the function so index.js can use it
module.exports = { runLocalizationOrchestrator };