/**
 * Main entry point for Firebase Cloud Functions.
 * Initializes Firebase Admin SDK ONCE.
 * Imports handler functions from other files.
 * Exports Cloud Functions using functions.https.onRequest, passing the imported handlers.
 */
const functions = require("firebase-functions");
const admin = require("firebase-admin");

// Initialize Firebase Admin SDK *ONCE*
// This automatically uses credentials from the Cloud Functions environment.
admin.initializeApp();
console.log("Firebase Admin SDK Initialized."); // Log for verification

// --- Register Device Function ---
// Import the specific handler function from registerDevice.js
const registerDeviceHandler = require("./registerDevice");
// Create and export the 'registerDevice' Cloud Function
exports.registerDevice = functions.https.onRequest(registerDeviceHandler);
console.log("Exported 'registerDevice' function."); // Log for verification

// --- Detect Function ---
// Import the specific handler function from detect.js
const detectHandler = require("./detect");
// Create and export the 'detect' Cloud Function
// Consider adding runtime options if needed (memory, timeout)
// e.g., functions.runWith({ memory: '1GB', timeoutSeconds: 120 }).https.onRequest(detectHandler);
exports.detect = functions.https.onRequest(detectHandler);
console.log("Exported 'detect' function."); // Log for verification