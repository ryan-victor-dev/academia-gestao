# ADR 0005 — Testes de integração com Testcontainers (Postgres real)

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

Banco em memória (H2) é rápido, mas não é PostgreSQL: tipos, funções e
restrições se comportam diferente. Um teste que passa no H2 pode quebrar em
produção, e as migrations escritas para Postgres podem nem rodar no H2.

## Decisão

- **Testes de unidade** (regras de domínio): JUnit 5 + AssertJ, sem Spring e
  sem banco.
- **Testes de integração**: Testcontainers sobe um `postgres:17` descartável
  para a suíte, o Flyway aplica as migrations nele e o Spring conecta via
  `@ServiceConnection` (`TestcontainersConfiguration`).
- O CI roda tudo com `./mvnw -B verify`. O runner do GitHub Actions já tem
  Docker, então o Testcontainers funciona lá sem configuração extra.

## Consequências

- Os testes exercitam o mesmo banco e as mesmas migrations de produção.
- Rodar os testes localmente exige Docker ligado.
- Os testes de integração são mais lentos que os de unidade; por isso as regras
  de negócio ficam em Java puro (ADR 0004) e são testadas sem container.
