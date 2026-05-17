FROM gradle:8.7-jdk21 AS build

WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN gradle bootJar --no-daemon


FROM ubuntu:24.04

WORKDIR /app

RUN apt-get update -o Acquire::ForceIPv4=true && \
    apt-get install -y --fix-missing \
    openjdk-21-jre \
    ffmpeg \
    imagemagick \
    libheif1 \
    libheif-examples \
    libheif-plugin-libde265 \
    libheif-plugin-x265 \
    libde265-0 \
    libaom3 \
    libx265-dev \
    libheif-dev \
    locales \
    python3 \
    python3-pip \
    python3-venv \
    && locale-gen C.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --break-system-packages pillow pillow-heif

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

COPY --from=build /app/build/libs/*.jar app.jar

RUN mkdir -p \
    /storage \
    /storage/main \
    /storage/HDD2 \
    /security \
    /cache/metadata \
    /cache/thumbnails \
    /cache/previews \
    /cache/folders \
    /cache/upload_tmp

EXPOSE 38471

RUN cat > /usr/local/bin/heic2jpg.py <<'EOF'
import sys
from PIL import Image
from pillow_heif import register_heif_opener

register_heif_opener()

input_path = sys.argv[1]
output_path = sys.argv[2]

img = Image.open(input_path)
img = img.convert("RGB")
img.thumbnail((1200, 1200))
img.save(output_path, "JPEG", quality=85)
EOF

RUN chmod +x /usr/local/bin/heic2jpg.py

RUN ln -sf /usr/bin/convert /usr/bin/magick

ENTRYPOINT ["java", "-jar", "/app/app.jar"]