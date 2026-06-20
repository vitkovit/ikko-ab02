#!/usr/bin/env python3
"""Generate a circular phone icon PNG matching Ikko launcher style."""
import struct, zlib, math, os

def png(width, height, pixels):
    def chunk(tag, data):
        c = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', c)
    raw = b''
    for row in pixels:
        raw += b'\x00' + bytes(row)
    compressed = zlib.compress(raw, 9)
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', compressed) + chunk(b'IEND', b'')

def draw_icon(size, bg_r, bg_g, bg_b, out_path):
    pixels = []
    cx = cy = size / 2
    r = size / 2

    # Pre-compute phone path at normalized coords (0-1), then scale
    def phone_pixel(px, py):
        """Returns True if pixel is inside the white phone handset shape."""
        # Normalize to -0.5..0.5
        nx = (px - cx) / r
        ny = (py - cy) / r

        # Phone shape: classic handset
        # Outer arc top-right: earpiece
        # Body: diagonal bar
        # Bottom arc: mouthpiece

        def in_ellipse(ex, ey, rx, ry, x, y):
            return ((x - ex)**2 / rx**2 + (y - ey)**2 / ry**2) <= 1

        def in_rect_rotated(cx2, cy2, w, h, angle, x, y):
            cos_a = math.cos(angle)
            sin_a = math.sin(angle)
            dx = x - cx2
            dy = y - cy2
            lx = dx * cos_a + dy * sin_a
            ly = -dx * sin_a + dy * cos_a
            return abs(lx) <= w/2 and abs(ly) <= h/2

        # Scale: use normalized coords
        x, y = nx, ny

        # Earpiece ellipse (top-right)
        if in_ellipse(0.18, -0.22, 0.16, 0.12, x, y):
            return True
        # Mouthpiece ellipse (bottom-left)
        if in_ellipse(-0.18, 0.22, 0.16, 0.12, x, y):
            return True
        # Body: rotated rectangle (handle)
        if in_rect_rotated(0, 0, 0.18, 0.52, math.radians(38), x, y):
            return True

        return False

    for row in range(size):
        pixel_row = []
        for col in range(size):
            # Background: circle with anti-aliasing
            dx = col - cx
            dy = row - cy
            dist = math.sqrt(dx*dx + dy*dy)

            # Inside circle
            if dist <= r - 1:
                if phone_pixel(col, row):
                    pixel_row += [255, 255, 255]  # white
                else:
                    pixel_row += [bg_r, bg_g, bg_b]
            elif dist <= r:
                # Anti-alias edge
                alpha = r - dist
                pixel_row += [int(bg_r*alpha), int(bg_g*alpha), int(bg_b*alpha)]
            else:
                pixel_row += [0, 0, 0]  # transparent black outside

        pixels.append(pixel_row)

    data = png(size, size, pixels)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'wb') as f:
        f.write(data)
    print(f"Written {out_path} ({len(data)} bytes)")

# Green background (#2e7d32 = 46, 125, 50) like Fossify Phone
bg = (46, 125, 50)

sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
}
base = '/Users/user/Developer/ikko/dialer-app/app/src/main/res'
for dpi, size in sizes.items():
    draw_icon(size, *bg, f'{base}/{dpi}/ic_launcher.png')
