#!/usr/bin/env python3
"""
Increase smoke opacity/density in cigarette PNG icons.
Makes smoke less transparent while preserving the cigarette body.
"""

from PIL import Image
import numpy as np
import os

# Density specifications: (directory_suffix, size_in_pixels)
DENSITIES = [
    ('mdpi', 24),
    ('hdpi', 36),
    ('xhdpi', 48),
    ('xxhdpi', 72),
    ('xxxhdpi', 96),
]

BASE_DIR = 'app/src/main/res'

def increase_smoke_opacity(input_path, output_path, opacity_multiplier=1.5):
    """
    Increase opacity of smoke pixels in cigarette icon.

    Args:
        input_path: Path to input PNG
        output_path: Path to output PNG
        opacity_multiplier: Factor to increase smoke opacity (1.0 = no change, >1.0 = more opaque)
    """
    # Load image
    img = Image.open(input_path).convert('RGBA')
    pixels = np.array(img)

    # Get alpha channel
    alpha = pixels[:, :, 3].astype(float)

    # Identify smoke pixels (lower opacity) vs cigarette body (high opacity)
    # Smoke typically has alpha < 240, cigarette body has alpha >= 240
    smoke_mask = alpha < 240

    # Increase opacity of smoke pixels
    alpha[smoke_mask] = np.minimum(255, alpha[smoke_mask] * opacity_multiplier)

    # Convert back to uint8
    pixels[:, :, 3] = alpha.astype(np.uint8)

    # Create new image and save
    result = Image.fromarray(pixels, 'RGBA')
    result.save(output_path, 'PNG')

    print(f'Processed {input_path} → {output_path}')

def main():
    """Process cigarette icons at all densities."""

    # Ask user for opacity multiplier
    try:
        multiplier = float(input("Enter smoke opacity multiplier (1.0 = no change, 1.5-2.0 recommended): ").strip())
        if multiplier < 1.0:
            print("Warning: Multiplier < 1.0 will make smoke more transparent!")
        elif multiplier > 3.0:
            print("Warning: Multiplier > 3.0 may make smoke too dense!")
    except ValueError:
        print("Invalid input. Using default multiplier of 1.5")
        multiplier = 1.5

    print(f"Using opacity multiplier: {multiplier}")

    for density, size in DENSITIES:
        dir_path = os.path.join(BASE_DIR, f'drawable-{density}')
        icon_path = os.path.join(dir_path, 'ic_notification_cigarette.png')

        if os.path.exists(icon_path):
            # Create backup in backups directory
            os.makedirs('backups', exist_ok=True)
            backup_path = f'backups/ic_notification_cigarette_{density}.png.backup'
            if not os.path.exists(backup_path):
                import shutil
                shutil.copy2(icon_path, backup_path)
                print(f'Created backup: {backup_path}')

            # Process the icon
            increase_smoke_opacity(icon_path, icon_path, multiplier)
        else:
            print(f'Warning: {icon_path} not found')

    print("\nDone! Original files backed up with .backup extension.")
    print("Test the app to see the denser smoke effect.")

if __name__ == '__main__':
    main()
