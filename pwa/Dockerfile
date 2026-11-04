# Hommaterial for the home server: a small Node server with SQLite inside, which serves the web app
# as it is (no build step) and speaks to Alexa, SwitchBot and the LG TVs in place of the browser.
FROM node:24-alpine
RUN apk add --no-cache openssl tini
WORKDIR /app
COPY server/package.json server/package-lock.json ./server/
RUN cd server && npm ci --omit=dev --no-audit --no-fund
COPY shared ./shared
COPY web ./web
COPY server/src ./server/src
COPY casaos/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENV NODE_ENV=production DATA_DIR=/data PORT=8080 HTTPS_PORT=8443
EXPOSE 8080 8443
VOLUME /data
# The container runs on the network of the host, on the port chosen at install time.
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s CMD wget -qO- "http://127.0.0.1:${PORT}/api/session" >/dev/null || exit 1
ENTRYPOINT ["/sbin/tini", "--", "docker-entrypoint.sh"]
CMD ["node", "--disable-warning=ExperimentalWarning", "server/src/main.ts"]
