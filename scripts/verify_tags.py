#!/usr/bin/env python3
"""Independent check of Kororythm's tag writer.

Run after `./gradlew :app:testDebugUnitTest --tests "*TagIoTest*"`. Reads the files the
tests wrote (app/build/tagtest/) with mutagen and decodes them with ffmpeg, comparing the
decoded audio (MD5 of the PCM) with the original input files.

Requires: pip install mutagen; ffmpeg on PATH.
"""
import os
import subprocess
import sys

import mutagen
from mutagen.flac import FLAC
from mutagen.id3 import ID3

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/test/resources/tags")
OUT = os.path.join(ROOT, "app/build/tagtest")

EXPECT = {
    "title": "Ñandú — 日本の夜",
    "artist": "Beyoncé & Grid Pilots",
    "album": "Runways",
    "albumartist": "Various Artists",
    "genre": "Synthwave",
    "date": "2018",
}

failures = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        failures.append(msg)


def pcm_md5(path):
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:a", "-f", "md5", "-"],
                       capture_output=True, text=True)
    return r.stdout.strip(), r.stderr.strip()


def same_audio(inp, out):
    a, ea = pcm_md5(os.path.join(RES, inp))
    b, eb = pcm_md5(os.path.join(OUT, out))
    check(not eb, f"{out}: ffmpeg decodes without errors {eb[:80]}")
    check(a == b and a != "", f"{out}: decoded audio identical to {inp}")


for inp, out in [("in16.flac", "out16.flac"), ("in24.flac", "out24.flac")]:
    print(out)
    f = FLAC(os.path.join(OUT, out))
    for k, v in EXPECT.items():
        check(f.get(k) == [v], f"{k} = {f.get(k)}")
    check(f.get("tracknumber") == ["3"] and f.get("tracktotal") == ["12"], "track 3 of 12")
    check(f.get("discnumber") == ["1"], "disc 1")
    check(len(f.pictures) == 1 and f.pictures[0].type == 3 and f.pictures[0].mime == "image/png", "one front cover")
    if inp == "in16.flac":
        check(f.get("replaygain_track_gain") == ["-3.00 dB"], "ReplayGain kept")
        check(f.get("description") == ["KeepMe"], "comment kept")
    check(f.info.bits_per_sample == FLAC(os.path.join(RES, inp)).info.bits_per_sample, f"bit depth {f.info.bits_per_sample}")
    same_audio(inp, out)

print("nocover.flac")
check(len(FLAC(os.path.join(OUT, "nocover.flac")).pictures) == 0, "cover removed")

for inp, out in [("in_v23.mp3", "out_v23.mp3"), ("in_v24.mp3", "out_v24.mp3"), ("in_bare.mp3", "out_bare.mp3")]:
    print(out)
    t = ID3(os.path.join(OUT, out))
    check(t.version == (2, 4, 0), f"ID3 {t.version}")
    check(str(t.get("TIT2")) == EXPECT["title"], f"title = {t.get('TIT2')}")
    check(str(t.get("TPE1")) == EXPECT["artist"], "artist")
    check(str(t.get("TALB")) == EXPECT["album"], "album")
    check(str(t.get("TPE2")) == EXPECT["albumartist"], "album artist")
    check(str(t.get("TCON")) == EXPECT["genre"], "genre")
    check(str(t.get("TDRC")) == "2018", "year")
    check(str(t.get("TRCK")) == "3/12", "track 3/12")
    apic = t.getall("APIC")
    check(len(apic) == 1 and apic[0].type == 3 and apic[0].mime == "image/png", "one front cover")
    if inp == "in_v23.mp3":
        check("TXXX:comment" in t.keys(), "ID3v2 comment kept")
        with open(os.path.join(OUT, out), "rb") as fh:
            fh.seek(-128, 2)
            check(fh.read(3) != b"TAG", "old ID3v1 removed")
    if inp == "in_v24.mp3":
        check(any(k.startswith("TXXX") for k in t.keys()), "custom frame kept")
    same_audio(inp, out)

print("lyrics")
LRC = "[00:01.00] Ñandú en la noche\n[00:02.50] second line"
f = FLAC(os.path.join(OUT, "lyrics_kept.flac"))
check(f.get("lyrics") == [LRC], "FLAC lyrics kept after a later edit")
t = ID3(os.path.join(OUT, "lyrics_kept.mp3"))
uslt = t.getall("USLT")
check(len(uslt) == 1 and uslt[0].text == LRC and uslt[0].lang == "eng", "MP3 USLT lyrics kept after a later edit")
same_audio("in_v24.mp3", "lyrics_kept.mp3")

print()
if failures:
    print(f"{len(failures)} check(s) failed")
    sys.exit(1)
print("All tag checks passed.")
