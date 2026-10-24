FROM ubuntu:24.04 AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
    clang make gcc pkg-config libbpf-dev libc6-dev linux-libc-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY Makefile ./
COPY src src
RUN make

FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends libbpf1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/bin/ids-agent /app/ids-agent
COPY --from=build /src/bin/ids.bpf.o /app/ids.bpf.o
ENTRYPOINT ["/app/ids-agent", "--object", "/app/ids.bpf.o"]
