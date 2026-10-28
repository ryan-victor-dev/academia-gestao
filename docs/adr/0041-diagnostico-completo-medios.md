# ADR 0041 — Diagnóstico completo (27/10): os três médios

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O diagnóstico completo de 27/10 (reta final, antes de ir ao ar) não achou nada alto e achou três
médios: o pagamento simulado num servidor de verdade (M1), o backup em claro e legível por todos na
máquina (M2) e a força bruta do código do "esqueci a senha" (M3). O dono pediu (28/10) que os três
sejam resolvidos, um por commit, e decidiu o que era dele: cifrar o backup com `age` (M2) e um teto
de 10 códigos por pessoa em 24 horas (M3).

## Decisões

### M1 — O simulado não sobe num servidor de verdade
Antes: com `PAGAMENTOS_PROVEDOR=simulado`, a rota de simular deixa o aluno marcar a própria
cobrança como paga sem pagar, e nada impedia ligar o simulado num servidor com domínio (a
aplicação nem recebia o `DOMINIO`, só o Caddy). Um `.env` copiado do ambiente de teste virava
fraude.

Agora o Compose passa o `DOMINIO` à aplicação (`academia.dominio`), e o `ProvedorSimulado` recusa
subir quando ele não é vazio nem `localhost` (`TravaDoSimulado`, a mesma regra do
`scripts/usuario-validacao.sh`). A aplicação inteira não sobe, com a mensagem no log: melhor fora do
ar do que recebendo "pagamentos" de mentira. Sem o simulado, o `DOMINIO` não muda nada.
