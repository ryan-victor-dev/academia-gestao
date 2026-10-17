#!/usr/bin/env bash
# Restaura um backup do banco (ADR 0020). APAGA os dados atuais e põe os do arquivo no lugar.
#
# Uso:  scripts/restaurar-backup.sh backups/diario/academia-AAAA-MM-DD.dump --sim
#
# Passos: faz antes um backup do estado atual (backups/antes-de-restaurar-*.dump, para
# desfazer), para a aplicação, restaura numa transação só (falhou, nada muda), e sobe a
# aplicação de novo. Rode na pasta do projeto, com o sistema no ar.
# Variáveis opcionais: PG_CONTAINER (padrão academia-postgres).
set -euo pipefail
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
PG=${PG_CONTAINER:-academia-postgres}
ARQUIVO=${1:?informe o arquivo de backup}
[ "${2:-}" = "--sim" ] || { echo "Isto apaga os dados atuais. Para confirmar, repita com --sim no final." >&2; exit 1; }
[ -f "$ARQUIVO" ] || { echo "Arquivo não encontrado: $ARQUIVO" >&2; exit 1; }
cd "$RAIZ"
PASTA=$( (grep -E '^BACKUP_PASTA=' .env 2>/dev/null || true) | cut -d= -f2-)
PASTA=${PASTA:-./backups}

# Copia o arquivo antes de tudo: o passo 1 grava outro backup, e nada pode tocar no que
# vai ser restaurado.
COPIA=$(mktemp)
trap 'rm -f "$COPIA"' EXIT
cp "$ARQUIVO" "$COPIA"

echo "1/4 Backup do estado atual (para desfazer, se preciso)..."
NOME="antes-de-restaurar-$(date +%Y%m%d-%H%M%S).dump"
docker compose run --rm -T backup para "$NOME" >/dev/null
SALVA="$PASTA/$NOME"
[ -s "$SALVA" ] || { echo "Não consegui salvar o estado atual; nada foi alterado." >&2; exit 1; }
echo "    salvo em $SALVA"

echo "2/4 Parando a aplicação..."
docker compose stop app >/dev/null

echo "3/4 Restaurando $ARQUIVO..."
# Dono do banco (as migrations e as extensões são dele); transação única: ou tudo, ou nada.
if ! docker exec -i "$PG" pg_restore -U academia -d academia --clean --if-exists --no-owner \
        --single-transaction --exit-on-error < "$COPIA"; then
    echo "RESTAURAÇÃO FALHOU: os dados continuam como estavam. Subindo a aplicação de novo." >&2
    docker compose start app >/dev/null
    exit 1
fi

echo "4/4 Subindo a aplicação..."
docker compose start app >/dev/null
for _ in $(seq 60); do
    docker compose exec -T app true 2>/dev/null && curl -sf http://127.0.0.1:${APP_PORTA:-8090}/actuator/health >/dev/null && break
    sleep 2
done
echo "Restaurado. Se algo estiver errado, restaure $SALVA da mesma forma."
