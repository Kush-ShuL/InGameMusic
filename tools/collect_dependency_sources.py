#!/usr/bin/env python3
"""Fetch exact published dependency sources/POMs for redistribution; not used at runtime."""
from pathlib import Path
from urllib.request import urlretrieve
import zipfile

DEPENDENCIES = [("io.github.jseproject", "jse-spi-" + c, "1.1.0") for c in ("aac", "mp3", "vorbis", "flac", "opus")] + [
    ("io.github.jseproject", "jse-api", "1.1.0"),
    ("com.googlecode.soundlibs", "tritonus-share", "0.3.7.4"),
    ("org.gagravarr", "vorbis-java-core", "0.8"),
    ("net.jthink", "jaudiotagger", "3.0.1"),
]
folder = Path("build/dependency-sources")
folder.mkdir(parents=True, exist_ok=True)
for group, artifact, version in DEPENDENCIES:
    prefix = f"{artifact}-{version}"
    for suffix in ("-sources.jar", ".pom"):
        name = prefix + suffix
        target = folder / name
        if not target.exists():
            urlretrieve(f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{name}", target)
        print(name)
with zipfile.ZipFile("build/ingamemusic-third-party-sources.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(folder.iterdir()):
        archive.write(path, path.name)
    archive.write("THIRD_PARTY_NOTICES.md")
