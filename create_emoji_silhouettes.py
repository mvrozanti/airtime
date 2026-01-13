#!/usr/bin/env python3
"""
Download emoji images and convert to white silhouettes with transparency for Android.
"""

import requests
from PIL import Image
import os

DENSITIES = [
    ('mdpi', 24),
    ('hdpi', 36),
    ('xhdpi', 48),
    ('xxhdpi', 72),
    ('xxxhdpi', 96),
]

BASE_DIR = 'app/src/main/res'

def download_and_convert(emoji_char, output_name):
    """Download emoji and convert to white silhouette with transparency."""
    url = f"https://emojicdn.elk.sh/{emoji_char}?style=apple"

    print(f"Downloading {output_name} ({emoji_char})...")
    try:
        response = requests.get(url, timeout=10, headers={'User-Agent': 'Mozilla/5.0'})
        if response.status_code != 200:
            print(f"  Failed to download: HTTP {response.status_code}")
            return False

        # Save to temp file
        temp_path = f"/tmp/emoji_{output_name}.png"
        with open(temp_path, 'wb') as f:
            f.write(response.content)

        # Open image
        img = Image.open(temp_path)

        # Convert to RGBA if not already
        if img.mode != 'RGBA':
            img = img.convert('RGBA')

        # Extract alpha channel (transparency mask)
        alpha = img.split()[3]

        # Create result image: white where alpha > 0, transparent elsewhere
        result = Image.new('RGBA', img.size, (255, 255, 255, 0))  # Start with transparent white

        # Apply alpha channel to create white silhouette
        white_layer = Image.new('RGBA', img.size, (255, 255, 255, 255))  # Solid white
        result = Image.composite(white_layer, result, alpha)

        # Generate all sizes
        for density, size in DENSITIES:
            dir_path = os.path.join(BASE_DIR, f'drawable-{density}')
            os.makedirs(dir_path, exist_ok=True)

            # Resize using high-quality resampling
            resized = result.resize((size, size), Image.Resampling.LANCZOS)
            output_path = os.path.join(dir_path, f'{output_name}.png')

            # Save as RGBA PNG (with transparency)
            resized.save(output_path, 'PNG')
            print(f'  Created {output_path} ({size}x{size}) - WHITE SILHOUETTE (with transparency)')

        os.remove(temp_path)
        return True

    except Exception as e:
        print(f"  Error: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == '__main__':
    icons = [
        ('🚬', 'ic_notification_cigarette'),
        ('🌿', 'ic_notification_leaf'),
    ]

    for emoji, name in icons:
        download_and_convert(emoji, name)
