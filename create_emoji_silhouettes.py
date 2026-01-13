#!/usr/bin/env python3
"""
Download emoji images and convert to black silhouettes (then invert to white for Android).
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
    """Download emoji and convert to silhouette."""
    # Use emoji CDN service
    url = f"https://emojicdn.elk.sh/{emoji_char}"
    
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
        
        # Extract alpha channel - this is our mask
        # Everything that's not transparent (alpha > threshold) becomes the shape
        alpha = img.split()[3]
        
        # Create black silhouette: black where there's content, transparent elsewhere
        result = Image.new('RGBA', img.size, (0, 0, 0, 0))
        
        # Copy alpha channel to create the silhouette
        # Put black (0,0,0) where alpha is > threshold
        black_layer = Image.new('RGB', img.size, (0, 0, 0))
        result = Image.composite(black_layer, result, alpha)
        result.putalpha(alpha)
        
        # Now invert to white for Android notification icons
        # Create white version
        white_result = Image.new('RGBA', img.size, (0, 0, 0, 0))
        white_layer = Image.new('RGB', img.size, (255, 255, 255))
        white_result = Image.composite(white_layer, white_result, alpha)
        white_result.putalpha(alpha)
        
        # Generate all sizes (using white version for Android)
        for density, size in DENSITIES:
            dir_path = os.path.join(BASE_DIR, f'drawable-{density}')
            os.makedirs(dir_path, exist_ok=True)
            
            resized = white_result.resize((size, size), Image.Resampling.LANCZOS)
            output_path = os.path.join(dir_path, f'{output_name}.png')
            resized.save(output_path, 'PNG')
            print(f'  Created {output_path} ({size}x{size})')
        
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
