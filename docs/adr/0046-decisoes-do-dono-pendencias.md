# ADR 0046 — Decisões do dono sobre as últimas pendências

- **Status:** Aceito
- **Data:** 2026-10-29

## Contexto

Da lista de pendências de 23/10, sobraram seis itens que pediam uma decisão do dono, e não uma
correção técnica: 3, 7, 8, 9, 10 e 15. Em 29/10 ele decidiu os seis. Cada item tem uma seção abaixo,
com o que foi decidido, o que ficou de fora e o que mudou no sistema. Cada um entrou num commit.

### 9 — O motivo do trancamento fica fora da trilha
Antes: o retrato da matrícula na trilha (ADR 0031) levava o último trancamento com o motivo. O motivo
é texto livre e muitas vezes tem dado de saúde ("cirurgia no joelho", "gravidez"), um dado sensível
pela LGPD, e a trilha fica 5 anos (ADR 0022).

**Decisão do dono:** a trilha não grava o motivo do trancamento. Ela continua registrando quem trancou,
quando, o início, a volta prevista e a volta. O motivo continua no próprio trancamento, onde a recepção
e o ADMIN o veem na matrícula, e sai junto com ela.

Ficaram de fora: manter o motivo na trilha (5 anos de um dado de saúde, só para justificar uma alteração
que o próprio trancamento já justifica) e não guardar o motivo em lugar nenhum (perde a informação de
por que a pessoa trancou).

O motivo do cancelamento continua na trilha: é a justificativa de uma alteração que mexe em dinheiro
(multa), e a decisão foi só sobre o trancamento. As linhas antigas da trilha não foram reescritas: o
sistema ainda não foi ao ar, e só existem dados de desenvolvimento.

O `TrilhaDasMatriculasTest` confere que a linha `TRANCADA` não tem o motivo.
