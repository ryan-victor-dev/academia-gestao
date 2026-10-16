# ADR 0012 — Endurecimento de segurança (revisão de 2026-10-16)

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

Antes da etapa 4, foi feita uma revisão de segurança da autenticação, do
cadastro de alunos, da configuração do Docker e das dependências, guiada pelo
OWASP API Security Top 10. Cada achado foi comprovado na prática (ataque
executado contra o sistema no Docker) antes de ser corrigido, e de novo depois.

## Achados e decisões

| # | Gravidade | Achado (comprovado) | Correção |
|---|---|---|---|
| 1 | Crítica | Postgres aceitava conexão pela rede, com senha `academia` fixa no repositório, como superusuário | Porta só em `127.0.0.1`; senha no `.env` (`POSTGRES_PASSWORD`), sem ela o Compose não sobe |
| 2 | Alta | Login sem limite: 174 tentativas/s, 10 mil senhas comuns em ~1 min, CPU a 1000% | 20 tentativas/min por IP e 10 falhas/15 min por conta; 429 com `Retry-After`; checagem antes do BCrypt |
| 3 | Alta | Tomcat 11.0.24 (3 CVEs críticas) e Jackson 3.1.5 (DoS sem login) | Fixados Tomcat 11.0.27 e Jackson 3.1.7 no `pom.xml` |
| 4 | Alta | HTTP puro, aplicação ouvindo em todas as interfaces | Caddy com HTTPS automático como única entrada; aplicação só em `127.0.0.1` |
| 5 | Média | Login aceitava corpo de 15 MB; 40 deles levaram a memória de 0,6 a 3,4 GB | Limite de 64 KB (inclusive em partes, sem `Content-Length`), 1 MB no Caddy, teto de memória e `restart` no Compose |
| 6 | Média | Aplicação usava o superusuário do banco | Usuário `academia_app` só com SELECT/INSERT/UPDATE/DELETE; migrations com o dono |
| 7 | Média | Senha do primeiro ADMIN no `.env` valia para sempre | Primeiro ADMIN nasce exigindo troca de senha |
| 8 | Baixa | `WWW-Authenticate` revelava o motivo interno da recusa do token | Só o código padrão (`invalid_token`) |
| 9 | Baixa | Nada avisava sobre CVEs novas; o grafo do GitHub nem via Tomcat/Jackson | Dependabot (alertas e PRs semanais) e envio da árvore resolvida pelo Maven no CI |
| 10 | Baixa | Logins falhos não eram registrados | Log de login aceito, recusado e bloqueado, com conta e IP, nunca a senha |

Também verificados e **sem problema**: token com `alg=none` e com perfil
adulterado são recusados; aluno não acessa dados de outro (sem IDOR); busca sem
injeção de SQL; Actuator expõe só o health; CORS fechado; cabeçalhos de
segurança presentes; tempo do login não revela e-mails cadastrados; segredos
nunca entraram no git; chave JWT de teste não vai para o jar; container sem root.

### Detalhes que importam
- **IP real atrás do proxy:** com o Caddy na frente, todas as requisições
  chegariam com o IP dele e o limite por IP viraria global. A aplicação lê
  `X-Forwarded-For`/`X-Forwarded-Proto` (`forward-headers-strategy: native`),
  mas **só de proxies internos**: quem vem de fora não escolhe o próprio IP.
- **Limite pelo e-mail digitado**, exista ele ou não, e com a senha certa
  também recusada durante o bloqueio: o bloqueio não revela e-mails nem senhas.
- **Usuário da aplicação no banco** é garantido a cada subida por um callback
  do Flyway em Java (`UsuarioDaAplicacaoNoBanco`): senha escapada, nome
  validado, sem senha em arquivo SQL.

## Riscos que ficam (conscientes)

- **Bloqueio de conta de propósito:** errar a senha de alguém 10 vezes trava o
  login dessa pessoa por 15 minutos. Contra uma força bruta que pode acertar a
  senha, é o mal menor.
- **Contadores em memória:** reiniciar a aplicação zera os bloqueios; com mais
  de uma instância, os contadores precisariam ir para o banco ou um Redis.
- **Senha do dono do banco no container da aplicação:** necessária para as
  migrations. Protege contra injeção de SQL (que usa o usuário restrito), mas
  não contra quem já executa código dentro do container.
- **Sem domínio, o certificado é de desenvolvimento** (o navegador avisa). A
  emissão real pelo Let's Encrypt depende de um domínio e não pôde ser testada
  aqui; a configuração foi validada com `caddy validate`.
- **HSTS com `includeSubDomains`:** ao usar um domínio de verdade, todos os
  subdomínios dele passam a exigir HTTPS.

## Ao instalar ou atualizar um servidor

1. Preencher no `.env` `POSTGRES_PASSWORD`, `DB_APP_SENHA` e, com domínio,
   `DOMINIO` (portas 80 e 443 abertas).
2. Banco criado antes desta mudança: trocar a senha do dono uma vez (README).
3. ADMIN criado antes desta mudança: trocar a senha pelo
   `POST /api/auth/trocar-senha`, para a do `.env` deixar de valer.
