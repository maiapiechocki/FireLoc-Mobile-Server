const { getElevation } = require("./dem_utils");
const math = require("mathjs");
const proj4 = require("proj4");

// ---------------------------
// Helper: Dot Product
// ---------------------------
function dot(a, b) {
    return a.reduce((sum, val, i) => sum + val * b[i], 0);
}

// ---------------------------
// Rotation Matrix
// from Yaw-Pitch-Roll (ZYX order)
// ---------------------------
function getRotationMatrix([yaw, pitch, roll]) {
    const deg2rad = angle => (angle * Math.PI) / 180;
    yaw = deg2rad(yaw);
    pitch = deg2rad(pitch);
    roll = deg2rad(roll);

    const cy = Math.cos(yaw), sy = Math.sin(yaw);
    const cp = Math.cos(pitch), sp = Math.sin(pitch);
    const cr = Math.cos(roll), sr = Math.sin(roll);

    return [
        [cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr],
        [sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr],
        [-sp, cp * sr, cp * cr]
    ];
}

// ---------------------------
// Project Pixel to 3D Ray
// ---------------------------
function computeRayFromPixel(pixel, intrinsics, orientation, location) {
    const [width, height] = intrinsics.resolution;
    const fov = intrinsics.fov;
    const fx = width / (2 * Math.tan((fov * Math.PI) / 360));
    const fy = fx;
    const cx = width / 2;
    const cy = height / 2;

    const [x_img, y_img] = pixel;
    const xn = (x_img - cx) / fx;
    const yn = (y_img - cy) / fy;
    const zn = 1.0;

    let ray_camera = [xn, yn, zn];
    const norm = Math.sqrt(ray_camera.reduce((acc, val) => acc + val * val, 0));
    ray_camera = ray_camera.map(v => v / norm);

    const R = getRotationMatrix(orientation);

    const ray_world = [
        dot(R[0], ray_camera),
        dot(R[1], ray_camera),
        dot(R[2], ray_camera)
    ];

    const ray_world_norm = Math.sqrt(dot(ray_world, ray_world));
    const normalized_ray = ray_world.map(v => v / ray_world_norm);

    return [location, normalized_ray];
}

// ---------------------------
// Triangulate Between Two Rays
// ---------------------------
function triangulateTwoRays(origin1, dir1, origin2, dir2) {
    const w0 = origin1.map((val, i) => val - origin2[i]);
    const a = dot(dir1, dir1);
    const b = dot(dir1, dir2);
    const c = dot(dir2, dir2);
    const d = dot(dir1, w0);
    const e = dot(dir2, w0);

    const denom = a * c - b * b;
    if (denom === 0) return null;

    const t = (b * e - c * d) / denom;
    const s = (a * e - b * d) / denom;

    const point1 = origin1.map((val, i) => val + t * dir1[i]);
    const point2 = origin2.map((val, i) => val + s * dir2[i]);

    const midpoint = point1.map((val, i) => (val + point2[i]) / 2);
    return midpoint;
}

// ---------------------------
// RANSAC-style Average
// ---------------------------
function ransacAverage(points, threshold = 5.0) {
    if (points.length <= 2) {
        return math.mean(points, 0);
    }

    const centroid = math.mean(points, 0);
    const distances = points.map(p => math.distance(p, centroid));
    const inliers = points.filter((_, i) => distances[i] < threshold);

    if (inliers.length === 0) return centroid;
    return math.mean(inliers, 0);
}

// ---------------------------
// Main FireTri Localizer
// ---------------------------
function firetriLocalize(cameras, demDataset = null, ransacThreshold = 5.0, transformer = null) {
    const rays = [];

    for (const cam of cameras) {
        const [origin, direction] = computeRayFromPixel(
            cam.fire_pixel,
            cam.intrinsics,
            cam.orientation,
            cam.location
        );
        rays.push([origin, direction]);
    }

    const points = [];
    for (let i = 0; i < rays.length; i++) {
        for (let j = i + 1; j < rays.length; j++) {
            const point = triangulateTwoRays(...rays[i], ...rays[j]);
            if (point) points.push(point);
        }
    }

    if (points.length === 0) {
        console.log("No valid triangulated points found.");
        return null;
    }

    let finalLocation = ransacAverage(points, ransacThreshold);
    console.log("Raw triangulated fire position (x, y, z):", finalLocation);

    // DEM correction
    if (demDataset && transformer) {
        const [x, y] = finalLocation;

        try {
            const [lon, lat] = transformer.inverse([x, y]);
            console.log(`Transformed to lat/lon: (${lat}, ${lon})`);

            const demZ = getElevation(lat, lon, demDataset);
            console.log(`Elevation from DEM at (${lat}, ${lon}): ${demZ}`);
            finalLocation[2] = demZ;
        } catch (err) {
            console.warn("DEM correction failed:", err.message);
        }
    }

    return finalLocation;
}

module.exports = {
    firetriLocalize,
    triangulateTwoRays,
    computeRayFromPixel,
    ransacAverage,
    getRotationMatrix
};