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

### 20 — A geração das cobranças antigas desligada por padrão
Quando a cobrança chegou (ADR 0024), as matrículas que já existiam ganharam as cobranças de hoje
em diante numa rotina que roda ao subir a aplicação (`CobrancasDasMatriculasAntigas`). Ela ficou
ligada por padrão e roda em **toda** subida, varrendo todas as matrículas em lotes, com o Tomcat
já atendendo. Depois da primeira vez não sobra nada a gerar: as matrículas novas ganham as
cobranças na própria transação.

Numa instalação nova não há matrícula de antes da cobrança. Agora a rotina só sobe com
`academia.cobranca.gerar-das-antigas-ao-subir=true`, para quem atualizar uma instalação de antes
da cobrança (hoje, nenhuma: o Docker local do dono tem todas as matrículas com cronograma). O
código fica, com os testes dele, em vez de sair: é ele que cobre esse caso, se aparecer.

O `CobrancasDasMatriculasAntigasAoSubirTest` sobe só a rotina (a configuração de teste a desliga
explicitamente e esconderia o padrão): sem a propriedade, ela não existe; com `true`, existe.
