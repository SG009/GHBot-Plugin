#!/usr/bin/env python3
"""Replicate GHBot BuildPreviewImage.render() in Python to verify the
json-test-build-preview.png == faithful render of json-test-build.json spec."""
import json, sys
from PIL import Image, ImageDraw

CELL = 6; TOP_H = 5; SIDE_W = CELL; M = 24

def color_of(name):
    n = (name or "").lower()
    if "deepslate" in n: return (90,90,100)
    if "basalt" in n: return (75,75,90)
    if "obsidian" in n: return (35,30,45)
    if "magma" in n: return (190,90,30)
    if "bone" in n: return (220,212,180)
    if "redstone_lamp" in n or "glowstone" in n or "shroomlight" in n: return (255,220,140)
    if "redstone" in n: return (190,40,30)
    if "gold" in n: return (240,200,60)
    if "diamond" in n: return (95,230,215)
    if "iron" in n: return (210,210,215)
    if "emerald" in n: return (70,215,120)
    if "coal" in n or "blackstone" in n: return (40,40,45)
    if "campfire" in n or "torch" in n or "lantern" in n: return (230,150,50)
    if "log" in n or "wood" in n or "planks" in n or "fence" in n or "door" in n:
        if "spruce" in n: return (95,70,45)
        if "birch" in n: return (200,190,160)
        if "dark" in n: return (65,48,32)
        if "jungle" in n: return (110,80,50)
        if "acacia" in n: return (165,105,65)
        return (175,130,75)
    if "leaves" in n or "moss" in n:
        if "spruce" in n: return (45,95,45)
        if "birch" in n: return (130,170,90)
        if "dark" in n: return (55,90,45)
        return (80,130,55)
    if "glass" in n: return (200,232,255)
    if "water" in n: return (70,120,210)
    if "lava" in n: return (230,110,30)
    if "sand" in n: return (220,205,160)
    if "stone_brick" in n or "stone" in n: return (125,130,140)
    if "cobblestone" in n or "mossy" in n: return (120,125,120)
    if "quartz" in n or ("concrete" in n and "white" in n): return (236,236,236)
    if "brick" in n: return (150,85,75)
    if "prismarine" in n: return (85,180,170)
    if "slime" in n or "honey" in n: return (160,200,120)
    if "wool" in n or "carpet" in n:
        if "red" in n: return (190,60,60)
        if "blue" in n: return (70,100,190)
        if "green" in n: return (90,160,90)
        if "black" in n: return (40,40,40)
        return (210,210,210)
    return (150,120,200)

def shade(c, f):
    return tuple(min(255, int(v*f)) for v in c)

def render(blocks):
    xs=[b["x"] for b in blocks]; ys=[b["y"] for b in blocks]; zs=[b["z"] for b in blocks]
    w=max(xs)-min(xs)+1; d=max(zs)-min(zs)+1; h=max(ys)-min(ys)+1
    W=(w+d)*SIDE_W+M*2; H=(w+d)*TOP_H+h*CELL+M*2
    # supersample x3 for AA
    SS=3
    img=Image.new("RGBA",(W*SS,H*SS),(0,0,0,0))
    dr=ImageDraw.Draw(img)
    baseSx=W/2*SS; baseSy=(H-M-h*CELL)*SS
    es=sorted(blocks, key=lambda b:(b["x"]+b["z"])*100+b["y"])
    for b in es:
        x,y,z=b["x"],b["y"],b["z"]
        col=color_of(b.get("_name", b.get("block","")))
        sx=baseSx+(x-z)*SIDE_W/2*SS
        sy=baseSy+(x+z)*TOP_H/2*SS - y*CELL*SS
        top=shade(col,1.12); side=shade(col,0.75); dark=shade(col,0.55)
        def poly(c, pts):
            dr.polygon(pts, fill=c+(255,), outline=(0,0,0,60))
        poly(top, [(sx,sy),(sx+SIDE_W*SS,sy+TOP_H/2*SS),(sx,sy+TOP_H*SS),(sx-SIDE_W*SS,sy+TOP_H/2*SS)])
        poly(side, [(sx,sy+TOP_H*SS),(sx+SIDE_W*SS,sy+TOP_H/2*SS),(sx+SIDE_W*SS,sy+TOP_H/2*SS+CELL*SS),(sx,sy+TOP_H*SS+CELL*SS)])
        poly(dark, [(sx,sy+TOP_H*SS),(sx,sy+TOP_H*SS+CELL*SS),(sx-SIDE_W*SS,sy+TOP_H/2*SS+CELL*SS),(sx-SIDE_W*SS,sy+TOP_H/2*SS)])
    return img.resize((W,H), Image.LANCZOS)

spec=json.load(open(sys.argv[1]))
pal=spec.get("palette",{})
blocks=[]
for b in spec["blocks"]:
    ref=b["block"]
    name = pal.get(ref, ref) if isinstance(ref,str) and ref in pal else ref
    bb=dict(b); bb["_name"]=name
    blocks.append(bb)
img=render(blocks)
out=sys.argv[2]
img.save(out)
print(f"rendered {len(blocks)} blocks -> {img.size} saved {out}")
