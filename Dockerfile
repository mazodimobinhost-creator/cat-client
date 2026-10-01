# Reproducible build image for Cat Client (Android app + Mihomo core + panel tests).
# Usage:
#   docker build -t cat-client-build .
#   docker run --rm -v "$PWD/out:/out" cat-client-build           # → out/app-debug.apk
#   docker run --rm cat-client-build npm test                      # panel tests only
FROM eclipse-temurin:21-jdk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GOTOOLCHAIN=auto \
    DEBIAN_FRONTEND=noninteractive
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:/usr/local/go/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl unzip ca-certificates python3 make nodejs npm \
    && rm -rf /var/lib/apt/lists/*

# Go (bootstrap; the core build script pins its exact toolchain via GOTOOLCHAIN)
ARG GO_VERSION=1.23.4
RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" | tar -C /usr/local -xz

# Android SDK command-line tools
ARG CMDLINE_TOOLS=11076708
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -o /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/ct.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" >/dev/null

WORKDIR /src
COPY . .
RUN npm ci --no-audit --no-fund

# Build debug APK by default; override the command for other tasks.
CMD ["sh", "-c", "./gradlew --no-daemon :app:assembleDebug && mkdir -p /out && cp app/build/outputs/apk/debug/*.apk /out/"]
