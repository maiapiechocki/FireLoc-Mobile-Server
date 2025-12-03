from osgeo import gdal
import numpy as np
import os

def load_dem_by_id(dem_id):
    path = f"landscapeAnalysis/{dem_id}.tif"
    ds = gdal.Open(path)
    if not ds:
        raise FileNotFoundError(f"DEM {dem_id} not found.")
    elevation_data = ds.GetRasterBand(1).ReadAsArray()
    geo_transform = ds.GetGeoTransform()
    return elevation_data, geo_transform
