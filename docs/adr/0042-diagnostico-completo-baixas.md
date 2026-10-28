# ADR 0042 — Diagnóstico completo (27/10): as baixas

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

Depois dos médios (ADR 0041), o dono pediu (28/10) que as baixas do diagnóstico completo de 27/10
(B1 a B8) sejam resolvidas, uma por commit. Decidiu as duas que eram dele: um limite por IP para os
tokens inválidos, na aplicação (B7), e dividir o `PagamentoOnlineService` agora (B6).

## Decisões

### B1 — 403 antes do corpo também para a regra na classe
O `PerfilAntesDoCorpo` (que responde 403 antes de validar o corpo) só olhava o `@PreAuthorize` do
método. `UsuarioController` e `ExercicioController` o põem na classe: quem não tinha o perfil
recebia 400 com as regras dos campos (nada passava: com corpo válido, 403). Agora vale a regra do
método ou, sem ela, a da classe. O `PerfilAntesDoCorpoTest` tinha o mesmo ponto cego (só lia a do
método) e por isso não pegou: passa a ler as duas e acha as quatro rotas que escapavam.
