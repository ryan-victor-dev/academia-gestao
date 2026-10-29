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
#
# Cifrado (ADR 0041, M2): o backup tem CPF, e-mail e telefone de todos os alunos. Sai cifrado com
# a chave pública do .env (BACKUP_CHAVE_PUBLICA, do age); só a chave privada, guardada fora do
# servidor, o abre. O dump em claro só existe na memória do container (/tmp é tmpfs), e a pasta e
# os arquivos são só do dono (700 e 600), como os antigos, em claro, até a rotação apagá-los.
set -u
umask 077
PASTA=/backups
CHAVE=${BACKUP_CHAVE_PUBLICA:-}
HORA=${BACKUP_HORA:-3}
DIARIOS=7
SEMANAIS=4

log() { echo "$(date '+%F %T') $*"; }

# Ao religar a máquina, o Docker sobe todos os containers juntos, sem esperar o banco (o
# depends_on só vale no "compose up"), e o backup atrasado saía antes do banco aceitar conexões:
# "BACKUP FALHOU" no log, à toa, até a nova tentativa em 5 minutos. Espera até 2 minutos; depois,
# deixa o pg_dump falhar e dizer o motivo.
esperar_banco() {
    tentativas=60
    until pg_isready --quiet; do
        tentativas=$((tentativas - 1))
        [ "$tentativas" -gt 0 ] || return 0
        sleep 2
    done
}

# Só o dono lê a pasta e os arquivos, inclusive os de antes desta regra.
fechar_permissoes() {
    chmod 700 "$PASTA" "$PASTA/diario" "$PASTA/semanal" 2>/dev/null
    find "$PASTA" -type f -exec chmod 600 {} + 2>/dev/null
}

# Dump legível e cifrado em $1 (grava num parcial e só troca no fim: um backup que falhou no
# meio nunca toma o lugar de um bom). Confere o dump em claro antes de cifrar: depois, sem a
# chave privada, ninguém aqui consegue.
gerar_dump() {
    if [ -z "$CHAVE" ]; then
        log "BACKUP FALHOU: sem BACKUP_CHAVE_PUBLICA no .env (veja o README)"; return 1
    fi
    parcial="$(dirname "$1")/.$(basename "$1").parcial"
    claro=/tmp/backup-em-claro.dump
    if ! touch "$parcial" 2>/dev/null; then
        log "BACKUP FALHOU: sem permissão para gravar em $(dirname "$1") (confira BACKUP_UID/BACKUP_GID no .env)"
        return 1
    fi
    esperar_banco
    if ! pg_dump --format=custom --file="$claro"; then
        log "BACKUP FALHOU: pg_dump"; rm -f "$parcial" "$claro"; return 1
    fi
    if ! pg_restore --list "$claro" >/dev/null; then
        log "BACKUP FALHOU: arquivo gerado está ilegível"; rm -f "$parcial" "$claro"; return 1
    fi
    if ! age --encrypt --recipient "$CHAVE" --output "$parcial" "$claro"; then
        log "BACKUP FALHOU: não consegui cifrar (confira BACKUP_CHAVE_PUBLICA no .env)"; rm -f "$parcial" "$claro"; return 1
    fi
    rm -f "$claro"
    mv "$parcial" "$1"
}

# Conta os cifrados e os antigos em claro juntos: os em claro saem primeiro, por serem os mais velhos.
apagar_antigos() {  # pasta quantos
    ls -1t "$1"/academia-*.dump "$1"/academia-*.dump.age 2>/dev/null | tail -n +"$(($2 + 1))" | xargs -r rm -f
}

fazer_backup() {
    dia=$(date +%F)
    arquivo="$PASTA/diario/academia-$dia.dump.age"
    mkdir -p "$PASTA/diario" "$PASTA/semanal" 2>/dev/null
    fechar_permissoes
    gerar_dump "$arquivo" || return 1
    # Semanal: o primeiro backup de cada semana (resiste a domingos com o servidor desligado).
    if [ -z "$(find "$PASTA/semanal" -name 'academia-*.dump.age' -mtime -7 2>/dev/null)" ]; then
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
        fechar_permissoes
        gerar_dump "$destino" && log "Backup avulso ok: $(basename "$destino")"
        ;;
    agendado)
        log "Rotina de backup: todo dia a partir das ${HORA}h ($DIARIOS diários e $SEMANAIS semanais, cifrados)"
        [ -n "$CHAVE" ] || log "BACKUP FALHOU: sem BACKUP_CHAVE_PUBLICA no .env (veja o README)"
        fechar_permissoes
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
