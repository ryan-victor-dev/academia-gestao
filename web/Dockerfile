# Imagem do Caddy com as telas (ADR 0033): o Node só constrói; quem serve é o Caddy.

# ===== Etapa 1: construir as telas =====
FROM node:24-alpine AS build
WORKDIR /web
# Primeiro só o que define as dependências (aproveita o cache do Docker). npm ci instala
# exatamente o package-lock.json, sem scripts de instalação (.npmrc).
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run build

# ===== Etapa 2: servir =====
FROM caddy:2.11-alpine
# O curl (e as bibliotecas dele, como a do brotli) vem na imagem oficial, mas o Caddy não usa:
# fora, a imagem perde 3 vulnerabilidades altas e o que um invasor usaria para baixar arquivos.
RUN apk del --no-cache curl
COPY --from=build /web/dist /srv
