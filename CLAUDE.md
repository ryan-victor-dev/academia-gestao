# CLAUDE.md

Sistema de gestão de academia: Java 25, Spring Boot 4.1, PostgreSQL 17, Flyway,
Testcontainers, Docker Compose (Caddy → app → Postgres). Tudo em português: código,
comentários, mensagens de commit, ADRs e respostas ao usuário.

## Comandos

```bash
./mvnw verify                      # todos os testes (precisa do Docker ligado)
./mvnw -P mutacao test-compile org.pitest:pitest-maven:mutationCoverage   # mutação (PIT)
docker compose up --build -d       # ambiente local; entrada em https://localhost:<HTTPS_PORTA>
scripts/e2e.sh                     # ponta a ponta numa instalação do zero, isolada (~4 min)
scripts/usuario-validacao.sh PERFIL   # token de um usuário de teste (só desenvolvimento)
```

## Arquitetura

Monolito modular por domínio (ADR 0002): `auth`, `alunos`, `planos`, `cobranca`,
`matriculas`, `treinos`, `auditoria`, `compartilhado`. Em cada módulo: Controller
(records de entrada/saída, nunca a entidade) → Service (transação, checagens que dependem
do banco) → Entidade rica (fábrica, sem setters, valida tudo antes de alterar) → Repository.
As regras da cobrança ficam em `cobranca.dominio`, Java puro (ADRs 0004 e 0024); o resto
do módulo usa Spring e SQL (`JdbcTemplate`), sem JPA.

As fronteiras são verificadas pelo `ArquiteturaTest` (ArchUnit, ADR 0015): sem ciclos;
`auth` e `compartilhado` não dependem de outros módulos; de fora de um módulo, só a porta
pública dele. Se um módulo novo precisar de outro, declare a porta pública no teste.

Leia os ADRs em `docs/adr/` antes de mudar uma decisão; mudanças de decisão viram ADR novo.

## Como trabalhar (combinado com o usuário)

- **Uma parte por commit**, com mensagem em português explicando o quê e o porquê, e uma
  explicação curta e didática ao usuário no fim de cada parte.
- Antes de cada commit: `./mvnw verify` passando.
- **Prove que cada teste novo pega o erro**: quebre a regra de propósito (mutação), veja o
  teste falhar, restaure. Mutação que sobrevive = teste faltando (ou mutante equivalente,
  que deve ser explicado). Para teste de concorrência, várias rodadas: uma só passa por sorte.
- **Valide no Docker de verdade** o fluxo novo (`docker compose up --build -d` + chamadas
  reais). Para tokens, use `scripts/usuario-validacao.sh` e apague os usuários `validacao-%`
  no fim. **Nunca use nem altere o ADMIN do usuário.**
- **Validação e commit em comandos separados**: só faça o commit depois de ler o resultado
  da validação. Validação que falhou por problema do script não é validação.
- Depois de cada push, confira o CI (`gh run watch`). Não deixe o `main` vermelho.
- Diga com honestidade o que foi e o que não foi verificado, e os erros cometidos.
- Decisão de negócio é do usuário: pergunte antes (perfis, preços, regras).

## Armadilhas já encontradas

- **Checar antes de alterar a entidade**: consulta nativa dispara auto-flush do Hibernate;
  com a entidade já alterada, o índice único estoura dentro da checagem (500 em vez de 409).
- Testes dividem o mesmo banco e rodam em ordem imprevisível: use `CpfsParaTeste.aleatorio()`
  e limpe o que criar. Para testar isso, rode o `verify` com `-Dsurefire.runOrder=random`.
- O limite de login (20/min por IP; 10 falhas/15 min por conta e IP, 100 por conta no total,
  ADR 0022) vale nos testes de ponta a ponta: espere a janela ou use contas diferentes.
- O limitador grava em transação própria (REQUIRES_NEW): a do login é desfeita quando a
  senha está errada.
- `psql -c` não substitui variáveis (`:'senha'`); passe o SQL pela entrada padrão.
- O `/tmp` (e a pasta de rascunho) pode ser apagado: guarde utilitários em `scripts/` ou
  `revisao-local/ferramentas/` (fora do Git); `target/` some num `clean`.
