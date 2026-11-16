"""Assemble binary artifacts and a complete source archive after a successful Gradle build."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
version = re.search(r"^version = '([^']+)'", (ROOT / "build.gradle").read_text(), re.MULTILINE).group(1)
DIST = ROOT / "dist" / version
DIST.mkdir(parents=True, exist_ok=True)
jar = ROOT / f"build/libs/ingamemusic-{version}.jar"
with zipfile.ZipFile(jar) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
    assert metadata["environment"] == "client"
    assert "top/mc_plfd_host/ingamemusic/ui/GeneratedCover.class" in archive.namelist()
    assert "top/mc_plfd_host/ingamemusic/ui/SmoothScroll.class" in archive.namelist()
    assert "top/mc_plfd_host/ingamemusic/compat/SimpleVoiceChatPlugin.class" in archive.namelist()
    assert "voicechat" in metadata["entrypoints"]
    assert not any(name.startswith("de/maxhenkel/") or "voicechat-api" in name for name in archive.namelist())

shutil.copy2(jar, DIST / jar.name)
for name in ("README.md", "LICENSE", "COPYING", "THIRD_PARTY_NOTICES.md"):
    shutil.copy2(ROOT / name, DIST / name)
shutil.copytree(ROOT / "docs", DIST / "docs", dirs_exist_ok=True)
third_party = ROOT / "build/ingamemusic-third-party-sources.zip"
if third_party.exists():
    shutil.copy2(third_party, DIST / third_party.name)

with zipfile.ZipFile(DIST / f"ingamemusic-{version}-project.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for folder in ("src", "gradle", "tools", "docs"):
        for source in (ROOT / folder).rglob("*"):
            if source.is_file() and "__pycache__" not in source.parts:
                archive.write(source, Path("InGameMusic") / source.relative_to(ROOT))
    for name in ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
                 ".gitignore", "README.md", "LICENSE", "COPYING", "THIRD_PARTY_NOTICES.md"):
        archive.write(ROOT / name, "InGameMusic/" + name)

checksums = []
for artifact in sorted(DIST.iterdir()):
    if artifact.suffix in (".jar", ".zip"):
        checksums.append(hashlib.sha256(artifact.read_bytes()).hexdigest() + "  " + artifact.name)
(DIST / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
print("Release artifacts: " + str(DIST))
