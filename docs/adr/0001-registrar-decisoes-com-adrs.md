# ADR 0001 — Registrar decisões de arquitetura com ADRs

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O projeto é ao mesmo tempo um freela real (uma academia vai usar) e uma peça
de portfólio. Daqui a alguns meses, eu — ou quem ler o código — vou querer saber
*por que* algo foi feito de um jeito, não só *o que* foi feito. Commits contam o
"o quê"; o "porquê" se perde.

## Decisão

Toda decisão que afeta a estrutura do sistema (e que seria cara de desfazer)
vira um ADR curto em `docs/adr/`, numerado em sequência, no formato:
Contexto → Decisão → Consequências.

ADRs não são editados depois de aceitos. Se uma decisão mudar, escrevo um ADR
novo e marco o antigo como **Substituído por ADR XXXX**.

## Consequências

- O histórico de raciocínio fica no próprio repositório, versionado junto do código.
- Custa alguns minutos por decisão. Decisões pequenas (nome de variável, lib de
  utilitário) **não** viram ADR.
