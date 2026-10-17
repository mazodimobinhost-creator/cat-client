FROM node:22-alpine
RUN addgroup -S cat && adduser -S cat -G cat
WORKDIR /app
COPY --chown=cat:cat package.json server.js ./
USER cat
ENV NODE_ENV=production PORT=8080 CAT_DATA_DIR=/data
EXPOSE 8080
CMD ["node", "server.js"]
