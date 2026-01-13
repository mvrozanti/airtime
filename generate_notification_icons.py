#!/usr/bin/env python3
"""
Generate PNG notification icons for Android.
Creates cigarette and leaf icons at all required densities.
"""

from PIL import Image, ImageDraw
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


def create_cigarette_icon(size):
    """Create a cigarette icon (horizontal rectangle with filter at end)."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Calculate dimensions scaled to size
    # Cigarette body: ~60% of width, centered vertically
    body_width = int(size * 0.6)
    body_height = int(size * 0.2)
    body_x = int(size * 0.1)
    body_y = int((size - body_height) / 2)
    
    # Filter: ~20% of width, same height, attached to body
    filter_width = int(size * 0.15)
    filter_x = body_x + body_width
    filter_y = body_y
    
    # Draw body (filled rectangle)
    draw.rectangle(
        [body_x, body_y, body_x + body_width, body_y + body_height],
        fill=(255, 255, 255, 255)
    )
    
    # Draw filter (filled rectangle, slightly different shade or outline)
    draw.rectangle(
        [filter_x, filter_y, filter_x + filter_width, filter_y + body_height],
        fill=(255, 255, 255, 255)
    )
    
    return img


def create_leaf_icon(size):
    """Create a leaf icon (teardrop shape with center vein)."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Leaf shape: teardrop/oval pointing up with rounded bottom
    center_x = size // 2
    top_y = int(size * 0.2)
    bottom_y = int(size * 0.8)
    width_at_mid = int(size * 0.5)
    left_x = center_x - width_at_mid // 2
    right_x = center_x + width_at_mid // 2
    
    # Create a smoother leaf shape using an ellipse for the main body
    # Draw the main leaf body (ellipse, wider at bottom)
    bbox = (left_x, top_y, right_x, bottom_y)
    draw.ellipse(bbox, fill=(255, 255, 255, 255))
    
    # Draw the pointed top (triangle overlay)
    top_points = [
        (center_x, int(size * 0.1)),  # Top point
        (left_x, top_y),               # Left edge
        (right_x, top_y),              # Right edge
    ]
    draw.polygon(top_points, fill=(255, 255, 255, 255))
    
    # Draw center vein (vertical line)
    stroke_width = max(1, size // 24)
    draw.line(
        [(center_x, top_y + int(size * 0.05)), (center_x, bottom_y - int(size * 0.05))],
        fill=(255, 255, 255, 255),
        width=stroke_width
    )
    
    return img


def main():
    """Generate all icons at all densities."""
    icons = [
        ('ic_notification_cigarette', create_cigarette_icon),
        ('ic_notification_leaf', create_leaf_icon),
    ]
    
    for icon_name, icon_func in icons:
        for density, size in DENSITIES:
            dir_path = os.path.join(BASE_DIR, f'drawable-{density}')
            os.makedirs(dir_path, exist_ok=True)
            
            icon_img = icon_func(size)
            output_path = os.path.join(dir_path, f'{icon_name}.png')
            icon_img.save(output_path, 'PNG')
            print(f'Created {output_path} ({size}x{size})')


if __name__ == '__main__':
    main()

