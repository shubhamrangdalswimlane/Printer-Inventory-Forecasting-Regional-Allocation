# frontend/Dockerfile - builds the web image: the production React build served by unprivileged nginx.
# Build from the frontend folder:
#   docker build --build-arg VITE_OIDC_AUTHORITY=https://sso.example.com/realms/pifa -t pifa-web frontend
# The Keycloak realm URL is baked in at build time (Vite reads VITE_* variables while bundling).
# In Kubernetes the Helm chart mounts its own nginx.conf with the production security headers.

# ---- Stage 1: build -------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_OIDC_AUTHORITY
ENV VITE_OIDC_AUTHORITY=${VITE_OIDC_AUTHORITY}
RUN npm run build

# ---- Stage 2: runtime -----------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# nginx-unprivileged listens on 8080; state its non-root user (uid 101) explicitly so scanners and admission
# policies can verify the container never runs as root.
USER 101
EXPOSE 8080
