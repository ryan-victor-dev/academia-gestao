#!/usr/bin/env bash
# Teste de ponta a ponta numa instalação do zero, isolada do seu ambiente.
#
# Copia o projeto (como está no disco, sem o .env) para target/e2e, sobe tudo com outros
# nomes de container e outras portas, segue o README (cp .env.example .env + senhas
# aleatórias), roda scripts/e2e.py e SEMPRE desmonta no final, apagando os volumes.
#
# Uso:  scripts/e2e.sh          (leva uns 5 minutos: há esperas pelo limite de login)
# Depois da API (scripts/e2e.py), as telas num Chromium de verdade (web/e2e/telas.mjs).
# Requer, uma vez: cd web && npm ci && npx playwright install chromium
# Portas: E2E_HTTPS (padrão 18443) e E2E_HTTP (padrão 18089).
set -euo pipefail
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
DESTINO=$RAIZ/target/e2e/instalacao
export E2E_HTTPS=${E2E_HTTPS:-18443} E2E_HTTP=${E2E_HTTP:-18089}
export COMPOSE_PROJECT_NAME=academia-e2e E2E_PREFIXO=e2e-

if [ ! -d "$RAIZ/web/node_modules/playwright" ] || ! (cd "$RAIZ/web" && npx --no-install playwright --version >/dev/null 2>&1); then
  echo "Falta o Playwright das telas: cd web && npm ci && npx playwright install chromium" >&2
  exit 1
fi

desmontar() { [ -d "$DESTINO" ] && (cd "$DESTINO" && docker compose down -v >/dev/null 2>&1 || true); rm -rf "$DESTINO"; }
trap desmontar EXIT
desmontar
mkdir -p "$DESTINO"
(cd "$RAIZ" && git ls-files -co --exclude-standard | grep -vE '^(target/|\.env$)' | tar -cf - -T -) | tar -xf - -C "$DESTINO"

cd "$DESTINO"
# Isolamento: outros nomes e portas, para não colidir com o ambiente de desenvolvimento.
sed -i 's/container_name: academia-/container_name: e2e-/; s/"127.0.0.1:5432:5432"/"127.0.0.1:15432:5432"/; s/"127.0.0.1:8090:8090"/"127.0.0.1:18090:8090"/' docker-compose.yml
cp .env.example .env
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -base64 24)|; \
        s|^DB_APP_SENHA=.*|DB_APP_SENHA=$(openssl rand -base64 24)|; \
        s|^ADMIN_SENHA=.*|ADMIN_SENHA=senha-primeiro-acesso-123|; \
        s|^JWT_SEGREDO=.*|JWT_SEGREDO=$(openssl rand -base64 32)|; \
        s|^HTTP_PORTA=.*|HTTP_PORTA=$E2E_HTTP|; s|^HTTPS_PORTA=.*|HTTPS_PORTA=$E2E_HTTPS|; \
        s|^BACKUP_UID=.*|BACKUP_UID=$(id -u)|; s|^BACKUP_GID=.*|BACKUP_GID=$(id -g)|; \
        s|^PROXY_REDE=.*|PROXY_REDE=10.250.251.0/24|; s|^PROXY_IP_DO_CADDY=.*|PROXY_IP_DO_CADDY=10.250.251.10|" .env

echo "Subindo a instalação de teste (https://localhost:$E2E_HTTPS)..."
docker compose up --build -d >/dev/null 2>&1
for _ in $(seq 90); do curl -skf "https://localhost:$E2E_HTTPS/actuator/health" >/dev/null && break; sleep 2; done
set +e
python3 -I "$RAIZ/scripts/e2e.py" "$DESTINO"
API=$?
echo
echo "Telas (Chromium):"
node "$RAIZ/web/e2e/telas.mjs" "https://localhost:$E2E_HTTPS"
TELAS=$?
set -e
[ "$API" -eq 0 ] && [ "$TELAS" -eq 0 ]
