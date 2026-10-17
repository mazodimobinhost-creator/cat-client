FROM node:22-alpine
RUN addgroup -S cat && adduser -S cat -G cat
WORKDIR /app
RUN mkdir -p /data && chown cat:cat /data
COPY --chown=cat:cat package.json server.js xray-runtime.js auth-store.js ./
USER cat
ENV NODE_ENV=production PORT=8080 CAT_DATA_DIR=/data
EXPOSE 8080
CMD ["node", "server.js"]
