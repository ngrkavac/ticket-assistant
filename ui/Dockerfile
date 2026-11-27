# Usage: docker build -t helpdesk-ui ui
# Static build served by unprivileged nginx, which proxies /ask, /requests, /customers and
# /questions to the assistant. It runs behind oauth2-proxy and turns the access token that
# oauth2-proxy passes (X-Forwarded-Access-Token) into "Authorization: Bearer" (K2, nginx.conf.template).
# Base images are pinned to a version tag plus digest; Renovate updates both.
FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
# Apply fixes published after the base image was built (the Trivy gate in images.yml fails on
# fixable HIGH/CRITICAL findings): Alpine packages from the Alpine repositories, and nginx with
# its modules from nginx.org's package repository (signed with nginx_signing.rsa.pub, which the
# base image already trusts), since nginx stable releases can precede the matching base image.
# The base image pins nginx and its modules to exact versions in /etc/apk/world, so they are
# re-added as "~<major.minor>" with --upgrade to get the newest patch release of the same stable line.
# The base image runs as the unprivileged nginx user (101), so the upgrade switches to root and back.
USER root
# CI passes the current date (images.yml), so this layer is not restored from the layer cache with
# an old package index for more than a day. Unused here except as a cache key.
ARG PACKAGE_REFRESH=unset
RUN set -eu; \
    repo="https://nginx.org/packages/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main"; \
    line="$(nginx -v 2>&1 | sed 's|.*nginx/||' | cut -d. -f1,2)"; \
    apk upgrade --no-cache; \
    apk add --no-cache --upgrade --repository "$repo" $(apk info 2>/dev/null | grep '^nginx' | sed "s/\$/~$line/"); \
    nginx -v
USER 101
# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/default.conf at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV ASSISTANT_URL=http://assistant:8080
# Substitute only these variables, so nginx's own $uri, $host, ... stay untouched.
ENV NGINX_ENVSUBST_FILTER="^ASSISTANT_URL$"
EXPOSE 8080
