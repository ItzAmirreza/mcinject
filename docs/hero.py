#!/usr/bin/env python3
"""Cream title-page hero GIF: MCInject wordmark, real packet names drifting in the margins.

Minimal and elegant. Background packets drift on a seamless loop; the centre stays calm for the
wordmark. All packet names are real Minecraft (Mojang-mapped) class names.
"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1200, 660

CREAM = (244, 238, 225)
INK   = (38, 33, 27)
INK_SOFT = (120, 110, 93)
RULE  = (203, 190, 165)
INDIGO = (86, 110, 141)
CLAY   = (178, 112, 66)

def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

SOFT = mix(INK, CREAM, 0.34)           # legible warm taupe for small type
TOKEN_IN  = mix(CREAM, INDIGO, 0.20)   # inbound name, faint but readable
TOKEN_OUT = mix(CREAM, CLAY, 0.20)     # outbound name
ARROW_IN  = mix(CREAM, INDIGO, 0.62)
ARROW_OUT = mix(CREAM, CLAY, 0.62)

DIDOT = "/System/Library/Fonts/Supplemental/Didot.ttc"
BASK  = "/System/Library/Fonts/Supplemental/Baskerville.ttc"
MENLO = "/System/Library/Fonts/Menlo.ttc"

f_title   = ImageFont.truetype(DIDOT, 122, index=2)   # Didot Bold
f_eyebrow = ImageFont.truetype(BASK, 24, index=4)     # Baskerville SemiBold
f_tag     = ImageFont.truetype(BASK, 22, index=0)
f_tok     = ImageFont.truetype(MENLO, 17, index=0)

# real names, split by direction
IN_NAMES = [
    "ClientboundBundlePacket", "ClientboundLevelChunkWithLightPacket",
    "ClientboundSystemChatPacket", "ClientboundAddEntityPacket",
    "ClientboundBlockUpdatePacket", "ClientboundSetEntityDataPacket",
    "ClientboundBossEventPacket", "ClientboundTabListPacket",
    "ClientboundTeleportEntityPacket", "ClientboundOpenScreenPacket",
    "ClientboundPlayerInfoUpdatePacket", "ClientboundSetTitleTextPacket",
    "ClientboundLevelParticlesPacket", "ClientboundContainerSetContentPacket",
]
OUT_NAMES = [
    "ServerboundMovePlayerPacket", "ServerboundSwingPacket",
    "ServerboundInteractPacket", "ServerboundContainerClickPacket",
    "ServerboundPlayerActionPacket", "ServerboundUseItemPacket",
    "ServerboundChatPacket", "ServerboundPlayerCommandPacket",
    "ServerboundSetCarriedItemPacket", "ServerboundKeepAlivePacket",
]

# lanes live only in top and bottom margins; centre stays calm for the wordmark
TOP_YS = [58, 94, 130, 166]
BOT_YS = [500, 536, 572, 608]
P = 480            # drift period (px); one token per period → sparse
GAPS = [0.0, 0.37, 0.71, 0.14, 0.58]

lanes = []
for band, ys in ((0, TOP_YS), (1, BOT_YS)):
    for i, y in enumerate(ys):
        idx = i + band * len(ys)
        inbound = (idx % 2 == 0)
        name = (IN_NAMES if inbound else OUT_NAMES)[idx % (len(IN_NAMES) if inbound else len(OUT_NAMES))]
        lanes.append({
            "y": y, "inbound": inbound, "name": name,
            "phase": GAPS[i] * P + band * 210,   # stagger so lanes don't align
        })

def spaced(d, xy, text, font, fill, spacing):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=font, fill=fill)
        x += d.textlength(ch, font=font) + spacing
    return x

def spaced_width(d, text, font, spacing):
    return sum(d.textlength(ch, font=font) + spacing for ch in text) - spacing

def draw_lane(d, lane, off):
    y = lane["y"]
    inbound = lane["inbound"]
    name = lane["name"]
    arrow = "›" if inbound else "‹"
    acol = ARROW_IN if inbound else ARROW_OUT
    tcol = TOKEN_IN if inbound else TOKEN_OUT
    tok_w = d.textlength(arrow + "   " + name, font=f_tok)
    # base drift position, wrapping over period P
    shift = (off if inbound else -off) % P
    start = -P + (lane["phase"] + shift) % P
    x = start - P
    while x < W + P:
        # arrow leads in the direction of motion
        if inbound:
            d.text((x, y), arrow, font=f_tok, fill=acol)
            d.text((x + d.textlength(arrow + "   ", font=f_tok), y), name, font=f_tok, fill=tcol)
        else:
            d.text((x, y), name, font=f_tok, fill=tcol)
            d.text((x + d.textlength(name + "   ", font=f_tok), y), arrow, font=f_tok, fill=acol)
        x += P

def spaced_multi(d, xy, text, font, spacing, base, accent, accent_ch="·"):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=font, fill=accent if ch == accent_ch else base)
        x += d.textlength(ch, font=font) + spacing

def draw_wordmark(d):
    cx = W // 2
    ty = 250                                 # title top; block optically centred

    # eyebrow
    eb = "FOR A RUNNING MINECRAFT CLIENT"
    ew = spaced_width(d, eb, f_eyebrow, 5)
    spaced(d, (cx - ew / 2, ty - 44), eb, f_eyebrow, SOFT, 5)

    # wordmark
    title = "MCInject"
    tw = d.textlength(title, font=f_title)
    d.text((cx - tw / 2, ty), title, font=f_title, fill=INK)

    # flourish: split hairline rule with a small centred diamond
    ry = ty + 162
    d.line([cx - 104, ry, cx - 13, ry], fill=RULE, width=1)
    d.line([cx + 13, ry, cx + 104, ry], fill=RULE, width=1)
    dm = mix(INK, CREAM, 0.42)
    d.polygon([(cx, ry - 4), (cx + 4, ry), (cx, ry + 4), (cx - 4, ry)], fill=dm)

    # tagline — separators tinted to echo the in/out accents
    tag = "ATTACH · TAP · READ & WRITE · NO RESTART"
    twd = spaced_width(d, tag, f_tag, 3)
    dotcol = mix(CREAM, INDIGO, 0.72)
    spaced_multi(d, (cx - twd / 2, ry + 22), tag, f_tag, 3, SOFT, dotcol)

def draw_border(d):
    m = 26
    d.rectangle([m, m, W - m, H - m], outline=mix(CREAM, INK, 0.10), width=1)

FRAMES = 132
frames = []
for fr in range(FRAMES):
    off = P * fr / FRAMES        # exactly one period over the loop → seamless
    img = Image.new("RGB", (W, H), CREAM)
    d = ImageDraw.Draw(img)
    draw_border(d)
    for lane in lanes:
        draw_lane(d, lane, off)
    draw_wordmark(d)
    frames.append(img)

master = frames[0].quantize(colors=112, method=Image.MEDIANCUT)
pal = [f.quantize(palette=master, dither=Image.Dither.NONE) for f in frames]
out = os.path.join(os.path.dirname(__file__), "hero.gif")
pal[0].save(out, save_all=True, append_images=pal[1:], duration=56, loop=0, optimize=True, disposal=1)
print("frames:", len(pal), "size:", round(os.path.getsize(out) / 1024, 1), "KB")
frames[0].save(os.path.join(os.path.dirname(__file__), "hero-still.png"))
