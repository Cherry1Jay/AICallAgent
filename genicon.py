from PIL import Image, ImageDraw

S = 512
img = Image.new("RGBA", (S, S), (0,0,0,0))

# 对角渐变
grad = Image.new("RGB", (S, S))
d = ImageDraw.Draw(grad)
c1, c2 = (92, 132, 255), (38, 72, 208)
for i in range(S*2):
    t = i/(S*2-1)
    d.line([(i,0),(0,i)], fill=tuple(int(c1[k]+(c2[k]-c1[k])*t) for k in range(3)))
img.paste(grad, (0,0))

mask = Image.new("L", (S,S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0,0,S-1,S-1], radius=int(S*0.235), fill=255)
img.putalpha(mask)

# 电话听筒：哑铃形，旋转 -45°
layer = Image.new("RGBA", (S,S), (0,0,0,0))
ld = ImageDraw.Draw(layer)
cx, cy = S//2, S//2
r = 64
top_y, bot_y = cy-108, cy+108
ld.ellipse([cx-r, top_y-r, cx+r, top_y+r], fill=(255,255,255,255))
ld.ellipse([cx-r, bot_y-r, cx+r, bot_y+r], fill=(255,255,255,255))
ld.rounded_rectangle([cx-30, top_y, cx+30, bot_y], radius=30, fill=(255,255,255,255))
layer = layer.rotate(-45, resample=Image.BICUBIC, center=(cx,cy))
img.alpha_composite(layer)

# 右上角声波
dr = ImageDraw.Draw(img)
for i, (rr, w) in enumerate([(196, 17), (250, 13)]):
    dr.arc([cx-rr, cy-rr, cx+rr, cy+rr], start=-62, end=-8,
           fill=(255,255,255, int(170-40*i)), width=w)

img.save("icon_master.png")
for dpi, px in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    img.resize((px,px), Image.LANCZOS).save(f"XiaomiAICallAgent/res/mipmap-{dpi}/ic_launcher.png")
print("ok")
