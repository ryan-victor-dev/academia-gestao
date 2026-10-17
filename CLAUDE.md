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
`cobranca` é Java puro (ADR 0004).

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
- O limite de login (20/min por IP, 10 falhas/15 min por conta) vale nos testes de ponta a
  ponta: espere a janela ou use contas diferentes.
- O limitador grava em transação própria (REQUIRES_NEW): a do login é desfeita quando a
  senha está errada.
- `psql -c` não substitui variáveis (`:'senha'`); passe o SQL pela entrada padrão.
- O `/tmp` (e a pasta de rascunho) pode ser apagado: guarde utilitários em `target/` ou `scripts/`.
- Caddy sem root: o binário exige a capacidade `NET_BIND_SERVICE` (setcap); sem ela, nem executa.
- Matrículas (e trancamentos) têm chave estrangeira para alunos, planos e usuários, e vários
  testes antigos apagam essas tabelas inteiras: teste que cria matrícula apaga os próprios
  trancamentos e matrículas antes de sair.
- `REQUIRES_NEW` dentro de outra transação usa uma segunda conexão: sob carga, o pool (10)
  esgota e tudo para por 30 s. Foi o que derrubou o login (ADR 0020); teste com mais
  requisições simultâneas do que conexões.
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

## Segurança

Revisões e decisões nos ADRs 0007, 0010, 0012 e 0014. Segredos só no `.env` (fora do Git,
modelo em `.env.example`); nunca imprima senhas, tokens ou o conteúdo do `.env`. Senhas
temporárias aparecem só na resposta (`Cache-Control: no-store`) e nunca no log.
