from .topomono import topo_mono
from .dmt import DMT
from utils.camera import Camera
from utils.dem import load_dem, get_elevation

def run_topomono(camera: Camera, pixel, elevation_data, geo_transform):
    pixel_x, pixel_y = pixel
    lat, lon, success = topo_mono(camera, pixel_x, pixel_y, elevation_data, geo_transform,
                                   image_width=camera.resolution[0], image_height=camera.resolution[1])
    return lat, lon, success

def run_dmt(camera: Camera, image, pixel, elevation_data, geo_transform, model_path=None):
    dmt = DMT(elevation_data, geo_transform, monodepth_model_weights_path=model_path)
    lat, lon, success = dmt.localize(camera, image, pixel)
    return lat, lon, success
