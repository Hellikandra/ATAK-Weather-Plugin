#!/usr/bin/env python3
"""
Generate land_mask.png from Natural Earth 10m land vectors.

Usage:
    pip install pyshp Pillow
    python generate_land_mask.py

Downloads ne_10m_land.shp from Natural Earth, rasterizes to 3600x1800 PNG.
Output: ../app/src/main/assets/land_mask.png

The PNG is grayscale: black (0) = LAND, white (255) = WATER.
Equirectangular projection, (0,0) = top-left = (90°N, 180°W).
"""

import os
import sys
import urllib.request
import zipfile
import tempfile

try:
    import shapefile
    from PIL import Image, ImageDraw
except ImportError:
    print("Install dependencies: pip install pyshp Pillow")
    sys.exit(1)

# Output resolution: 0.033° per pixel (~3.7km at equator)
# English Channel at Dover (34km) = ~9 pixels wide
# Loaded at 1/4 resolution in-app: 2700×1350 (~3.6MB flat array)
WIDTH = 10800
HEIGHT = 5400

# Natural Earth 10m land shapefile URL
NE_LAND_URL = "https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip"
# Also get minor islands
NE_ISLANDS_URL = "https://naciscdn.org/naturalearth/10m/physical/ne_10m_minor_islands.zip"

def download_and_extract(url, tmp_dir, label):
    """Download and extract a Natural Earth zip."""
    zip_name = url.split("/")[-1]
    zip_path = os.path.join(tmp_dir, zip_name)
    print(f"Downloading {label}: {url}")
    urllib.request.urlretrieve(url, zip_path)
    extract_dir = os.path.join(tmp_dir, zip_name.replace('.zip', ''))
    os.makedirs(extract_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path, 'r') as z:
        z.extractall(extract_dir)
    # Find .shp file
    for root, dirs, files in os.walk(extract_dir):
        for f in files:
            if f.endswith('.shp'):
                return os.path.join(root, f)
    # Try extract_dir directly
    for f in os.listdir(tmp_dir):
        if f.endswith('.shp') and label.lower().replace(' ', '_') in f.lower():
            return os.path.join(tmp_dir, f)
    return None

def geo_to_pixel(lon, lat):
    """Geographic coordinates to pixel. Returns (x, y) tuple."""
    x = (lon + 180.0) / 360.0 * WIDTH
    y = (90.0 - lat) / 180.0 * HEIGHT
    return (int(round(x)), int(round(y)))

def rasterize_shapefile(sf, draw):
    """Draw all polygons from a shapefile as filled black on the image."""
    shapes = sf.shapes()
    print(f"  {len(shapes)} shapes, processing...")

    total_parts = 0
    for shape in shapes:
        if shape.shapeType not in (5, 15, 25):  # Polygon, PolygonZ, PolygonM
            continue

        parts = list(shape.parts)
        if not parts:
            continue
        parts.append(len(shape.points))

        # Each "part" is a ring. First part = exterior, subsequent = holes.
        # But in Natural Earth, each shape may have multiple exterior rings
        # (multi-polygon). We need to draw each part as filled.
        for p_idx in range(len(parts) - 1):
            ring_points = shape.points[parts[p_idx]:parts[p_idx + 1]]
            if len(ring_points) < 3:
                continue

            pixels = [geo_to_pixel(lon, lat) for lon, lat in ring_points]

            # For ne_10m_land.shp, ALL polygons are land areas.
            # Exterior rings define continents/islands, interior rings define
            # large lakes. Draw all as land first, then we'll cut lakes separately
            # if needed. At 0.1° resolution, most lakes are sub-pixel.
            draw.polygon(pixels, fill=0)  # Black = land

            total_parts += 1

    print(f"  {total_parts} polygon parts rasterized")

def verify_mask(img):
    """Print verification stats."""
    data = list(img.getdata())
    land = sum(1 for p in data if p < 128)
    water = sum(1 for p in data if p >= 128)
    total = len(data)

    print(f"\nVerification:")
    print(f"  Total pixels: {total:,}")
    print(f"  Land: {land:,} ({100*land/total:.1f}%)")
    print(f"  Water: {water:,} ({100*water/total:.1f}%)")

    # Check known points
    test_points = [
        ("London, UK",      51.5, -0.1, "land"),
        ("Paris, France",   48.9, 2.3, "land"),
        ("English Channel", 50.5, 1.0, "water"),
        ("North Sea",       55.0, 3.0, "water"),
        ("Atlantic Ocean",  45.0, -20.0, "water"),
        ("New York, USA",   40.7, -74.0, "land"),
        ("Pacific Ocean",   30.0, -150.0, "water"),
        ("Sahara Desert",   25.0, 10.0, "land"),
        ("Tokyo, Japan",    35.7, 139.7, "land"),
        ("Mediterranean",   36.0, 15.0, "water"),
    ]

    print("\n  Point checks:")
    for name, lat, lon, expected in test_points:
        x = int((lon + 180) / 360 * WIDTH)
        y = int((90 - lat) / 180 * HEIGHT)
        x = max(0, min(WIDTH-1, x))
        y = max(0, min(HEIGHT-1, y))
        pixel = img.getpixel((x, y))
        actual = "water" if pixel >= 128 else "land"
        match = "✓" if actual == expected else "✗ WRONG"
        print(f"    {name}: pixel={pixel}, {actual} (expected {expected}) {match}")

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_path = os.path.join(script_dir, "..", "app", "src", "main", "assets", "land_mask.png")
    output_path = os.path.normpath(output_path)

    with tempfile.TemporaryDirectory() as tmp_dir:
        # Download both land and islands shapefiles
        land_shp = download_and_extract(NE_LAND_URL, tmp_dir, "Land")
        islands_shp = download_and_extract(NE_ISLANDS_URL, tmp_dir, "Minor Islands")

        # Create white image (all water by default)
        img = Image.new('L', (WIDTH, HEIGHT), 255)
        draw = ImageDraw.Draw(img)

        # Rasterize land polygons
        if land_shp:
            print(f"\nRasterizing land: {land_shp}")
            sf = shapefile.Reader(land_shp)
            rasterize_shapefile(sf, draw)
        else:
            print("WARNING: Land shapefile not found!")

        # Rasterize minor islands
        if islands_shp:
            print(f"\nRasterizing islands: {islands_shp}")
            sf = shapefile.Reader(islands_shp)
            rasterize_shapefile(sf, draw)
        else:
            print("Note: Minor islands shapefile not found (optional)")

        # Verify
        verify_mask(img)

        # Save
        print(f"\nSaving to {output_path}...")
        img.save(output_path, 'PNG', optimize=True)
        file_size = os.path.getsize(output_path)
        print(f"Done! {WIDTH}x{HEIGHT} PNG, {file_size / 1024:.1f} KB")

    print(f"\nAsset saved to: {output_path}")
    print("Rebuild the APK to include the new asset.")

if __name__ == "__main__":
    main()
