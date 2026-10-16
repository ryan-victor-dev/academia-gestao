# ADR 0004 — Regras de negócio em Java puro, separadas da persistência

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

A regra mais sensível do sistema é o ciclo de vida da mensalidade: ela não pode
ser paga duas vezes, nem estornada sem ter sido paga, nem reaberta depois de
cancelada. Se essas regras ficarem espalhadas em services e controllers,
qualquer `setStatus(...)` em qualquer lugar fura a regra.

## Decisão

- O ciclo de vida é uma **máquina de estados** explícita em `StatusMensalidade`:
  cada estado declara para quais pode ir (`PENDENTE → PAGA | VENCIDA | CANCELADA`,
  `VENCIDA → PAGA | CANCELADA`, `PAGA → ESTORNADA`; `ESTORNADA` e `CANCELADA`
  são finais).
- `Mensalidade` não tem `setStatus`. O status só muda por métodos com nome de
  negócio (`pagar`, `marcarVencida`, `estornar`, `cancelar`), que validam a
  transição e lançam `TransicaoInvalidaException` quando ela não é permitida.
- Criação só pela fábrica `Mensalidade.nova(...)`, que valida os dados.
- Essas classes são **Java puro**: sem `@Entity`, sem Spring. O mapeamento para
  o banco será feito à parte quando a persistência da cobrança chegar.

## Consequências

- As regras são testadas com testes de unidade rápidos, sem banco e sem subir o
  Spring (11 testes em `MensalidadeTest`).
- É impossível, pelo código, colocar uma mensalidade em estado inválido.
- Custo: vai existir uma conversão entre o objeto de domínio e a entidade JPA
  (ou um mapeamento cuidadoso). É um preço aceito pela segurança da regra.
- Em aberto com a academia: o que significa "pagamento com problema" e a regra
  do dia de vencimento. Podem gerar estados ou transições novos — se gerarem,
  entram aqui com testes antes.
