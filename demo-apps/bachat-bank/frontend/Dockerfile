FROM node:18-alpine AS build
WORKDIR /app

# Inject the backend URL at build time so the static bundle points to a
# host the browser can reach (the frontend runs in the user's browser,
# not inside the container network).
ARG VITE_BACKEND_URL=http://13.204.239.189:8000
ENV VITE_BACKEND_URL=${VITE_BACKEND_URL}

COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM node:18-alpine
WORKDIR /app
RUN npm install -g serve
COPY --from=build /app/dist ./dist
EXPOSE 3000
CMD ["serve", "-s", "dist", "-l", "3000"]

