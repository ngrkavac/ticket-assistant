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
# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/default.conf at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV ASSISTANT_URL=http://assistant:8080
# Substitute only these variables, so nginx's own $uri, $host, ... stay untouched.
ENV NGINX_ENVSUBST_FILTER="^ASSISTANT_URL$"
EXPOSE 8080
