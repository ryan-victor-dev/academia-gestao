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

### B3 — HSTS também nas telas
O Spring põe o `Strict-Transport-Security` nas respostas da API, mas as telas (arquivos servidos
pelo próprio Caddy) não o tinham: quem abrisse o endereço pela primeira vez em HTTP dependia do
redirecionamento. Agora o Caddy põe o mesmo cabeçalho das telas (`max-age` de um ano, com os
subdomínios), e a API continua com o dela, sem duplicar. Em `localhost` não muda nada na prática:
a API já o mandava. O e2e confere o cabeçalho na página.

### B4 — A lista da equipe sem os logins de alunos; os de alunos, paginados
`GET /api/usuarios` devolvia todos os logins, inclusive os de aluno, sem paginação: com milhares de
alunos, a tela da equipe carregaria todos para mostrar meia dúzia (os de aluno ficavam escondidos
por um filtro na tela). Agora:
- `GET /api/usuarios` traz só a equipe (todos os perfis menos ALUNO), pelo nome, sem paginar: são
  poucos.
- `GET /api/usuarios/alunos` traz os logins de aluno paginados (20 por padrão, até 100), pelo nome,
  com `busca` por qualquer parte do nome ou do e-mail, sem diferenciar maiúsculas (`%` e `_` são
  texto, e não curingas).
- Na tela, "Mostrar também os logins de alunos" abre uma seção à parte, com busca e páginas; as
  ações (desativar, senha nova) são as mesmas e recarregam as duas listas.