- Caddy sem root: o binário exige a capacidade `NET_BIND_SERVICE` (setcap); sem ela, nem executa.
- Para apagar tudo num teste, use `LimpezaDoBanco.apagarTudo()` (ordem das chaves
  estrangeiras, ADR 0023), nunca `DELETE FROM tabela` na mão; para apagar só o que criou,
  `limpeza.apagar("alunos", id)` (leva matrículas, trancamentos, cobranças e o login).
  Linha criada pela migração e só alterada pela aplicação (a configuração da cobrança)
  fica de fora (`LimpezaDoBanco.FICAM`): quem altera restaura.
- `REQUIRES_NEW` dentro de outra transação usa uma segunda conexão: sob carga, o pool (20)
  esgota (ADR 0020). Hoje a espera é de 3 s e cada consulta tem teto de 5 s, com 503
  (ADR 0021); teste com mais requisições simultâneas do que conexões.
- O VS Code (extensão Java) compila em `target/` por conta própria: um `.class` dele, mais
  novo que o código, faz o Maven pular a compilação e esconder o erro do `javac`
  ("Unresolved compilation problem" no teste). Na dúvida, `touch` no arquivo e recompile.
- Backup só vale com restauração testada (o teste pegou um script que restaurava o arquivo
  errado). `scripts/e2e.sh` cobre os dois.
- `CHECK (a AND x > 0)` deixa passar `x` nulo: `NULL > 0` dá `NULL`, e CHECK com `NULL`
  passa. Escreva `x IS NOT NULL AND x > 0`.
- O Caddy (Go) devolve `Etag`, não `ETag`: em scripts de validação, leia cabeçalhos sem
  diferenciar maiúsculas.
- Teste de corrida: confira que cada lado ganha em parte das rodadas; se um lado ganha
  sempre, a corrida não foi testada. Mutação que falha por erro de compilação não conta.
- A recuperação de ADMIN sobe a aplicação **sem web**: componente que depende de algo da web
  (filtro, `HandlerExceptionResolver`, segurança) leva `@ConditionalOnWebApplication`. O
  `ModoComandoTest` e o e2e pegam; os outros testes sobem sempre com web.
- Token simulado (`jwt()`) pula a validação no banco do token de verdade: o que depende
  dela (erros no filtro, limite por usuário) precisa de um teste com login real.
- Limite de 300 requisições/min por usuário: os testes usam um valor alto (config de
  teste) e a carga precisa de `ACADEMIA_REQUISICOES_POR_MINUTO` alto na instalação isolada.
- Todo "agora" vem do `Clock` injetado (ADR 0023; o `ArquiteturaTest` proíbe `now()`).
  Nos testes, o `RelogioDeTeste` (compartilhado por todos) para, adianta ou fixa: restaure
  no `@AfterEach`. Relógio no passado + tarefas agendadas (limpezas) = dados do teste
  apagados no meio: prefira a hora real ou o futuro. Datas de regra: `Calendario.hoje(...)`,
  no fuso da academia (o container roda em UTC).
- A matrícula gera as cobranças na mesma transação (ADR 0024): teste que mexe em matrícula
  cria cobranças, e o "pode treinar?" depende delas. Para atrasar uma cobrança, mude o
  `vencimento` no banco ou o `RelogioDeTeste`.
- Build incremental sujo: um `.class` auxiliar (`Classe$1`, do `switch` em enum) sumiu de
  `target/classes` depois de muitas mutações, e o `verify` deu `NoClassDefFoundError`. Apague
  `target/classes`, `target/test-classes` e `target/maven-status` e rode de novo. O
  `./mvnw clean` falha aqui (`target/validacao/trivy-cache` é do root) e ainda apaga os
  utilitários de `target/`: guarde-os em `revisao-local/ferramentas/` (fora do Git).
  Em rodada longa de mutações isso volta: apague essas pastas a cada mutante, ou trate
  `NoClassDefFoundError` como mutante inválido (senão ele conta como "morto" sem ser).
