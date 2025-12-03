/**
 * Handles the logic for the /registerDevice endpoint.
 * Verifies user authentication via Firebase ID Token.
 * Writes device information to Firestore atomically.
 * Exports ONLY the request handler function.
 */
const functions = require("firebase-functions"); // Good practice, optional in JS
const admin = require("firebase-admin");

// Helper function to verify Firebase ID Token from Authorization header
async function verifyFirebaseIdToken(req) {
    const authHeader = req.headers.authorization;

    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        console.warn("Verification failed: Missing or invalid Authorization header.");
        throw new Error("Missing or invalid Authorization header");
    }

    const idToken = authHeader.split("Bearer ")[1];

    if (!idToken) {
        console.warn("Verification failed: No token found after 'Bearer '.");
        throw new Error("Missing token in Authorization header");
    }

    try {
        console.log("Verifying Firebase ID token...");
        const decodedToken = await admin.auth().verifyIdToken(idToken);
        console.log(`Token verified successfully for UID: ${decodedToken.uid}`);
        return decodedToken; // contains uid, email, etc.
    } catch (err) {
        console.error("Error verifying Firebase ID token:", err);
        if (err.code === 'auth/id-token-expired') {
            throw new Error("Firebase ID token has expired");
        } else if (err.code === 'auth/argument-error') {
             throw new Error("Invalid Firebase ID token format");
        }
        // Add more specific error checks if needed
        throw new Error("Invalid Firebase ID token"); // Generic fallback
    }
}

// --- Request Handler ---
// Export ONLY the request handler function using module.exports
module.exports = async (req, res) => {
    console.log(`Received request for /registerDevice (${req.method})`);

    // Check HTTP Method
    if (req.method !== "POST") {
        console.warn(`Method Not Allowed: ${req.method}`);
        // Send JSON error for consistency
        return res.status(405).json({ error: "Method Not Allowed" });
    }

    // Verify User Authentication
    let decodedUser;
    try {
        decodedUser = await verifyFirebaseIdToken(req);
    } catch (err) {
        // verifyFirebaseIdToken already logged the error
        // Send 401 Unauthorized for auth errors
        return res.status(401).json({ error: err.message });
    }

    const uid = decodedUser.uid;

    // Parse and Validate Request Body
    const { deviceId, deviceName } = req.body;

    if (!deviceId) {
        console.warn("Bad Request: Missing deviceId.");
        return res.status(400).json({ error: "Missing required field: deviceId" });
    }
    if (typeof deviceId !== 'string' || deviceId.trim() === '') {
        console.warn(`Bad Request: Invalid deviceId format: ${deviceId}`);
        return res.status(400).json({ error: "Invalid deviceId format" });
    }
    // Optional: Validate deviceName if provided
    if (deviceName && typeof deviceName !== 'string') {
         console.warn(`Bad Request: Invalid deviceName format: ${deviceName}`);
         return res.status(400).json({ error: "Invalid deviceName format" });
    }

    console.log(`Processing registration for device: ${deviceId}, user: ${uid}`);

    // Perform Firestore Writes Atomically
    try {
        const db = admin.firestore();
        const deviceRef = db.collection("devices").doc(deviceId);
        const userRef = db.collection("users").doc(uid);

        // Use a batch write for atomicity
        const batch = db.batch();

        // (1) Set device data in the batch
        batch.set(deviceRef, {
            userId: uid,
            deviceName: deviceName || null, // Store null if not provided
            status: "registered",
            lastSeen: admin.firestore.FieldValue.serverTimestamp(),
            location: null // Initialize location
        }, { merge: true }); // Merge ensures we don't overwrite other fields accidentally

        // (2) Update user data (link device) in the batch
        batch.set(userRef, {
            registeredDevices: admin.firestore.FieldValue.arrayUnion(deviceId)
        }, { merge: true }); // Merge ensures we don't overwrite other user fields

        // Commit the batch
        console.log(`Attempting Firestore batch commit for device ${deviceId}...`);
        await batch.commit();
        console.log(`Device ${deviceId} registered successfully in Firestore for user ${uid}.`);

        // Send Success Response
        return res.status(200).json({
            status: "success",
            message: "Device registered successfully."
        });

    } catch (err) {
        console.error(`Internal Server Error during Firestore operation for device ${deviceId}, user ${uid}:`, err);
        // Send 500 Internal Server Error for database/server issues
        return res.status(500).json({ error: "Internal server error during device registration." });
    }
}; // End of module.exports handler