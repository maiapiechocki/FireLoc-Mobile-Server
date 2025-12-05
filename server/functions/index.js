/**
 * Main entry point for Firebase Cloud Functions.
 * Initializes Firebase Admin SDK ONCE.
 * Imports handler functions from other files.
 * Exports Cloud Functions using functions.https.onRequest.
 */
const functions = require("firebase-functions");
const admin = require("firebase-admin");

// Initialize Firebase Admin SDK *ONCE*
// We check apps.length to prevent "App already initialized" errors during hot reloads or tests
if (!admin.apps.length) {
    admin.initializeApp();
    console.log("Firebase Admin SDK Initialized."); 
}

// --- 1. Register Device Function ---
// Import the specific handler function from registerDevice.js
// Ensure registerDevice.js exists and exports a function (module.exports = ...)
const registerDeviceHandler = require("./registerDevice");
exports.registerDevice = functions.https.onRequest(registerDeviceHandler);

// --- 2. Detect Function ---
// Import the specific handler function from detect.js
// NOTE: detect.js now uses lazy loading for heavy libraries (sharp, onnx) to prevent timeouts
const detectHandler = require("./detect");
exports.detect = functions.https.onRequest(detectHandler);

// --- 3. Process Fire Alert Function (NEW LOCALIZATION SYSTEM) ---
// Import the orchestrator from fireTri.js
// **IMPORTANT:** Check capitalization. Linux servers are case-sensitive!
const { runLocalizationOrchestrator } = require('./fireTri');

exports.processFireAlert = functions.https.onRequest(async (req, res) => {
    try {
        // Extract data from the request body
        const { singleLocation, boundingBox, deviceId, detectionMetadata } = req.body;

        // Basic validation
        if (!singleLocation || !boundingBox || !deviceId) {
            console.warn(`[processFireAlert] Missing fields from device: ${deviceId}`);
            res.status(400).send({ error: "Missing required fields (singleLocation, boundingBox, deviceId)" });
            return;
        }

        // Run the localization logic (talks to Python via ngrok)
        const result = await runLocalizationOrchestrator(
            singleLocation, 
            boundingBox, 
            deviceId, 
            detectionMetadata
        );

        if (result) {
            res.status(200).send({ success: true, data: result });
        } else {
            // Logic ran, but calculation failed (e.g. timeout or bad data)
            res.status(200).send({ success: false, message: "Localization could not be determined." });
        }

    } catch (error) {
        console.error("Function Crash in processFireAlert:", error);
        res.status(500).send({ error: error.message });
    }
});