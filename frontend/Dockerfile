FROM node:20-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .

ARG VITE_API_BASE_URL=/api
ARG VITE_DEMO_MODE=false
ARG VITE_BACKEND_MODE=go
ARG VITE_GO_API_BASE_URL=/go-api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_DEMO_MODE=${VITE_DEMO_MODE}
ENV VITE_BACKEND_MODE=${VITE_BACKEND_MODE}
ENV VITE_GO_API_BASE_URL=${VITE_GO_API_BASE_URL}

RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
