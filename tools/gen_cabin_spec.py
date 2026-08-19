#!/usr/bin/env python3
"""Generate the v0.21.39 test-kit spec: 'Ranger's Cabin' — a real cabin, generated
deterministically so spec, ground-truth render, and concept reference all agree.
Footprint 9x7 (x -4..4, z -3..3), 2-high walls, gabled dark-oak roof, chimney,
campfire, one small oak tree beside. No block states needed (roof uses full blocks)."""
import json, sys

PAL = {
    "0": "minecraft:oak_planks",
    "1": "minecraft:spruce_log",
    "2": "minecraft:dark_oak_planks",
    "3": "minecraft:glass",
    "4": "minecraft:oak_door",
    "5": "minecraft:stone_bricks",
    "6": "minecraft:campfire",
    "7": "minecraft:oak_log",
    "8": "minecraft:oak_leaves",
}
blocks = []

def add(x, y, z, ref):
    blocks.append({"x": x, "y": y, "z": z, "block": ref})

# ── floor y=0: 9x7 oak_planks ──
for x in range(-4, 5):
    for z in range(-3, 4):
        add(x, 0, z, "0")

# ── corner posts (spruce_log), y=1..2 ──
for (cx, cz) in [(-4,-3),(4,-3),(-4,3),(4,3)]:
    add(cx, 1, cz, "1"); add(cx, 2, cz, "1")

# ── walls y=1..2 (oak_planks) with door + windows ──
for y in (1, 2):
    for x in range(-4, 5):
        if (x, -3) not in [(-4,-3),(4,-3)]: add(x, y, -3, "0")          # north
        if (x, 3) not in [(-4,3),(4,3)]:
            if x == 0: add(x, y, 3, "4")                                # door @ front center
            else:      add(x, y, 3, "0")                                # south
    for z in range(-3, 4):
        if (-4, z) not in [(-4,-3),(-4,3)]:
            if z == 0 and y == 2: add(-4, y, z, "3")                    # west window
            else:                 add(-4, y, z, "0")
        if (4, z) not in [(4,-3),(4,3)]:
            if z == 0 and y == 2: add(4, y, z, "3")                     # east window
            else:                 add(4, y, z, "0")

# ── gabled roof (dark_oak_planks), ridge along x at z=0 ──
CHIMNEY = {(3, -1)}  # (x,z) column occupied by the chimney (y=1..7)
for x in range(-4, 5):
    for (rz, ry) in [(0,6),(-1,5),(1,5),(-2,4),(2,4),(-3,3),(3,3)]:
        if (x, rz) in CHIMNEY: continue     # chimney passes through the roof
        add(x, ry, rz, "2")
# gable end fill at x=±4 (close the triangles)
for gx in (-4, 4):
    add(gx, 4, -1, "2"); add(gx, 4, 0, "2"); add(gx, 4, 1, "2")
    add(gx, 5, 0, "2")

# ── chimney (stone_bricks) at (3,-1), y=1..7, through the roof ──
for y in range(1, 8):
    add(3, y, -1, "5")

# ── campfire in front of the door ──
add(0, 0, 4, "6")

# ── small oak tree beside (west side) ──
tx, tz = -6, -4
for y in range(1, 4): add(tx, y, tz, "7")          # trunk
for (dx, dz) in [(-1,0),(1,0),(0,-1),(0,1),(0,0)]: add(tx+dx, 4, tz+dz, "8")
add(tx, 5, tz, "8")

# ── consistency checks ──
keys = [(b["x"], b["y"], b["z"]) for b in blocks]
dupes = {k for k in keys if keys.count(k) > 1}
assert not dupes, f"duplicate coords: {dupes}"
bad = [b for b in blocks if b["block"] not in PAL]
assert not bad, f"unknown palette refs: {bad}"
print(f"blocks={len(blocks)}  unique={len(set(keys))}")

spec = {"name": "Ranger's Cabin", "palette": PAL, "blocks": blocks}
out = sys.argv[1] if len(sys.argv) > 1 else "/home/user/gh-bot/rangers-cabin.json"
with open(out, "w") as f:
    json.dump(spec, f, indent=1)
print("wrote", out)
