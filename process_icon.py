import sys
import os
from PIL import Image

src_path = '/mnt/c/Users/KSJ/Desktop/로고.png'
fg_out = '/tmp/ic_launcher_foreground.png'
bg_out = '/tmp/bg_color.txt'

try:
    img = Image.open(src_path).convert("RGBA")
    data = img.getdata()
    
    # Get top-left color as background
    bg_color = data[0]
    bg_hex = '#{:02x}{:02x}{:02x}'.format(bg_color[0], bg_color[1], bg_color[2])
    
    # Create a mask for non-background pixels
    def is_bg(p, bg, tol=25):
        return abs(p[0]-bg[0])<tol and abs(p[1]-bg[1])<tol and abs(p[2]-bg[2])<tol
        
    new_data = []
    for p in data:
        if is_bg(p, bg_color):
            new_data.append((255, 255, 255, 0)) # transparent
        else:
            new_data.append(p)
            
    img.putdata(new_data)
    
    # Find bounding box
    bbox = img.getbbox()
    if bbox is None:
        bbox = (0,0,img.width,img.height)
    
    img_cropped = img.crop(bbox)
    
    # Adaptive icon xxxhdpi size: 432x432, safe zone diameter roughly 288. We use 280 to be safe.
    target_size = 432
    safe_size = 280
    
    scale = min(safe_size / img_cropped.width, safe_size / img_cropped.height)
    new_w = int(img_cropped.width * scale)
    new_h = int(img_cropped.height * scale)
    
    # Using Image.Resampling.LANCZOS if available, otherwise Image.LANCZOS
    resample_filter = getattr(Image, 'Resampling', Image).LANCZOS
    img_cropped = img_cropped.resize((new_w, new_h), resample_filter)
    
    final_img = Image.new("RGBA", (target_size, target_size), (0,0,0,0))
    paste_x = (target_size - new_w) // 2
    paste_y = (target_size - new_h) // 2
    final_img.paste(img_cropped, (paste_x, paste_y), img_cropped)
    
    final_img.save(fg_out)
    
    with open(bg_out, 'w') as f:
        f.write(bg_hex)
        
    print("SUCCESS")
except Exception as e:
    print("ERROR:", e)
