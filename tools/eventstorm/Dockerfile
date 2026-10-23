FROM golang:1.24.9-bookworm AS build
WORKDIR /src
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /eventstorm .
FROM scratch
COPY --from=build /eventstorm /eventstorm
USER 10001:10001
ENTRYPOINT ["/eventstorm"]
