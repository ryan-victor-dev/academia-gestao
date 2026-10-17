# ADR 0014 — Segunda revisão de segurança (2026-10-17)

- **Status:** Aceito (complementa o ADR 0012)
- **Data:** 2026-10-17

## Contexto

Depois da etapa 4 (planos), foi feita uma nova rodada de revisão, com foco no
que entrou depois do ADR 0012 e em pontos ainda não verificados: as imagens
Docker (sistema operacional, e não só as bibliotecas Java), os logs, os
privilégios dos containers e entradas malformadas. Como antes, cada achado foi
comprovado na prática antes de corrigido, e de novo depois.

## Achados e decisões

| # | Gravidade | Achado (comprovado) | Correção |
|---|---|---|---|
| 1 | Alta | `caddy:2.10-alpine` abandonada: o Trivy achou 87 vulnerabilidades altas ou críticas (uma crítica no OpenSSL); o Dependabot não vigiava o `docker-compose.yml` | `caddy:2.11-alpine`; só HTTP/1.1 enquanto a CVE-2026-78669 (negação de serviço por HTTP/2) não tiver Caddy corrigido; Dependabot no Compose |
| 2 | Média | Troca de senha sem limite: com um token roubado, 40 chutes da senha atual sem bloqueio | Mesmo limitador do login, na mesma conta (10 erros bloqueiam a troca e o login) |
| 3 | Média | Logs do Docker sem rotação (crescem a cada tentativa de login, com e-mail e IP) | 5 arquivos de 10 MB por container |
| 4 | Média | Caddy (exposto à internet) como root; nenhum container com `no-new-privileges` | Caddy como uid 10001 só com `NET_BIND_SERVICE`; `no-new-privileges` em todos; aplicação sem capacidades e só leitura; Postgres só com o que a inicialização usa |
| 5 | Baixa | Tipos convertidos sem aviso: `duracaoMeses: 1.5` virava 1 e `"12"` virava 12 | Jackson estrito (400 para tipo errado e lixo depois do JSON) |
| 6 | Baixa | Sem `Content-Security-Policy`, `Referrer-Policy` e `Permissions-Policy` | Política mais restritiva possível para uma API só de JSON |
| 7 | Aviso | HTML é aceito e devolvido como texto (ex.: `<img onerror=...>` no nome) | Ver "Front-end" abaixo |
| 8 | Baixa | `pebble` (Go com 3 CVEs altas) na imagem base da aplicação, nunca usado | Removido da imagem (Trivy: de 3 para 0) |

Verificado e **sem problema**: números gigantes (`1e999999999`) e JSON com 20
mil níveis recusados rapidamente; campos extras (id, status, hash de senha)
ignorados; quebra de linha no e-mail recusada antes de chegar ao log; `Host`
forjado não chega à aplicação; `TRACE` bloqueado; 0 vulnerabilidades conhecidas
nas 105 dependências Java.

### Detalhes que importam
- **Caddy sem root:** o binário vem marcado com a capacidade
  `cap_net_bind_service` (setcap). Com `cap_drop: ALL`, o kernel nem executa o
  arquivo; por isso ela fica (e é a única). Testado abrindo a porta 80 de
  verdade: só a capacidade funciona; o `sysctl` de portas sem privilégio, não.
  Um container de uso único entrega ao usuário do Caddy os volumes de
  certificados, que a imagem cria como root; ele precisa de `DAC_READ_SEARCH`
  para entrar nas pastas a partir da segunda subida.
- **Troca de senha e login contam juntos:** um ataque pela troca de senha
  também bloqueia o login da conta, que está claramente sob ataque.

### Front-end (item 7)
A API guarda e devolve texto exatamente como recebido, em JSON com
`X-Content-Type-Options: nosniff`: não há XSS nela. Mas qualquer front-end que
exibir esses textos **precisa escapá-los** (React, Vue e Angular fazem isso por
padrão; nunca usar `innerHTML`/`dangerouslySetInnerHTML`/`v-html` com dados da
API). Restringir caracteres nos nomes foi descartado: atrapalharia nomes reais
(D'Ávila, hífens, acentos) e não substitui o escape.

## Riscos aceitos

- **4 CVEs no binário do Caddy 2.11.7** (Go 1.26.8): só a de HTTP/2 se aplica a
  um proxy reverso, e está mitigada; as de `Range` (servidor de arquivos) e ECH
  não se aplicam. O Dependabot avisa quando sair imagem nova.
- **`gosu` na imagem oficial do Postgres** (25 CVEs de Go): roda um instante na
  inicialização para trocar de root para `postgres` e não abre rede.
- **Ações do GitHub fixadas por versão, não por hash:** o Dependabot as atualiza;
  fixar por hash fica para quando houver mais gente no repositório.
