FROM ghcr.io/graalvm/native-image-community:25i3 AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew nativeCompile --no-daemon

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends libtesseract5 liblept5 \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s liblept.so.5 /usr/lib/x86_64-linux-gnu/libleptonica.so
WORKDIR /app
COPY --from=build /app/src/main/resources/tessdata /app/tessdata
ENV OCR_TESSDATA_PATH=/app/tessdata
COPY --from=build /app/build/native/nativeCompile/ /app/
ENTRYPOINT ["/app/receipt-extractor"]