- Filtro de log com grep não é resultado de teste: confira o código de saída do Maven
  (um erro de compilação passou batido num `grep "Tests run"`).
- Toda alteração de plano, aluno ou usuário registra na trilha (`Auditoria`, ADR 0021), na
  mesma transação. O usuário da aplicação não apaga a trilha (só a função da retenção de 5
  anos, ADR 0022, apaga o vencido); nos testes, limpe com o `JdbcTemplate` (dono do banco).
- Pagamento e estorno também gravam em `lancamentos` (ADR 0025), que só aceita INSERT: teste
  que insere uma cobrança `PAGA` direto no banco e a estorna precisa inserir o lançamento do
  pagamento junto (senão, 500). Use a API para pagar.
- Usuário de validação que registrou algo não pode ser apagado (chave estrangeira): o
  `scripts/usuario-validacao.sh` só troca a senha; no fim, apague primeiro o que ele criou.
- Nos testes, a aplicação conecta como o dono do banco (`@ServiceConnection`), e não como
  `academia_app`: para provar uma permissão, conecte como `academia_app` (veja
  `UsuarioDaAplicacaoNoBancoTest`).
- `@Fetch(SUBSELECT)` ignora a paginação: repete a consulta sem o limite e carrega as filhas
  de todas as linhas (ADR 0028). Em lista paginada, use `@BatchSize`. Para ver o SQL num
  teste: `JAVA_TOOL_OPTIONS="-Dlogging.level.org.hibernate.SQL=DEBUG" ./mvnw test -Dtest=...`.
- Ficha encerrada não ganha divisões nem exercícios (V32): num teste que insere direto no
  banco, insira a ficha aberta, os filhos, e só então encerre.
- A lista de revisões vencidas usa `fichas.aluno_ativo`, mantida pelo aviso da inativação
  (V33): teste que inativa aluno direto no banco não passa pelo aviso; use a API.
- Pagar e estornar exigem o caixa de hoje aberto (ADR 0031), e todo lançamento novo precisa
  de um caixa aberto do dia do `registrado_em` (gatilho da V35). O caixa é **um por dia** e os
  testes dividem o banco: use `CaixaDeTeste.abrir(...)` no dia do teste e apague o caixa no fim
  (`limpeza.apagar("caixas", id)`); dia anterior esquecido aberto impede abrir o seguinte.
- O registro de acessos (consultas ao cadastro e logins) grava fora de transação
  (`Propagation.NEVER`): chamá-lo de dentro de uma dá `IllegalTransactionStateException`. A
  consulta ao cadastro grava o `subject` do token como UUID: token simulado (`jwt()`) que
  abre `GET /api/alunos/{id}` precisa de `.jwt(j -> j.subject(UUID...))`.
- O usuário da aplicação só tem DELETE em `tentativas_login`, `ficha_divisoes` e
  `ficha_exercicios` (o `UsuarioDaAplicacaoNoBancoTest` confere a lista): tabela nova nasce com
  DELETE e o teste falha até você decidir. Os testes conectam como dono do banco, então um
  DELETE que a aplicação precise e não tenha só aparece no Docker ou no e2e.
- `@PreAuthorize` só com perfil (`hasRole`, `hasAnyRole`): o `PerfilAntesDoCorpo` avalia a regra
  antes de ler o corpo, quando os argumentos do método ainda não existem.
- A API da cobrança mostra a `situacao` (calculada: `ATRASADA` é a pendente vencida), não o
  `status` do banco: em scripts de validação, compare com a situação certa.

## Segurança

Revisões e decisões nos ADRs 0007, 0010, 0012, 0014, 0020, 0021, 0022, 0028, 0029 e 0031 (registro de acessos); dinheiro, nos 0024, 0025 e 0026. Segredos só no `.env` (fora do Git,
modelo em `.env.example`); nunca imprima senhas, tokens ou o conteúdo do `.env`. Senhas
temporárias aparecem só na resposta (`Cache-Control: no-store`) e nunca no log.
