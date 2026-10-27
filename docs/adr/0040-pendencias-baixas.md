# ADR 0040 — Pendências baixas sem decisão do dono

- **Status:** Aceito
- **Data:** 2026-10-26

## Contexto

A lista consolidada das pendências (revisões de 17/10 e 18/10 e diagnósticos das etapas 6 a 8) só
tem itens baixos. O dono pediu (2026-10-26) que os que não dependem de decisão dele sejam
resolvidos, um por commit. As decisões (aparência, o que a recepção vê, troco, motivo do
trancamento, limite da ficha, enxurrada de logins) e o ir ao ar ficam para depois. Cada seção
abaixo é um item, com o número da lista.

## Decisões

### 2 — Só o Caddy diz qual é o IP do cliente
Antes: com `forward-headers-strategy: native`, o Tomcat aceitava `X-Forwarded-For` de qualquer IP
interno (10/8, 172.16/12, 192.168/16, 127/8). De fora, pelo Caddy, não dava para forjar; mas de
outro container da rede (o backup, o Mailpit) ou desta máquina (porta 8090), bastava trocar o
cabeçalho a cada tentativa para furar o limite de login por IP (ADR 0012) e falsificar o IP no
registro de acessos (ADR 0031).

Agora:
- O Caddy e a aplicação ficam numa rede só deles (`proxy`, padrão `10.250.250.0/24`), com o Caddy
  num **IP fixo** (`10.250.250.10`). O Caddy sai da rede do banco.
- A aplicação confia **só nesse IP** (`academia.proxy-confiavel`, que o Compose preenche): de
  qualquer outro, o cabeçalho é ignorado e vale o IP da conexão. Valor que não é um IPv4 impede a
  subida, em vez de deixar o limite aberto (ou o IP de todos igual ao do Caddy) sem ninguém ver.
- Sem a propriedade (pelo Maven, nos testes), fica o padrão do Tomcat.
- A rede fica fora das faixas que o Docker usa sozinho (172.17–172.31, 192.168); se colidir com
  outra do servidor, `PROXY_REDE` e `PROXY_IP_DO_CADDY` no `.env` mudam as duas coisas. O e2e usa
  outra rede (`10.250.251.0/24`) para rodar junto do ambiente de desenvolvimento.

Rejeitado: um segredo compartilhado num cabeçalho do Caddy (mais código e mais um segredo para
trocar, para o mesmo efeito); confiar na rede inteira do proxy (o gateway dela é a própria máquina).

### 4 — Sem ficha, o 429 sai antes da consulta ao banco
Antes: o limite por usuário (300/min) vinha depois da validação completa do token, que consulta o
usuário no banco (token revogado?). Um token roubado em laço gerava uma consulta por requisição,
mesmo recebendo 429.

Agora o limite é olhado duas vezes:
- **Antes** da validação completa, um filtro confere o token só pela assinatura, pela expiração e
  pelo emissor (sem banco; o "sub" já é de confiança) e recusa quem está sem ficha, **sem gastar**.
- **Depois**, como antes, a ficha é gasta.

Rejeitado: gastar a ficha antes da consulta. Um token revogado (que ainda tem a assinatura certa,
até vencer) esgotaria o balde do dono da conta, e a troca de senha não pararia mais o estrago.
Com o desenho escolhido, o token revogado recebe 401 sem gastar nada.

Fica: um token revogado em laço ainda custa uma consulta por requisição (por chave primária), como
sempre custou, até vencer (15 min). O limite de requisições não o pega, porque ele não gasta fichas.

### 6 — Vulnerabilidades das imagens, reconferidas
Trivy 0.65 (altas e críticas) em 26/10, com as imagens base baixadas de novo:

| Imagem | Achado | Decisão |
|---|---|---|
| Aplicação (`eclipse-temurin:25-jre` + jar) | 0 | — |
| Caddy, pacotes do Alpine | 3 novas, com correção: `curl`/`libcurl` (CVE-2026-92392) e `brotli-libs` (CVE-2026-111497) | **Removidos** da imagem (`apk del curl`): o Caddy não usa nenhum dos dois, e sem eles a imagem perde também o que um invasor usaria para baixar arquivos. Ficam 0 |
| Caddy, binário 2.11.7 (Go 1.26.8) | As mesmas 4 do ADR 0014; ainda não há Caddy 2.11.8 nem 2.12 | Risco aceito, como no ADR 0014 (HTTP/2 desligado; as outras não se aplicam a um proxy reverso) |
| Postgres (`17-alpine`), `gosu` | As mesmas 25 (1 crítica) do ADR 0014; a imagem de 21/10 ainda traz o `gosu` com Go 1.24.6 | Risco aceito, como no ADR 0014 (roda um instante na subida, sem rede) |

Reconferir quando o Dependabot abrir a atualização do Caddy ou do Postgres.

### 14 — Inativar ou reativar de novo não vai para a trilha
Antes: inativar um exercício já inativo (ou reativar um já ativo) gravava "INATIVADO" (ou
"REATIVADO") na trilha, sem nada ter mudado. O `PlanoService` tinha o mesmo código.

Agora, nos exercícios e nos planos, a repetição devolve o registro como está (200, mesma versão)
e não grava nada.

**Sem `If-Match`, de propósito**, como nos planos e nos alunos: inativar e reativar são
idempotentes e não regravam nenhum campo que outra pessoa possa ter editado, então não perdem a
alteração de ninguém. Exigir o cabeçalho só nos exercícios tornaria a API incoerente e não
protegeria nada.

### 18 — O que a matrícula faz nas cobranças vai para a trilha
Antes: o acerto das cobranças com o cronograma da matrícula (ADR 0024) reprogramava (trancamento,
volta), cancelava as em aberto (cancelamento, troca) e tirava do cronograma a paga adiantada, sem
nada na trilha das cobranças: só ficava a da matrícula que causou a mudança.

Agora cada cobrança que o acerto altera ganha uma linha na trilha (`COBRANCA`), na mesma
transação e com o mesmo autor da operação na matrícula (sem token, como na geração da subida, o
próprio sistema):
- `REPROGRAMADA`: o vencimento, antes e depois;
- `CANCELADA`: o status e o motivo ("Matrícula encerrada..."); o motivo distingue do
  cancelamento feito pelo ADMIN, que usa a mesma ação;
- `FORA_DO_CRONOGRAMA`: a paga que o estorno passa a cancelar (ADR 0026). O campo
  `foraDoCronograma` entrou no retrato da cobrança, também nas mudanças feitas à mão.

As criadas não entram: saem do cronograma da matrícula, que já está na trilha dela (uma
matrícula de 12 meses seriam 12 linhas a mais, sem informação nova).
