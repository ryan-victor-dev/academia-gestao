#!/bin/sh
# Backup do banco (ADR 0020). Roda dentro do container "backup" (imagem do Postgres, mesma
# versão do banco), com o dono do banco: o backup inclui o histórico das migrations.
#
#   sh rotina.sh            todo dia, a partir de BACKUP_HORA (3h); se o servidor estava
#                           desligado no horário, faz assim que voltar
#   sh rotina.sh agora      um backup na hora, nos diários (como o agendado)
#   sh rotina.sh para ARQ   um backup avulso em /backups/ARQ, sem mexer nos diários (usado
#                           por scripts/restaurar-backup.sh antes de restaurar)
#
# Guarda 7 diários (backups/diario) e 4 semanais (backups/semanal: o primeiro de cada semana).
# Copie a pasta para fora do servidor: contra disco queimado, só uma cópia em outro lugar salva.
set -u
PASTA=/backups
HORA=${BACKUP_HORA:-3}
DIARIOS=7
SEMANAIS=4

log() { echo "$(date '+%F %T') $*"; }

# Dump legível em $1 (grava num parcial e só troca se estiver legível: um backup que falhou
# no meio nunca toma o lugar de um bom).
gerar_dump() {
    parcial="$(dirname "$1")/.$(basename "$1").parcial"
    if ! touch "$parcial" 2>/dev/null; then
        log "BACKUP FALHOU: sem permissão para gravar em $(dirname "$1") (confira BACKUP_UID/BACKUP_GID no .env)"
        return 1
    fi
    if ! pg_dump --format=custom --file="$parcial"; then
        log "BACKUP FALHOU: pg_dump"; rm -f "$parcial"; return 1
    fi
    if ! pg_restore --list "$parcial" >/dev/null; then
        log "BACKUP FALHOU: arquivo gerado está ilegível"; rm -f "$parcial"; return 1
    fi
    mv "$parcial" "$1"
}

apagar_antigos() {  # pasta quantos
    ls -1t "$1"/academia-*.dump 2>/dev/null | tail -n +"$(($2 + 1))" | xargs -r rm -f
}

fazer_backup() {
    dia=$(date +%F)
    arquivo="$PASTA/diario/academia-$dia.dump"
    mkdir -p "$PASTA/diario" "$PASTA/semanal" 2>/dev/null
    gerar_dump "$arquivo" || return 1
    # Semanal: o primeiro backup de cada semana (resiste a domingos com o servidor desligado).
    if [ -z "$(find "$PASTA/semanal" -name 'academia-*.dump' -mtime -7 2>/dev/null)" ]; then
        cp "$arquivo" "$PASTA/semanal/"
    fi
    apagar_antigos "$PASTA/diario" "$DIARIOS"
    apagar_antigos "$PASTA/semanal" "$SEMANAIS"
    echo "$dia" > "$PASTA/.ultimo-backup"
    log "Backup ok: $(basename "$arquivo") ($(du -h "$arquivo" | cut -f1))"
}

case "${1:-agendado}" in
    agora)
        fazer_backup
        ;;
    para)
        destino="$PASTA/$(basename "${2:?informe o nome do arquivo}")"
        gerar_dump "$destino" && log "Backup avulso ok: $(basename "$destino")"
        ;;
    agendado)
        log "Rotina de backup: todo dia a partir das ${HORA}h ($DIARIOS diários e $SEMANAIS semanais)"
        while true; do
            hora_atual=$(expr "$(date +%H)" + 0)
            if [ "$hora_atual" -ge "$HORA" ] && [ "$(cat "$PASTA/.ultimo-backup" 2>/dev/null)" != "$(date +%F)" ]; then
                fazer_backup
            fi
            sleep 300  # se falhou, tenta de novo daqui a 5 minutos
        done
        ;;
    *)
        echo "uso: rotina.sh [agora | para ARQUIVO]" >&2; exit 2
        ;;
esac
