#!/usr/bin/env bash
# Cria (ou recria) um usuário de validação direto no banco e imprime um token dele.
#
#   ATENÇÃO: só para desenvolvimento. Escreve direto no banco, passando por cima das
#   regras da API. Nunca rode num servidor de verdade. A trava abaixo recusa rodar
#   quando o .env tem um DOMINIO configurado.
#   O usuário já sai com o e-mail confirmado (ADR 0038): com o e-mail ligado, senão, ficaria
#   preso na confirmação, e os roteiros de validação parariam.
#
# Uso:  scripts/usuario-validacao.sh PERFIL [aluno_id]
#       PERFIL: ADMIN, RECEPCAO, INSTRUTOR ou ALUNO (ALUNO exige o id de um aluno)
# Variáveis opcionais: PG_CONTAINER (padrão academia-postgres),
#                      BASE_URL (padrão https://localhost:<HTTPS_PORTA do .env>),
#                      COOKIE_JAR (arquivo onde guardar o cookie da sessão renovável, ADR 0033),
#                      VALIDACAO_SENHA (senha a usar, para testar as telas; padrão: aleatória)
# Se o usuário já existe, ganha senha nova e o token anterior deixa de valer. Ele não é
# apagado e recriado: quem já registrou algo (matrícula, pagamento) é referenciado por
# chave estrangeira, e o DELETE falharia.
# Apagar depois: primeiro o que eles registraram, depois
#                DELETE FROM usuarios WHERE email LIKE 'validacao-%';
set -euo pipefail
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${ENV_FILE:-$RAIZ/.env}

DOMINIO=$( (grep -E '^DOMINIO=' "$ENV_FILE" 2>/dev/null || true) | cut -d= -f2-)
if [ -n "$DOMINIO" ] && [ "$DOMINIO" != "localhost" ]; then
  echo "Recusado: o .env tem DOMINIO=$DOMINIO, então isto parece um servidor de verdade." >&2
  exit 1
fi
PORTA=$( (grep -E '^HTTPS_PORTA=' "$ENV_FILE" 2>/dev/null || true) | cut -d= -f2-)
PG=${PG_CONTAINER:-academia-postgres}
URL=${BASE_URL:-https://localhost:${PORTA:-443}}
PERFIL=${1:?informe o perfil: ADMIN, RECEPCAO, INSTRUTOR ou ALUNO}
ALUNO=${2:-}
EMAIL="validacao-$(echo "$PERFIL" | tr 'A-Z' 'a-z')@academia.local"
SENHA=${VALIDACAO_SENHA:-senha-validacao-$(openssl rand -hex 6)}

# BCrypt com a mesma biblioteca da aplicação.
CP=$RAIZ/target/validacao/cp.txt
if [ ! -s "$CP" ]; then
  mkdir -p "$(dirname "$CP")"
  (cd "$RAIZ" && ./mvnw -B -q dependency:build-classpath -Dmdep.outputFile="$CP" -Dmdep.includeScope=test >/dev/null)
fi
CRYPTO=$(tr ':' '\n' < "$CP" | grep spring-security-crypto)
LOGS=$(tr ':' '\n' < "$CP" | grep -E 'commons-logging|spring-jcl' | head -1)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
printf 'public class H { public static void main(String[] a) { System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(a[0])); } }\n' > "$TMP/H.java"
HASH=$(java -cp "$CRYPTO:$LOGS" "$TMP/H.java" "$SENHA")

if [ -n "$ALUNO" ]; then VALOR_ALUNO="'$ALUNO'"; else VALOR_ALUNO=NULL; fi
docker exec "$PG" psql -U academia -d academia -v ON_ERROR_STOP=1 -q \
  -c "INSERT INTO usuarios (id,nome,email,senha_hash,perfil,aluno_id,email_confirmado_em) VALUES (gen_random_uuid(),'Validacao $PERFIL','$EMAIL','$HASH','$PERFIL',$VALOR_ALUNO,now())
      ON CONFLICT (lower(email)) DO UPDATE SET senha_hash = EXCLUDED.senha_hash, perfil = EXCLUDED.perfil,
        aluno_id = EXCLUDED.aluno_id, ativo = true, precisa_trocar_senha = false, email_confirmado_em = now(),
        versao_token = usuarios.versao_token + 1" >&2

TOKEN=$(curl -sk ${COOKIE_JAR:+-c "$COOKIE_JAR"} -X POST "$URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"senha\":\"$SENHA\"}" | python3 -I -c 'import sys,json; print(json.load(sys.stdin).get("token",""))')
[ -n "$TOKEN" ] || { echo "Não consegui obter o token de $PERFIL (limite de login? aplicação no ar?)" >&2; exit 1; }
echo "$TOKEN"
