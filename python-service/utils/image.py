from PIL import Image
import requests
from io import BytesIO
import numpy as np

def load_image(path_or_url):
    if path_or_url.startswith("http"):
        response = requests.get(path_or_url)
        image = Image.open(BytesIO(response.content)).convert("RGB")
    else:
        image = Image.open(path_or_url).convert("RGB")
    return np.array(image)
