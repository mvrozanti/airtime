#!/usr/bin/env python3
"""
Process PNG icons to apply opacity threshold:
- Pixels with >= 15% opacity → 100% opacity
- Pixels with < 15% opacity → 0% opacity (fully transparent)
"""

from PIL import Image
import os
import numpy as np

# Opacity threshold: 15% = 0.15 * 255 = 38.25, so threshold is 39
OPACITY_THRESHOLD = int(0.15 * 255)  # 38, so >= 39 means >= 15%

BASE_DIR = 'app/src/main/res'

# Density directories
DENSITIES = ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']

# Icon names
ICON_NAMES = ['ic_notification_cigarette', 'ic_notification_leaf']


def process_image(img_path):
    """Process a single PNG image with opacity threshold."""
    # Open image
    img = Image.open(img_path)
    
    # Ensure RGBA mode
    if img.mode != 'RGBA':
        img = img.convert('RGBA')
    
    # Convert to numpy array for efficient processing
    data = np.array(img)
    
    # Extract alpha channel
    alpha = data[:, :, 3]
    
    # Apply threshold: >= 15% opacity → 100%, < 15% → 0%
    # 15% of 255 = 38.25, so threshold value is 38
    # Pixels >= 39 (>= 38.25) → 255, pixels < 39 → 0
    alpha_processed = np.where(alpha >= OPACITY_THRESHOLD + 1, 255, 0)
    
    # Update alpha channel in the image data
    data[:, :, 3] = alpha_processed
    
    # Convert back to PIL Image
    processed_img = Image.fromarray(data, 'RGBA')
    
    return processed_img


def main():
    """Process all icon PNG files."""
    processed_count = 0
    
    for density in DENSITIES:
        for icon_name in ICON_NAMES:
            img_path = os.path.join(BASE_DIR, f'drawable-{density}', f'{icon_name}.png')
            
            if not os.path.exists(img_path):
                print(f'Warning: {img_path} not found, skipping')
                continue
            
            print(f'Processing {img_path}...')
            
            # Process the image
            processed_img = process_image(img_path)
            
            # Save back to the same location
            processed_img.save(img_path, 'PNG')
            
            processed_count += 1
            print(f'  ✓ Processed {img_path}')
    
    print(f'\nDone! Processed {processed_count} icon files.')


if __name__ == '__main__':
    main()

