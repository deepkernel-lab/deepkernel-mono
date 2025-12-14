FROM node:18-alpine AS build
WORKDIR /app

ARG VITE_BACKEND_URL=http://localhost:8001
ENV VITE_BACKEND_URL=${VITE_BACKEND_URL}

COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM node:18-alpine
WORKDIR /app
RUN npm install -g serve
COPY --from=build /app/dist ./dist
EXPOSE 3001
CMD ["serve", "-s", "dist", "-l", "3001"]
