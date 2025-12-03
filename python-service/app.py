# python-service/app.py

import os
import traceback
from flask import Flask, request, jsonify

from algorithms.interface import run_topomono, run_dmt
from utils.camera import Camera
from utils.dem import load_dem_by_id
from utils.image import load_image

app = Flask(__name__)

def validate_camera_params(params):
    required_keys = ["lat", "lon", "elevation_m", "heading_deg", "tilt_deg", "fov_h_deg", "fov_v_deg", "resolution"]
    if not isinstance(params, dict): return False
    if not all(key in params for key in required_keys): return False
    if not isinstance(params.get("resolution"), dict) or \
            "width" not in params["resolution"] or \
            "height" not in params["resolution"]: return False
    return True

@app.route('/localize_single_camera', methods=['POST'])
def localize_single_camera():
    print("Received request for /localize_single_camera")
    try:
        data = request.get_json()
        if not data:
            return jsonify({"status": "error", "message": "No JSON data received."}), 400

        algorithm = data.get('algorithm')
        image_gcs_uri = data.get('image_gcs_uri')
        detection_pixel = data.get('detection_pixel')
        camera_params = data.get('camera_params')
        dem_identifier = data.get('dem_identifier')

        required_fields = [algorithm, image_gcs_uri, detection_pixel, camera_params, dem_identifier]
        if not all(required_fields):
            return jsonify({"status": "error", "message": "Missing required fields."}), 400

        if not isinstance(detection_pixel, dict) or "x" not in detection_pixel or "y" not in detection_pixel:
            return jsonify({"status": "error", "message": "Invalid 'detection_pixel' format."}), 400

        if not validate_camera_params(camera_params):
            return jsonify({"status": "error", "message": "Invalid 'camera_params' structure."}), 400

        dem_data, dem_geo_transform = load_dem_by_id(dem_identifier)
        if dem_data is None:
            return jsonify({"status": "error", "message": f"DEM {dem_identifier} could not be loaded."}), 500

        image_np = None
        if algorithm.lower().startswith("dmt"):
            image_np = load_image(image_gcs_uri)
            if image_np is None:
                return jsonify({"status": "error", "message": f"Image at {image_gcs_uri} could not be loaded."}), 500

        cam_res = camera_params["resolution"]
        camera = Camera(
            lat=camera_params["lat"],
            lon=camera_params["lon"],
            elevation=camera_params["elevation_m"],
            heading=camera_params["heading_deg"],
            tilt=camera_params["tilt_deg"],
            fov_h=camera_params["fov_h_deg"],
            fov_v=camera_params["fov_v_deg"],
            resolution=(cam_res["width"], cam_res["height"])
        )

        pixel = (detection_pixel["x"], detection_pixel["y"])

        if algorithm.lower() == "topomono":
            lat, lon, success = run_topomono(camera, pixel, dem_data, dem_geo_transform)
        elif algorithm.lower() in ["dmt", "dmt-ransac"]:
            lat, lon, success = run_dmt(camera, image_np, pixel, dem_data, dem_geo_transform)
        else:
            return jsonify({"status": "error", "message": f"Unsupported algorithm '{algorithm}'."}), 400

        if success:
            return jsonify({
                "status": "success",
                "latitude": lat,
                "longitude": lon,
                "elevation_m": camera.elevation,
                "method_used": algorithm,
                "message": None
            }), 200
        else:
            return jsonify({
                "status": "error",
                "latitude": None,
                "longitude": None,
                "elevation_m": None,
                "method_used": algorithm,
                "message": "Localization failed."
            }), 500

    except Exception as e:
        print(f"!!! UNEXPECTED ERROR in /localize_single_camera: {e}")
        traceback.print_exc()
        return jsonify({
            "status": "error",
            "latitude": None,
            "longitude": None,
            "elevation_m": None,
            "method_used": data.get('algorithm', 'unknown') if 'data' in locals() else 'unknown',
            "message": f"Internal Server Error: {type(e).__name__}"
        }), 500

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    app.run(host='0.0.0.0', port=port, debug=False)
