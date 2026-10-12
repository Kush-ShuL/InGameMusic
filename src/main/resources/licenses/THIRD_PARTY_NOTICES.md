# Third-party components

InGame Music's own code is MIT licensed. Dependencies keep their respective licenses.
All audio decoding is Java code. No FFmpeg, JNI codec, external executable or runtime download is used.
Java Sound uses the Java runtime's platform audio output implementation.

| Component | Version | License | Source distribution |
| --- | --- | --- | --- |
| JSE AAC SPI (JAAD) | 1.1.0 | BSD-2-Clause | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-spi-aac/1.1.0/jse-spi-aac-1.1.0-sources.jar) |
| JSE MP3 SPI (Java LAME / mpg123) | 1.1.0 | LGPL-2.1 | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-spi-mp3/1.1.0/jse-spi-mp3-1.1.0-sources.jar) |
| JSE Vorbis SPI | 1.1.0 | Xiph BSD | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-spi-vorbis/1.1.0/jse-spi-vorbis-1.1.0-sources.jar) |
| JSE FLAC SPI | 1.1.0 | Xiph BSD | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-spi-flac/1.1.0/jse-spi-flac-1.1.0-sources.jar) |
| JSE Opus SPI (Concentus) | 1.1.0 | Xiph BSD | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-spi-opus/1.1.0/jse-spi-opus-1.1.0-sources.jar) |
| JSE API | 1.1.0 | BSD-3-Clause | [Maven sources](https://repo.maven.apache.org/maven2/io/github/jseproject/jse-api/1.1.0/jse-api-1.1.0-sources.jar) |
| Tritonus Share | 0.3.7.4 | LGPL-2.1 | [Maven sources](https://repo.maven.apache.org/maven2/com/googlecode/soundlibs/tritonus-share/0.3.7.4/tritonus-share-0.3.7.4-sources.jar) |
| Vorbis Java Core | 0.8 | Apache-2.0 | [Maven sources](https://repo.maven.apache.org/maven2/org/gagravarr/vorbis-java-core/0.8/vorbis-java-core-0.8-sources.jar) |
| jaudiotagger | 3.0.1 | LGPL-2.1 | [Maven sources](https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar) |
| Noto Sans SC | regular instance of Google Fonts variable font | SIL OFL 1.1 | [Upstream](https://github.com/google/fonts/tree/main/ofl/notosanssc), license in `src/main/resources/licenses/NotoSansSC-OFL.txt` |

Java dependencies are included as separate, unmodified nested JARs in `META-INF/jars/`, with no shading or relocation. Their original sources and POM license declarations can be fetched using `python3 tools/collect_dependency_sources.py`. Distribution bundles include this source archive. To replace a library with a compatible modified version, replace its nested JAR (preserving its generated Fabric metadata), or update its version in `build.gradle` and rebuild. Reverse engineering for debugging modifications to LGPL libraries is permitted under their licenses.

Fabric Loader, Fabric API and Minecraft are provided by the game installation and are not bundled.
