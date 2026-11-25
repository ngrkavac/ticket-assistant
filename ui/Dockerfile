# Usage: docker build -t helpdesk-ui ui
# Static build served by unprivileged nginx, which proxies /ask, /requests, /customers and
# /questions to the assistant. It runs behind oauth2-proxy and turns the access token that
# oauth2-proxy passes (X-Forwarded-Access-Token) into "Authorization: Bearer" (K2, nginx.conf.template).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
# Rendered by the image's entrypoint (envsubst) into /etc/nginx/conf.d/default.conf at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV ASSISTANT_URL=http://assistant:8080
# Substitute only these variables, so nginx's own $uri, $host, ... stay untouched.
ENV NGINX_ENVSUBST_FILTER="^ASSISTANT_URL$"
EXPOSE 8080
