const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { Buffer } = require("buffer");
const path = require("path");
const os = require("os");
const fs = require("fs");
const { Storage } = require("@google-cloud/storage");

// Import the Orchestrator
const { runLocalizationOrchestrator } = require("./fireTri"); 

// --- Constants ---
const CONFIDENCE_THRESHOLD = 0.85; 
const MODEL_FILE_NAME = "cloud_best.onnx"; 
const TEMP_MODEL_PATH = path.join(os.tmpdir(), MODEL_FILE_NAME);
const BUCKET_NAME = "fireloc-e68b0.firebasestorage.app"; 
// MAX_CONFIDENCE_FOR_DEFAULT_BOX: If confidence is high, but the box is invalid, 
// we use a centered default box to attempt localization.
const MAX_CONFIDENCE_FOR_DEFAULT_BOX = 0.40; 

// --- Global State for ONNX Session Caching ---
let sessionPromise = null;

async function loadModelOnce() {
    // Lazy Load ONNX Runtime only when needed
    const ort = require("onnxruntime-node");
    
    if (sessionPromise) return sessionPromise;
    sessionPromise = (async () => {
        try {
            const storage = new Storage();
            const file = storage.bucket(BUCKET_NAME).file(MODEL_FILE_NAME);

            if (!fs.existsSync(TEMP_MODEL_PATH)) {
                await file.download({ destination: TEMP_MODEL_PATH });
            }
            
            return await ort.InferenceSession.create(TEMP_MODEL_PATH);
        } catch (err) {
            console.error("[ONNX] Critical error loading model:", err);
            sessionPromise = null;
            throw new Error("Failed to load ONNX model.");
        }
    })();
    return sessionPromise;
}

// --- Main Cloud Function Handler ---
module.exports = async (req, res) => {
    // Lazy Load Sharp only when request comes in
    const sharp = require("sharp");
    const ort = require("onnxruntime-node"); 

    if (req.method !== "POST") return res.status(405).send({ error: "Method Not Allowed" });

    const { deviceId, image_base64, timestamp_ms, location, mobile_detected } = req.body;
    
    if (!deviceId || !image_base64 || !timestamp_ms || !location) {
        return res.status(400).send({ error: "Missing required fields." });
    }

    // --- Image Processing ---
    let tensor;
    try {
        const imageBuffer = Buffer.from(image_base64, "base64");
        const { data } = await sharp(imageBuffer)
            .removeAlpha()
            .resize(640, 640, { fit: 'fill' })
            .raw()
            .toBuffer({ resolveWithObject: true });

        const floatArray = new Float32Array(data.length);
        for (let i = 0; i < data.length; i++) {
            floatArray[i] = data[i] / 255.0;
        }
        tensor = new ort.Tensor("float32", floatArray, [1, 3, 640, 640]);
    } catch (err) {
        console.error("Image processing failed:", err);
        // The client-side memory is now 1GiB, but the cloud function might still fail if the image is too large for its heap.
        return res.status(500).send({ error: "Failed to process image." });
    }

    // --- ONNX Inference ---
    let outputData;
    try {
        const session = await loadModelOnce();
        const results = await session.run({ [session.inputNames[0]]: tensor });
        outputData = results[session.outputNames[0]].data;
    } catch (err) {
        console.error("Inference failed:", err);
        return res.status(500).send({ error: "AI Inference failed." });
    }

    // --- Process Detections ---
    let fireDetected = false;
    let strongestDetection = null;
    const outputStride = 6; 

    if (outputData) {
        for (let i = 0; i < outputData.length; i += outputStride) {
            const [x1, y1, x2, y2, confidence, classId] = outputData.slice(i, i + outputStride);
            
            // Only consider detections above the confidence threshold
            if (confidence >= CONFIDENCE_THRESHOLD) {
                if (!strongestDetection || confidence > strongestDetection.confidence) {
                    strongestDetection = {
                        class_id: Math.round(classId),
                        confidence: confidence,
                        box_normalized: [
                            // FIX: Ensure coordinates are valid numbers and clamped
                            Math.max(0, Math.min(1, x1)),
                            Math.max(0, Math.min(1, y1)),
                            Math.max(0, Math.min(1, x2)),
                            Math.max(0, Math.min(1, y2)),
                        ]
                    };
                }
                fireDetected = true;
            }
        }
    }
    
    // --- Localization & Database Update ---
    const db = admin.firestore();
    const batch = db.batch();
    
    const deviceRef = db.collection("devices").doc(deviceId);

    try {
        // Only run localization if a high-confidence fire was detected
        if (fireDetected && strongestDetection) {
            
            // FIX: If the box is invalid (zero area), replace it with a centered default box
            const [xmin, ymin, xmax, ymax] = strongestDetection.box_normalized;
            if (xmax <= xmin || ymax <= ymin) {
                 console.warn(`[Detection] Invalid bounding box (${xmin},${ymin}) detected. Using default centered box.`);
                 // Default to a small, centered bounding box (0.4 to 0.6)
                 strongestDetection.box_normalized = [0.4, 0.4, 0.6, 0.6];
            }
            
            const detectionId = `${deviceId}_${Date.now()}`;
            const detectionRef = db.collection("detections").doc(detectionId);
            
            let firePositionGeoPoint = null;
            
            const detectionMetadata = {
                confidence: Math.min(1.0, strongestDetection.confidence / 1000) || 0,
                classId: strongestDetection.class_id,
                elevation: location.altitude || location.elevation || 0, 
                heading: location.heading || 0,
                pitch: location.pitch || 0,
                verticalFov: location.verticalFov,
                horizontalFov: location.horizontalFov
            };

            const localizedResult = await runLocalizationOrchestrator(
                location, 
                strongestDetection.box_normalized, 
                deviceId, 
                detectionMetadata
            );

            if (localizedResult && localizedResult.lat) {
                firePositionGeoPoint = new admin.firestore.GeoPoint(localizedResult.lat, localizedResult.lon);
            }

            batch.set(detectionRef, {
                deviceId,
                timestamp: admin.firestore.Timestamp.fromMillis(Number(timestamp_ms)),
                deviceLocation: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                results: [strongestDetection], // Store only the strongest
                firePosition: firePositionGeoPoint, 
                mobile_detected: mobile_detected || false
            });

            batch.set(deviceRef, {
                lastSeen: admin.firestore.FieldValue.serverTimestamp(),
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                status: "confirmed_fire"
            }, { merge: true });

        } else {
            console.log(`[Detection] No high-confidence fire found (Threshold: ${CONFIDENCE_THRESHOLD}). Status set to scanning.`);
            batch.set(deviceRef, {
                lastSeen: admin.firestore.FieldValue.serverTimestamp(),
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                status: "scanning"
            }, { merge: true });
        }

        await batch.commit();

    } catch (err) {
        console.error("Database error during localization:", err);
        // This is a database or network error (Python Orchestrator failure)
        return res.status(500).send({ error: "Database or Localization update failed." });
    }

    return res.status(200).json({
        status: fireDetected ? "processed_confirmed" : "processed_scanning",
        detected: fireDetected,
        results: strongestDetection ? [strongestDetection] : []
    });
};
