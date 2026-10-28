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

### B2 — O aluno inativo não corrige o e-mail
As rotas de `/api/auth/email/**` tinham regra própria (quem ainda não confirmou o e-mail precisa
delas) e não aplicavam o "só leitura" do ADR 0029: o aluno inativo que ainda não confirmara o
e-mail podia corrigi-lo, e a correção muda o cadastro. Havia um segundo furo por trás: o token de
quem precisa confirmar o e-mail ganhava só a autoridade `EMAIL_NAO_CONFIRMADO`, e a de só leitura
se perdia.

Agora o token leva as duas, e `PUT /api/auth/email` recusa o só de leitura (403). Pedir e
confirmar o código continuam liberados: confirmar não muda o cadastro e é o caminho para o
inativo chegar aos próprios dados (direito dele, ADR 0029). Na tela, o inativo não vê "O e-mail
está errado?", e sim um aviso para falar com a recepção, que corrige o cadastro.
