# Build stage
FROM node:20-alpine AS build
WORKDIR /app

# Copy the lockfile too and use npm ci: installs exactly the locked versions (reproducible builds).
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Production stage – serve with Nginx
FROM nginx:1.25-alpine AS runtime

# Copy build output
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx config: proxy /shorten, /urls, and /{alias} to the API
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
