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
