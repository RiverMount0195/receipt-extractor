FROM ghcr.io/graalvm/native-image-community:25i3 AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew nativeCompile --no-daemon

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends libtesseract5 libleptonica5 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/src/main/resources/tessdata /app/tessdata
ENV OCR_TESSDATA_PATH=/app/tessdata
COPY --from=build /app/build/native/nativeCompile/receipt-extractor /app/receipt-extractor
ENTRYPOINT ["/app/receipt-extractor"]
