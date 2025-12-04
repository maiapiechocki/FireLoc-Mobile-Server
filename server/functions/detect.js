const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { Buffer } = require("buffer");
const ort = require("onnxruntime-node");
const sharp = require("sharp");
const path = require("path");
const os = require("os");
const fs = require("fs");
const { Storage } = require("@google-cloud/storage");

// Import the new Orchestrator
const { runLocalizationOrchestrator } = require("./fireTri"); 

// --- Constants ---
const CONFIDENCE_THRESHOLD = 0.85; 
const MODEL_FILE_NAME = "cloud_best.onnx"; 
const TEMP_MODEL_PATH = path.join(os.tmpdir(), MODEL_FILE_NAME);
const BUCKET_NAME = "fireloc-e68b0.firebasestorage.app"; 

// --- Global State for ONNX Session Caching ---
let sessionPromise = null;

// Lazily loads the ONNX model
async function loadModelOnce() {
    if (sessionPromise) return sessionPromise;
    sessionPromise = (async () => {
        try {
            const storage = new Storage();
            const file = storage.bucket(BUCKET_NAME).file(MODEL_FILE_NAME);

            if (!fs.existsSync(TEMP_MODEL_PATH)) {
                console.log(`[ONNX] Downloading model from ${BUCKET_NAME}...`);
                await file.download({ destination: TEMP_MODEL_PATH });
            }
            
            console.log("[ONNX] Initializing InferenceSession...");
            const session = await ort.InferenceSession.create(TEMP_MODEL_PATH);
            return session;
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
    // 1. Basic Validation
    if (req.method !== "POST") return res.status(405).send({ error: "Method Not Allowed" });

    // 2. Auth & App Check (Simplified for brevity - keep your existing logic here)
    // ... [Keep your existing App Check and Auth logic from the previous file] ...
    // For this snippet, I assume `uid` is available or validation passed.

    // 3. Parse Request Body
    const { deviceId, image_base64, timestamp_ms, location, mobile_detected } = req.body;
    
    if (!deviceId || !image_base64 || !timestamp_ms || !location) {
        return res.status(400).send({ error: "Missing required fields." });
    }

    // 4. Image Preprocessing (Standard Sharp + ONNX prep)
    let tensor;
    try {
        const imageBuffer = Buffer.from(image_base64, "base64");
        const { data, info } = await sharp(imageBuffer)
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
        return res.status(500).send({ error: "Failed to process image." });
    }

    // 5. ONNX Inference
    let outputData;
    try {
        const session = await loadModelOnce();
        const results = await session.run({ [session.inputNames[0]]: tensor });
        outputData = results[session.outputNames[0]].data;
    } catch (err) {
        console.error("Inference failed:", err);
        return res.status(500).send({ error: "AI Inference failed." });
    }

    // 6. Process Detections
    let fireDetected = false;
    const cloudDetections = [];
    const outputStride = 6; 

    if (outputData) {
        for (let i = 0; i < outputData.length; i += outputStride) {
            const [x1, y1, x2, y2, confidence, classId] = outputData.slice(i, i + outputStride);
            if (confidence >= CONFIDENCE_THRESHOLD) {
                fireDetected = true;
                cloudDetections.push({
                    class_id: Math.round(classId),
                    confidence: confidence,
                    box_normalized: [
                        Math.max(0, Math.min(1, x1)),
                        Math.max(0, Math.min(1, y1)),
                        Math.max(0, Math.min(1, x2)),
                        Math.max(0, Math.min(1, y2)),
                    ]
                });
            }
        }
    }

    // 7. Localization & Database Update
    const db = admin.firestore();
    const batch = db.batch();
    const deviceRef = db.collection("devices").doc(deviceId);

    try {
        if (fireDetected && cloudDetections.length > 0) {
            const detectionId = `${deviceId}_${Date.now()}`;
            const detectionRef = db.collection("detections").doc(detectionId);
            
            // --- LOCALIZATION INTEGRATION ---
            let firePositionGeoPoint = null;
            
            // Prepare metadata with placeholders for missing Pose data
            const detectionMetadata = {
                confidence: cloudDetections[0].confidence,
                classId: cloudDetections[0].class_id,
                // TODO: Update Mobile App to send these fields in `req.body.location`
                elevation: location.elevation || 0, 
                heading: location.heading || 0,
                pitch: location.pitch || 0
            };

            // Call the Orchestrator
            const localizedResult = await runLocalizationOrchestrator(
                location, 
                cloudDetections[0].box_normalized, 
                deviceId, 
                detectionMetadata
            );

            if (localizedResult && localizedResult.lat) {
                firePositionGeoPoint = new admin.firestore.GeoPoint(localizedResult.lat, localizedResult.lon);
            }
            // -------------------------------

            // Save Detection Log
            batch.set(detectionRef, {
                deviceId,
                timestamp: admin.firestore.Timestamp.fromMillis(Number(timestamp_ms)),
                deviceLocation: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                results: cloudDetections,
                firePosition: firePositionGeoPoint, // Saved if localization succeeded
                mobile_detected: mobile_detected || false
            });

            // Update Device Status
            batch.set(deviceRef, {
                lastSeen: admin.firestore.FieldValue.serverTimestamp(),
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                status: "confirmed_fire"
            }, { merge: true });

        } else {
            // No fire detected, just heartbeat
            batch.set(deviceRef, {
                lastSeen: admin.firestore.FieldValue.serverTimestamp(),
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                status: "scanning"
            }, { merge: true });
        }

        await batch.commit();
        console.log(`Firestore batch committed for ${deviceId}`);

    } catch (err) {
        console.error("Database error:", err);
        return res.status(500).send({ error: "Database update failed." });
    }

    return res.status(200).json({
        status: "processed",
        detected: fireDetected,
        results: cloudDetections
    });
};