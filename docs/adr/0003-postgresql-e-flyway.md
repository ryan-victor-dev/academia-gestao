# ADR 0003 — PostgreSQL com schema versionado pelo Flyway

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

Os dados (alunos, mensalidades, pagamentos) são relacionais e precisam de
integridade: CPF único, valores positivos, chaves estrangeiras. O schema vai
mudar muitas vezes ao longo das etapas, e o banco de produção não pode ser
recriado a cada mudança.

## Decisão

- **PostgreSQL 17**, rodando em Docker (Compose com healthcheck).
- **Flyway** gerencia o schema: cada mudança é um arquivo
  `src/main/resources/db/migration/V<n>__descricao.sql`, aplicado uma vez e
  registrado na tabela `flyway_schema_history`.
- Hibernate com `ddl-auto: validate`: ele **confere** se as entidades batem com
  o banco, mas nunca altera o schema por conta própria.
- Migration aplicada é imutável. Corrigir algo = escrever uma migration nova
  (foi o que aconteceu com `V2__adicionar_observacoes_aluno.sql`).
- Restrições de negócio simples ficam também no banco (`CHECK`, `UNIQUE`,
  `NOT NULL`), não só no Java.
- Chaves primárias são `UUID`, geradas pela aplicação.

## Consequências

- Qualquer ambiente (meu PC, CI, servidor da academia) chega ao mesmo schema
  rodando as mesmas migrations em ordem.
- Mudanças de banco ficam revisáveis no Git, como código.
- Não há "desfazer" automático: reverter exige uma migration nova escrita à mão.
