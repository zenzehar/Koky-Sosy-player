#!/usr/bin/env python3
"""Generate Android raster assets using the exact supplied S/K master SVG shape.
Run before Gradle. Original supplied PNG pack remains the preferred replacement
when adding images through a normal Git checkout; SVG rendering is deterministic.
"""
from pathlib import Path
from io import BytesIO
from PIL import Image, ImageDraw, ImageFont
import cairosvg

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/"app/src/main/res"
SVG='''<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600"><defs><linearGradient id="sGrad" x1="0%" y1="0%" x2="83%" y2="100%"><stop offset="0%" stop-color="#FFD35B"/><stop offset="35%" stop-color="#FF8944"/><stop offset="70%" stop-color="#FF4676"/><stop offset="100%" stop-color="#A12CEF"/></linearGradient><linearGradient id="kGrad" x1="15%" y1="5%" x2="86%" y2="90%"><stop offset="0%" stop-color="#2EE4EF"/><stop offset="40%" stop-color="#19AFFF"/><stop offset="100%" stop-color="#2351E9"/></linearGradient></defs><g transform="translate(134 139) scale(.56)"><path d="M 353 134 L 419 134 L 419 263 L 506 135 L 573 135 L 452 304 L 578 475 L 499 475 L 419 359 L 419 475 L 353 475 Z" fill="url(#kGrad)" stroke-linejoin="round"/><path d="M 305 154 C 254 115 188 120 156 153 C 110 198 139 243 210 282 C 271 315 304 341 300 382 C 295 450 210 483 144 446" fill="none" stroke="url(#sGrad)" stroke-width="76" stroke-linecap="round" stroke-linejoin="round"/></g></svg>'''
colors=[(9,14,30),(13,19,45),(36,12,54)]

def gradient(w,h):
    im=Image.new("RGBA",(w,h))
    px=im.load()
    for y in range(h):
        for x in range(w):
            k=min(1.,(x/w+y/h)/2)
            px[x,y]=tuple(int(colors[0][i]*(1-k)+colors[-1][i]*k) for i in range(3))+(255,)
    return im

def glyph(size):
    return Image.open(BytesIO(cairosvg.svg2png(bytestring=SVG.encode(),output_width=size,output_height=size))).convert("RGBA")

def save(name,im):
    path=RES/name
    path.parent.mkdir(parents=True,exist_ok=True)
    im.save(path,format="PNG",optimize=True)

def font(size):
    for name in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf"):
        try:return ImageFont.truetype(name,size)
        except OSError:continue
    return ImageFont.load_default()

# Exact S/K geometry and gradient palette from the user-provided master SVG.
for bucket,size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    base=gradient(size,size)
    base.alpha_composite(glyph(size))
    save(f"mipmap-{bucket}/ic_launcher.png",base)
    # Round icon is a separate crop, keeping the S/K mark centred.
    mask=Image.new("L",(size,size),0)
    ImageDraw.Draw(mask).ellipse((0,0,size-1,size-1),fill=255)
    round_base=base.copy()
    round_base.putalpha(mask)
    save(f"mipmap-{bucket}/ic_launcher_round.png",round_base)

save("drawable-nodpi/soky_adaptive_foreground.png",glyph(432))
splash=gradient(432,432)
splash.alpha_composite(glyph(432))
save("drawable-nodpi/soky_splash_icon.png",splash)

banner=gradient(320,180)
banner.alpha_composite(glyph(200).resize((155,155)),(5,13))
d=ImageDraw.Draw(banner)
d.text((144,58),"SOKY",font=font(27),fill="#FFFFFF")
d.text((144,91),"IPTV PRO",font=font(18),fill="#2EE4EF")
save("drawable-nodpi/soky_tv_banner.png",banner)

# Adaptive icon and consistent system splash values, based on supplied pack.
values=RES/"values"
values.mkdir(parents=True,exist_ok=True)
(values/"soky_colors.xml").write_text("""<resources>
 <color name="soky_icon_background">#090E1E</color>
 <color name="soky_splash_background">#090E1E</color>
 <color name="soky_brand_coral">#FF496E</color>
 <color name="soky_brand_cyan">#20DFF4</color>
 <color name="soky_brand_blue">#176BF4</color>
</resources>
""")
adaptive=RES/"mipmap-anydpi-v26"
adaptive.mkdir(parents=True,exist_ok=True)
for name in ("ic_launcher.xml","ic_launcher_round.xml"):
    (adaptive/name).write_text("""<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
 <background android:drawable="@color/soky_icon_background"/>
 <foreground android:drawable="@drawable/soky_adaptive_foreground"/>
 <monochrome android:drawable="@drawable/soky_adaptive_monochrome"/>
</adaptive-icon>""")
mono=RES/"drawable/soky_adaptive_monochrome.xml"
mono.parent.mkdir(parents=True,exist_ok=True)
mono.write_text("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
 android:width="108dp" android:height="108dp" android:viewportWidth="600" android:viewportHeight="600">
 <group android:translateX="134" android:translateY="139" android:scaleX="0.56" android:scaleY="0.56">
  <path android:fillColor="#FFFFFFFF" android:pathData="M 353 134 L 419 134 L 419 263 L 506 135 L 573 135 L 452 304 L 578 475 L 499 475 L 419 359 L 419 475 L 353 475 Z"/>
  <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="76" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M 305 154 C 254 115 188 120 156 153 C 110 198 139 243 210 282 C 271 315 304 341 300 382 C 295 450 210 483 144 446"/>
 </group>
</vector>""")
# Previously existing drawable resources are not the user's new brand.
for old in ("drawable/banner.xml","drawable/ic_launcher_foreground.xml",
            "mipmap-anydpi/ic_launcher.xml","mipmap-anydpi/ic_launcher_round.xml",
            "drawable/ic_sokyiptv_logo.xml"):
    (RES/old).unlink(missing_ok=True)
print("Soky IPTV Pro adaptive, splash, TV banner and launcher resources generated.")
