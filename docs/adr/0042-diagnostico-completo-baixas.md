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

### B5 — A premissa de uma instância só, escrita
O ADR 0021 registrava que o limite por usuário fica na memória e vale por instância, mas não dizia
o mesmo das tarefas agendadas (`@Scheduled`). São elas, hoje:

| Tarefa | Quando | Com duas instâncias |
|---|---|---|
| `LimiteDeRequisicoes.limparCheios` | a cada minuto | cada uma limpa a sua memória (sem problema) |
| `LimitadorDeTentativas.limparAntigos` | a cada minuto | o mesmo DELETE duas vezes (inofensivo) |
| `Sessoes.limparAntigas` | a cada hora | o mesmo DELETE duas vezes (inofensivo) |
| `RetencaoDaAuditoria.apagarVencidos` | 4h30 | a mesma retenção duas vezes (inofensivo) |
| `ConsultaPeriodicaDosPagamentos` (em aberto, encerrados, contestações) | 2 min, 1 h, 24 h | o dobro de chamadas à empresa; a confirmação trava a linha (`FOR UPDATE`), então não paga duas vezes, mas gasta a cota da empresa |

Decisão: **o sistema roda com uma instância só**, e isso agora está escrito no README (Arquitetura).
Nada muda no código: com uma instância (o Compose sobe uma), não há problema; o que muda é que
quem pensar em escalar sabe por onde começar: os limites em memória vão para o banco, e as tarefas ganham uma trava no banco (por exemplo, `pg_try_advisory_lock`,
ou o ShedLock), para só uma rodar por vez.

### B7 — Limite de tokens inválidos por IP (decisão do dono)
Um IP mandava 1.480 tokens inválidos por segundo, todos 401, sem limite: sem banco (a assinatura é
conferida antes), mas com a CPU à vontade. O dono decidiu por um limite por IP na própria aplicação.

- Cada 401 de quem **mandou um token** gasta uma ficha do IP (`LimiteDeTokensInvalidos`, 60 por
  minuto, `academia.tokens-invalidos.por-minuto`). O 401 de quem não mandou token nenhum não conta.
- O IP sem fichas recebe 429 (com `Retry-After`) em toda requisição com token, antes de qualquer
  conferência (`FiltroDeTokensInvalidos`, o primeiro filtro de segurança): é a conferência da
  assinatura que custa. O 429 não gasta ficha, e as fichas voltam aos poucos (uma a cada segundo).
  Requisição sem token passa (o login tem o limite dele).
- O mesmo balde de fichas do limite por usuário, em memória (uma instância só, B5).
- 60 por minuto fica muito acima do uso real (um token vencido de vez em quando, que a tela renova
  sozinha) e muito abaixo de um laço. Numa academia em que todos saem pelo mesmo IP (a rede da
  recepção), só um laço de verdade esgota o balde, e aí todos daquele IP esperam até a ficha voltar.
- Não resolve a enxurrada vinda de muitos IPs (pendências, item 3), que continua com o dono.

### B8 — Trivy atualizado (0.65 → 0.76) e as imagens reconferidas
O Trivy local era o 0.65 (a base de vulnerabilidades se atualiza sozinha, mas a ferramenta também
precisa). Atualizado para o 0.76 (`aquasec/trivy:0.76.0`; nada no repositório fixa a versão) e
reconferidas, em 28/10, as imagens reconstruídas com as bases baixadas de novo (altas e críticas):

| Imagem | Achado | Decisão |
|---|---|---|
| Aplicação (`eclipse-temurin:25-jre` + jar) | 0 | — |
| Caddy, binário 2.11.7 (Go 1.26.8) | As 4 da biblioteca padrão do Go já conhecidas (ADR 0014) e 3 que o Trivy novo mostra: `x/net` (CVE-2026-78669, a mesma falha de HTTP/2) e `go-jose` v3/v4 (CVE-2026-116587, negação de serviço ao ler chaves JWK com RSA) | Risco aceito: o HTTP/2 está desligado, e o `go-jose` só é usado pelo cliente ACME, que conversa com o Let's Encrypt; nenhuma chave JWK de fora é lida. Ainda não há Caddy 2.11.8 nem 2.12 |
| Backup (Postgres `17-alpine` + `age`) e Postgres | `gosu` com Go 1.24.6: 26 (1 crítica), como no ADR 0014. O `age` não trouxe nenhuma | Risco aceito, como no ADR 0014: no Postgres, o `gosu` roda um instante na subida, sem rede; no backup, nem roda (a rotina sobe direto como o usuário) |

Reconferir quando o Dependabot abrir a atualização do Caddy ou do Postgres.

### B6 — O `PagamentoOnlineService` dividido por assunto (decisão do dono: agora)
Com 544 linhas, o `PagamentoOnlineService` gerava, recebia avisos, consultava, devolvia, contestava
e simulava: o maior arquivo do projeto, e onde a empresa de pagamentos de verdade vai entrar. O dono
preferiu dividir agora, sem mudar comportamento:

| Classe | O quê |
|---|---|
| `EmpresaDePagamento` | a empresa ligada (ou nenhuma) e o que se pergunta a ela: as formas, se está disponível, o nome, se é o simulado, cancelar lá |
| `PagamentoOnlineService` | o que o aluno vê e gera: as opções e o Pix, o boleto ou a página do cartão |
| `ConfirmacaoDePagamentoOnlineService` | o que a empresa diz: o aviso, as consultas periódicas, o recebimento, a expiração, a contestação e o simulado, todos por `confirmar` |
| `DevolucaoDePagamentoOnlineService` | o dinheiro a devolver: a lista, a devolução pela empresa e a marcação "por fora" |

Os métodos foram copiados sem mudar (conferido linha a linha contra o original; mudaram só as
chamadas à empresa, que passam pela `EmpresaDePagamento`). Os 41 testes do controller de pagamentos
e o roteiro do pagamento online no Docker passam iguais.
