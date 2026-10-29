# ADR 0044 — Antes de ir ao ar: o backup ao religar e a geração das cobranças antigas

- **Status:** Aceito
- **Data:** 2026-10-29

## Contexto

Dos itens deixados para antes de pôr o sistema num servidor de verdade (lista consolidada de 23/10),
dois não dependiam de decisão do dono: o 19 (backup ao religar a máquina, diagnóstico de 18/10, D1) e
o 20 (geração das cobranças das matrículas antigas, diagnóstico da etapa 6, R11). O dono pediu os dois
em 29/10, um por commit.

## Decisões

### 19 — O backup espera o banco
Ao religar a máquina, o Docker sobe todos os containers juntos pela política de reinício: o
`depends_on` com `service_healthy` só vale no `docker compose up`. Se o backup do dia estava
atrasado, a rotina rodava antes de o banco aceitar conexões, e o log mostrava `BACKUP FALHOU: pg_dump`
à toa; a nova tentativa, 5 minutos depois, dava certo. Um alarme falso ensina a ignorar o alarme.

Agora, antes de cada `pg_dump`, a rotina espera o banco com `pg_isready` por até 2 minutos. Depois
disso, deixa o `pg_dump` falhar e dizer o motivo: um banco que não volta continua aparecendo no log,
e a rotina não fica presa (o próximo ciclo, em 5 minutos, tenta de novo).

Verificado num banco isolado que sobe 10 s depois do backup (a versão antiga falha, a nova faz o
backup), num banco que nunca sobe (desiste em 2 minutos com o erro) e com dois mutantes: sem o teto,
trava; com espera curta, falha.
