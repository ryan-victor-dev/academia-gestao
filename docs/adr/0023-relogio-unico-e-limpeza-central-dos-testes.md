# ADR 0023 — Relógio único e limpeza central dos testes

- **Status:** Aceito
- **Data:** 2026-10-19

## Contexto

A revisão geral de 2026-10-17 apontou duas pendências de arquitetura, de gravidade baixa,
que pesam na etapa de cobrança (vencimento, atraso, juros): sem um jeito de testar uma
regra de data numa data escolhida, e com a limpeza dos testes espalhada, cada regra nova
de cobrança custaria caro para testar.

- **R4:** quatro componentes criavam o próprio `Clock.systemUTC()`, o token usava
  `Instant.now()` e o cadastro de aluno, `LocalDate.now()`. Nenhum teste conseguia mudar a
  data, e os testes de matrícula comparavam o "hoje" deles com o da aplicação (falha
  possível perto da meia-noite).
- **R5:** 31 classes de teste apagavam tabelas inteiras na mão. Cada tabela nova com chave
  estrangeira exigia ajustes em várias delas.

## Decisões

### 1. Um só relógio, injetado (R4)
- `RelogioConfig` (compartilhado) publica o `Clock` da aplicação. Todo "agora" sai dele:
  matrículas, inativação de aluno, cadastro e edição de aluno, limite de login, limite de
  requisições, emissão do token e a conferência da expiração do token.
- O `@Past` dos pedidos (Bean Validation) usa o mesmo relógio, no fuso da academia.
- O `ArquiteturaTest` proíbe ler a hora do sistema (`now()` dos tipos de data,
  `Clock.system*`, `System.currentTimeMillis`/`nanoTime`) fora do `RelogioConfig`.
  **Exceção:** os carimbos de criação de usuário e de plano, que só são exibidos e não
  entram em nenhuma regra. Trocá-los mudaria a assinatura de ~70 chamadas nos testes sem
  ganho; se um dia uma regra depender deles, eles passam para o relógio.

### 2. O dia é o da academia
- `Calendario` (compartilhado) guarda o fuso (`America/Sao_Paulo`) e calcula o "hoje" a
  partir de um momento. A matrícula já fazia isso; o cadastro de aluno, não.
- **Defeito corrigido:** o container da aplicação roda em UTC. Depois das 21h de São Paulo,
  o cadastro aceitava nascimento "amanhã", e o `@Past` aceitava "hoje" à noite, mas o
  recusava de manhã.

### 3. Relógio de teste controlável
- Nos testes, `RelogioDeTeste` toma o lugar do relógio (bean `@Primary` na
  `TestcontainersConfiguration`). Sem mexer, anda com a hora real. Um teste pode pará-lo
  (`parar`), adiantá-lo (`adiantar`) ou fixá-lo (`fixar`), e precisa restaurá-lo no fim
  (o contexto do Spring é compartilhado).
- Os testes de matrícula param o relógio: o "hoje" não vira entre o teste e a aplicação.

### 4. Limite de requisições com relógio que volta
- O limite guarda, por usuário, o momento em que o balde estaria cheio. Se o relógio
  voltasse (um ajuste do NTP, ou o relógio de teste restaurado depois de adiantado), esse
  momento ficava no futuro e o usuário recebia 429 até a hora antiga voltar: 440 dias, no
  primeiro teste que adiantou o relógio.
- **Regra:** se o balde está mais de uma janela (1 minuto) à frente de agora, ele recomeça
  cheio. Só acontece com o relógio voltando, e o ganho máximo é uma janela de requisições.
  O aviso no log com data no futuro também deixa de calar os avisos seguintes.

### 5. Limpeza central dos testes (R5)
- `LimpezaDoBanco.apagarTudo()` apaga todas as tabelas, menos o histórico do Flyway,
  das filhas para as mães. A ordem sai das chaves estrangeiras do catálogo do Postgres a
  cada chamada: uma tabela nova entra sozinha. Autorreferência (a matrícula aponta para a
  que ela substituiu) não atrapalha, porque um `DELETE` só apaga a tabela inteira.
- `DELETE`, e não `TRUNCATE`: o `TRUNCATE` trava a tabela inteira e esperaria qualquer
  transação aberta, como a de uma thread de um teste de concorrência.
- As classes que apagavam tabelas inteiras usam a limpeza central. As que apagam só o que
  criaram, pelo id, continuam assim: rodam junto com dados de outras classes sem apagá-los.

## Como foi verificado
- Mutações feitas à mão: trocar o relógio injetado pelo do sistema em cada componente, o
  fuso da academia por UTC, a validação sem o relógio, cada caso da regra do ArchUnit e a
  limpeza (ordem invertida, sem ordem, apagando o Flyway, sem apagar). 27 de 27 mortas.
  A do `@Past` em UTC sobreviveu de início (às 14h, São Paulo e UTC estão no mesmo dia) e
  pediu o teste das 23h30.
- `./mvnw verify` em ordem aleatória a cada parte (quatro rodadas, sem falhas).
- No Docker: token de 15 minutos emitido agora, token adulterado recusado, matrícula
  começando hoje no fuso da academia, "pode treinar?" verdadeiro, inativação recusada
  com matrícula vigente; nascimento hoje recusado e ontem aceito, `criadoEm` atual,
  edição para nascer amanhã recusada, mais de 120 anos recusado.

## Consequências
- Regras de data novas (vencimento, atraso, juros) recebem o `Clock` e são testadas de
  ponta a ponta numa data escolhida com o `RelogioDeTeste`.
- Teste que mexe no relógio precisa restaurá-lo; se esquecer, os seguintes rodam numa data
  errada. Os testes que mexem nele restauram no `@AfterEach`.
- Quem apaga só o que criou não ganha nada com a limpeza central; quem apaga tudo precisa
  rodar sozinho (os testes já rodam em sequência, nunca em paralelo).
