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

### 17 — Um teste confere que o banco usa o fuso do Java
O fuso da academia está no Java (`Calendario.FUSO`) e no gatilho que só aceita lançamento no caixa
do dia (V35). Se um mudasse sem o outro, o caixa recusaria pagamentos perto da meia-noite.

Em vez de tirar o fuso do gatilho (passá-lo pela aplicação abriria um jeito de contornar a trava),
o `FusoNoBancoTest` lê do banco a definição **atual** de todas as funções e visões e exige que todo
`AT TIME ZONE` use o `Calendario.FUSO`, e que haja pelo menos um (para não passar no vazio). As
migrações V25 e V26, que também citam o fuso, ficam de fora: são correções de dados que já rodaram.

### 10 — Pagamento registrado sem receber: a conferência do ADMIN, escrita
A recepção registra o pagamento que diz ter recebido; o sistema não sabe se o PIX ou o cartão do
balcão entrou na conta. No dinheiro, o fechamento às cegas pega a falta (ADR 0031); no PIX e no
cartão, só a conferência do ADMIN com o extrato do banco e o da maquininha. O relatório do caixa
já separa cada dia por forma, que é o que essa conferência precisa: faltava dizer como fazê-la.

Agora o README tem a rotina (semanal e antes de fechar o mês): o relatório por forma contra o
extrato e a maquininha, e os lançamentos e a trilha para achar quem registrou.

Fica para o dono decidir (muda o trabalho da recepção): exigir o código da transação (o "ID" do
PIX ou o número do comprovante do cartão) ao registrar, para a conferência ser linha a linha, e
não por total do dia.

### 21 — Busca por nome com o nome normalizado gravado na linha
Antes: a página da busca é lida pelo índice de nome e id, na ordem, e o filtro calculava
`nome_normalizado` (o `unaccent`) em cada linha descartada até achar 20. Com um nome comum, eram
dezenas de milhares de cálculos por busca.

Agora (V52) o nome normalizado é uma **coluna gerada** (`nome_busca`): o banco a grava em cada
INSERT e UPDATE do nome, e ninguém a escreve à mão. O filtro compara a coluna, e o índice de
trigramas passou para ela (a contagem da página o usa).

Medido num Postgres descartável, com 100 mil alunos e 20 % deles "Maria": a página de "maria"
caiu de 131 ms para 20 ms, e a contagem fica em 7 ms. O `IndicesDaBuscaDeAlunosTest` confere que a
consulta da aplicação usa o índice novo.

### 23 — Montar ficha encerra a anterior sem carregá-la
Antes: para encerrar a ficha atual, o `montar` a carregava inteira (as divisões e os exercícios são
EAGER, até 7 × 20 linhas) só para mudar o momento do encerramento e a versão (diagnóstico da
etapa 7, B6).

Agora o repositório busca só o id da atual e a encerra com um `UPDATE` (`encerrada_em` e a versão
+ 1, só se ela ainda for a atual). O aluno continua travado antes, como na correção; se o `UPDATE`
não acha a ficha, é erro de programação (500), e não uma corrida. O momento passa pela mesma
função da entidade (`Ficha.momentoDoEncerramento`, na precisão do banco), e a trilha fica igual à
de antes: ela já gravava só o que mudou, e só o `encerradaEm` mudava.

O resultado não muda, então só a contagem do Hibernate mostra a diferença: o
`MontarNaoCarregaAAnteriorTest` exige zero fichas, divisões e exercícios carregados, e confere o
encerramento, a versão e a trilha. No Docker, três montagens seguidas para o mesmo aluno: cada
anterior encerrada no momento da seguinte, com a versão nova, as divisões intactas, a correção
recusada (409) e a trilha com só o `encerradaEm`.

### 24 — O controller não usa o banco nem abre transação
O combinado (Controller → Service → Repository) era só convenção: cinco controllers faziam a parte
do service. A trilha e o registro de acessos montavam o SQL no controller; a lista de usuários e o
`/api/me` usavam o repositório; e a configuração do pagamento online travava a linha, conferia o
If-Match, gravava e escrevia na trilha, tudo no controller, com o `@Transactional` nele. Esse
último a lista não citava; a regra nova o achou.

Agora o SQL e a transação estão em services (`AuditoriaService`, `AcessosService`, `MeService`,
`EquipeService.listar` e `ConfiguracaoDoPagamentoOnlineService`, no molde do da cobrança), e o
controller só confere o formato e chama. A ordem das conferências não mudou: a configuração nova
ainda só é validada depois do If-Match (versão errada continua 412, e não 400).

Três regras novas no `ArquiteturaTest` impedem a volta:
- controller não depende de `org.springframework.jdbc`, de repositório (do Spring Data ou só pelo
  nome, como os de `JdbcTemplate`) nem do `EntityManager`;
- controller não tem `@Transactional`, nem na classe nem nos métodos;
- service não depende de controller, nem dos records de dentro dele (senão, o jeito de "cumprir"
  a primeira regra seria o service devolver a resposta da API).

Cada regra pegou o seu mutante (8 de 8: cada tipo de acesso ao banco, a transação na classe e no
método, e o service usando o controller e o record dele). No Docker, o mesmo roteiro rodou nas
duas versões, com os perfis permitidos e os recusados, os erros do If-Match e a trilha: as
respostas saíram idênticas.

### 25 — O registro de login: só os tipos certos, e depois de quem entrou ter entrado
Antes: o `RegistroDeAcessos.login` recebia o `Tipo` inteiro e recusava, em execução, a consulta
ao cadastro e o bloqueio (que têm método próprio). E o `LOGIN` era gravado antes de gerar o token
e de abrir a sessão: se um deles falhasse, ficava um `LOGIN` de quem não entrou (diagnóstico da
etapa 8, B7).

Agora o método recebe um `TipoDeLogin` (`LOGIN`, `LOGIN_FALHO`, `TROCA_SENHA_FALHA`), e passar
outro não compila. O login e a troca de senha devolvem o token e a sessão juntos (a sessão saiu
do controller e foi para o `AutenticacaoService`), e o `LOGIN` só é gravado depois dos dois. Se o
registro falhar depois disso, sobra uma sessão sem dono: o cookie dela nunca saiu do servidor, e
ela vence sozinha.

Testes: a sessão que não abre não deixa `LOGIN`, e o `LOGIN` vem depois da sessão; os testes do
registro já conferiam o tipo gravado em cada caso. 4 mutantes, 4 mortos (a ordem de antes e cada
tipo trocado). No Docker: login, senha errada, troca recusada e aceita, renovação pelo cookie e o
cookie antigo recusado depois da troca, com o registro na ordem certa.
