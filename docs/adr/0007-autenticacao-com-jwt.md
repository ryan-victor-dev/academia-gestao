# ADR 0007 — Autenticação stateless com JWT (HS256) e perfis

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O sistema tem quatro perfis de acesso (ADMIN, RECEPCAO, INSTRUTOR, ALUNO) e
será consumido por um front-end separado (web e, no futuro, celular). Preciso
saber quem está chamando a API e o que cada perfil pode fazer, sem depender de
um servidor de identidade externo (Keycloak, Auth0) para uma única academia.

## Decisão

### Senhas
- Guardadas só como hash **BCrypt** (custo 10). O BCrypt tem salt por hash e
  é lento de propósito, o que encarece força bruta se o banco vazar.
- O BCrypt ignora o que passa de 72 bytes; senhas acima disso são recusadas.

### Primeiro ADMIN
- Na inicialização, se o banco não tem nenhum ADMIN, um é criado a partir de
  `ADMIN_NOME`/`ADMIN_EMAIL`/`ADMIN_SENHA` (arquivo `.env`, fora do Git).
- Senha com menos de 12 caracteres impede a aplicação de subir.
- Depois que existe um ADMIN, as variáveis são ignoradas: trocar a senha no
  `.env` não altera a do banco.

### Login e token
- `POST /api/auth/login` (e-mail + senha) devolve um **JWT** assinado.
- Gerado e validado com o `JwtEncoder`/`JwtDecoder` do Spring Security
  (Nimbus, via `spring-boot-starter-security-oauth2-resource-server`), sem
  biblioteca de JWT de terceiros.
- **HS256** (HMAC, chave simétrica): quem assina e quem valida é a mesma
  aplicação, então não há necessidade de par de chaves pública/privada. A
  chave vem de `JWT_SEGREDO` (Base64, mínimo de 256 bits); sem ela a aplicação
  não sobe.
- Claims: `sub` = id do usuário (não muda e não é dado pessoal), `perfil`,
  `iss`, `iat` e `exp`.
- **Expiração curta** (15 min, ajustável por `JWT_EXPIRACAO`).
- E-mail inexistente, senha errada e usuário inativo recebem **a mesma
  resposta 401**. A senha é conferida até quando o e-mail não existe (contra
  um hash falso), para o tempo de resposta não revelar quais e-mails estão
  cadastrados.

### Validação em cada requisição
- `SecurityFilterChain` **stateless** com `oauth2ResourceServer`: toda
  requisição traz `Authorization: Bearer <token>`, e o filtro confere
  assinatura, expiração e emissor. Não há sessão no servidor.
- Liberados sem token: `POST /api/auth/login` e `GET /actuator/health`. Todo
  o resto exige autenticação.
- **CSRF desligado** porque a API não usa cookie: o ataque depende de o
  navegador anexar credenciais sozinho, e ele não anexa o cabeçalho
  `Authorization`. Se um dia o token for para um cookie, o CSRF precisa voltar.
- A claim `perfil` vira a autoridade `ROLE_<PERFIL>`.

### Autorização e erros
- Regras por perfil com `@PreAuthorize("hasRole('...')")` no próprio método do
  controller.
- 401 e 403 saem em **ProblemDetail** (RFC 9457, `application/problem+json`),
  igual aos outros erros da API. O 401 mantém o `WWW-Authenticate: Bearer`.
  O motivo exato de um token ser recusado não vai no corpo.

## Alternativas consideradas

- **Sessão com cookie (JSESSIONID):** exige estado no servidor e proteção
  CSRF, e funciona mal para um app de celular.
- **RS256 (par de chaves):** útil quando outro serviço precisa validar tokens
  sem poder emiti-los. Aqui só existe um serviço; se isso mudar, troca-se a
  chave sem mudar o resto.
- **Biblioteca externa (jjwt, java-jwt):** o Spring Security já traz o Nimbus
  integrado ao filtro de validação, e uma dependência a menos é menos
  superfície de ataque.
- **Servidor de identidade (Keycloak):** mais um serviço para operar e
  atualizar, desproporcional para uma academia.

## Consequências

- Escala sem sessão compartilhada: qualquer instância valida qualquer token.
- **Um token não pode ser revogado antes de expirar.** Um usuário desativado
  continua com o token válido por até 15 minutos; o `/api/me` confere o banco
  e já o recusa, mas as outras rotas só olham o token. Se isso virar
  problema, o caminho é um refresh token com lista de revogação.
- Mudar o perfil de alguém só vale no próximo login.
- Trocar o `JWT_SEGREDO` invalida todos os tokens: todo mundo loga de novo.
  É também o "botão de emergência" se a chave vazar.
- Ainda não há refresh token: quando o token expira, o usuário faz login de novo.
