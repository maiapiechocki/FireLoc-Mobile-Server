# python-service/tests/test_localize_single_camera.py

# Run: pytest tests/test_localize_single_camera.py

import pytest
from app import app as flask_app
import json

@pytest.fixture
def client():
    flask_app.config['TESTING'] = True
    with flask_app.test_client() as client:
        yield client

def test_missing_fields(client):
    res = client.post("/localize_single_camera", json={})
    assert res.status_code == 400
    assert res.get_json()["status"] == "error"

def test_invalid_pixel_format(client):
    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "path/to/image.jpg",
        "detection_pixel": {"wrong": 1},
        "camera_params": {
            "lat": 34.07,
            "lon": -118.20,
            "elevation_m": 450,
            "heading_deg": 90,
            "tilt_deg": 10,
            "fov_h_deg": 60,
            "fov_v_deg": 45,
            "resolution": {"width": 640, "height": 480}
        },
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", json=payload)
    assert res.status_code == 400
    assert "detection_pixel" in res.get_json()["message"]

def test_invalid_camera_params(client):
    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "path/to/image.jpg",
        "detection_pixel": {"x": 100, "y": 100},
        "camera_params": {"lat": 34.07},
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", json=payload)
    assert res.status_code == 400
    assert "camera_params" in res.get_json()["message"]

def test_valid_topomono_call(client):
    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "tests/data/fire.jpg",
        "detection_pixel": {"x": 10, "y": 20},
        "camera_params": {
            "lat": 34.07,
            "lon": -118.20,
            "elevation_m": 450,
            "heading_deg": 90,
            "tilt_deg": 10,
            "fov_h_deg": 60,
            "fov_v_deg": 45,
            "resolution": {"width": 640, "height": 480}
        },
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", data=json.dumps(payload), content_type='application/json')
    data = res.get_json()
    assert res.status_code == 200
    assert data["status"] == "success"
    assert isinstance(data["latitude"], float)
    assert isinstance(data["longitude"], float)

def test_valid_dmt_call(client):
    payload = {
        "algorithm": "DMT",
        "image_gcs_uri": "tests/data/fire.jpg",
        "detection_pixel": {"x": 50, "y": 80},
        "camera_params": {
            "lat": 34.10,
            "lon": -118.25,
            "elevation_m": 500,
            "heading_deg": 100,
            "tilt_deg": 20,
            "fov_h_deg": 70,
            "fov_v_deg": 50,
            "resolution": {"width": 800, "height": 600}
        },
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", data=json.dumps(payload), content_type='application/json')
    data = res.get_json()
    assert res.status_code == 200
    assert data["status"] == "success"
    assert isinstance(data["latitude"], float)
    assert isinstance(data["longitude"], float)

def test_valid_localize_triangulate_call(client):
    payload = {
        "camera_observations": [
            {
                "camera_params": {
                    "lat": 34.10,
                    "lon": -118.25,
                    "elevation_m": 500,
                    "heading_deg": 90,
                    "tilt_deg": 15,
                    "fov_h_deg": 60,
                    "fov_v_deg": 40,
                    "resolution": {"width": 800, "height": 600}
                },
                "detection_pixel": {"x": 320, "y": 240}
            },
            {
                "camera_params": {
                    "lat": 34.11,
                    "lon": -118.26,
                    "elevation_m": 510,
                    "heading_deg": 85,
                    "tilt_deg": 10,
                    "fov_h_deg": 65,
                    "fov_v_deg": 45,
                    "resolution": {"width": 800, "height": 600}
                },
                "detection_pixel": {"x": 300, "y": 250}
            }
        ],
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_triangulate", data=json.dumps(payload), content_type='application/json')
    data = res.get_json()
    assert res.status_code == 200
    assert data["status"] == "success"
    assert isinstance(data["latitude"], float)
    assert isinstance(data["longitude"], float)

def test_valid_firetri_call(client):
    payload = {
        "camera_observations": [
            {
                "camera_params": {
                    "lat": 34.15,
                    "lon": -118.30,
                    "elevation_m": 480,
                    "heading_deg": 95,
                    "tilt_deg": 18,
                    "fov_h_deg": 75,
                    "fov_v_deg": 50,
                    "resolution": {"width": 1280, "height": 720}
                },
                "detection_pixel": {"x": 400, "y": 300}
            },
            {
                "camera_params": {
                    "lat": 34.14,
                    "lon": -118.28,
                    "elevation_m": 470,
                    "heading_deg": 92,
                    "tilt_deg": 17,
                    "fov_h_deg": 70,
                    "fov_v_deg": 45,
                    "resolution": {"width": 1280, "height": 720}
                },
                "detection_pixel": {"x": 410, "y": 310}
            }
        ],
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/firetri", data=json.dumps(payload), content_type='application/json')
    data = res.get_json()
    assert res.status_code == 200
    assert data["status"] == "success"
    assert isinstance(data["latitude"], float)
    assert isinstance(data["longitude"], float)
