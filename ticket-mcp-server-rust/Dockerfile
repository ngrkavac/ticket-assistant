# Usage: docker build -t ticket-mcp-server-rust ticket-mcp-server-rust
FROM rust:1-slim-trixie AS build
WORKDIR /src
# Build dependencies in their own layer so source edits don't recompile them.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs && touch src/lib.rs \
 && cargo build --release --locked && rm -rf src
COPY src src
RUN touch src/main.rs src/lib.rs && cargo build --release --locked

FROM debian:trixie-slim
# curl is used by the docker compose healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
COPY --from=build /src/target/release/ticket-mcp-server-rust /usr/local/bin/
USER 10001
EXPOSE 8081
ENTRYPOINT ["ticket-mcp-server-rust"]
