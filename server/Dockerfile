# Build context is the repository root (see docker-compose.yml).
# PHP 8.1 is the target; the code also runs on newer, still security-supported versions:
#   docker compose build --build-arg PHP_VERSION=8.3
ARG PHP_VERSION=8.1

# Stage 1: build the Vue front-end
FROM node:20-bookworm-slim AS webbuild
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build

# Stage 2: PHP-FPM
FROM php:${PHP_VERSION}-fpm-bookworm AS php
RUN apt-get update \
    && apt-get install -y --no-install-recommends libsqlite3-dev libonig-dev \
    && docker-php-ext-install pdo_sqlite mbstring \
    && rm -rf /var/lib/apt/lists/*
COPY docker/php/zz-smsgate.ini /usr/local/etc/php/conf.d/zz-smsgate.ini
COPY docker/php/zz-smsgate.conf /usr/local/etc/php-fpm.d/zz-smsgate.conf
COPY server/ /var/www/server/
RUN mkdir -p /var/lib/smsgate && chown -R www-data:www-data /var/lib/smsgate
ENV SMSGATE_DATA_DIR=/var/lib/smsgate \
    SMSGATE_MIGRATIONS_DIR=/var/www/server/migrations
WORKDIR /var/www/server
VOLUME ["/var/lib/smsgate"]
EXPOSE 9000

# Stage 3: Nginx serving the SPA and proxying /api to PHP-FPM
FROM nginx:1.27-alpine AS nginx
COPY --from=webbuild /web/dist /usr/share/nginx/html
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
