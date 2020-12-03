FROM oracle/graalvm-ce:19.3.1 AS builder

# As of GraalVM 19.0.0, native-image is no longer included by default:
RUN gu install native-image

RUN curl https://download.clojure.org/install/linux-install-1.10.1.492.sh | bash

RUN mkdir -p /app/classes

WORKDIR app

ADD src /app/src
ADD deps.edn /app/

RUN clojure -A:aot
RUN clojure -A:native-image --verbose

FROM jrottenberg/ffmpeg:4.1-ubuntu

COPY --from=builder /app/frame-stream-server /frame-stream-server

ENTRYPOINT ["/frame-stream-server"]
