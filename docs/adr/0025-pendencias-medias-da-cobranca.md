# ADR 0025 — Pendências médias da cobrança: teto do plano, subida e registro de pagamentos

- **Status:** Aceito (completa o ADR 0024)
- **Data:** 2026-10-20

## Contexto

A validação da etapa 6 (cobrança), em 2026-10-20, achou três problemas médios,
reproduzidos no Docker:

1. **Valores grandes davam 500.** O plano aceitava até R$ 99.999.999,99 por mês e 60
   meses, mas a cobrança guarda o valor em `NUMERIC(10,2)`. O total à vista (valor × meses)
   estourava a coluna ao matricular, e o pagamento com atraso estourava ao pagar.
2. **Uma matrícula antiga com erro derrubava a subida.** A geração das cobranças das
   matrículas de antes da cobrança roda ao subir e não tratava o erro de cada uma. A
   exceção encerrava a aplicação, e o reinício automático a repetia sem fim.
3. **O estorno apagava o pagamento.** Ele anulava, na linha da cobrança, a forma, a data,
   quem recebeu e as partes. O pagamento só sobrava na trilha, sem as partes e apagado
   depois de 5 anos. Com estornos, não dava para conferir o caixa.

## Decisões

### Teto de R$ 100.000 por mês (decisão do dono)
- O valor mensal vai até **R$ 100.000** no plano e na matrícula, na API (400) e no banco
  (V23, `CHECK` em `planos` e `matriculas`). O à vista mais caro (60 meses) fica em 6
  milhões e cabe na cobrança.
- O pago (juros e total) passou para `NUMERIC(12,2)`: os juros crescem com os dias de
  atraso, sem limite, e mesmo com 10% ao mês levariam mais de mil anos para estourar.
- Um plano ou matrícula acima do teto faz a V23 falhar: ajuste o valor antes de atualizar.

### A subida não cai por uma matrícula
- O erro de cada matrícula antiga vai para o log, com o id, e as outras continuam. A que
  falhou fica sem cronograma e é tentada de novo na próxima subida.

### Registro de pagamentos (decisão do dono: registro + histórico agora, caixa na etapa 8)
- Tabela `lancamentos` (V24): cada pagamento é um lançamento `PAGAMENTO`, com valor,
  acréscimo, juros, total, forma, quem e quando. O estorno é um lançamento `ESTORNO`
  novo, com os mesmos valores e o motivo, que aponta para o pagamento. Nada se altera nem
  se apaga.
- A cobrança continua guardando o estado atual (o pagamento em vigor). O registro é
  gravado na mesma transação, então os dois não divergem.
- O banco confere: total = valor + acréscimo + juros; só o estorno tem motivo e aponta
  para um pagamento; cada pagamento é estornado uma vez só, e na mesma cobrança
  (chave estrangeira composta). O usuário da aplicação só insere e lê, como na trilha.
- Sem prazo de retenção: são registros financeiros. Se um dia houver prazo, é decisão do
  dono (com o contador).
- `GET /api/cobrancas/{id}/lancamentos` mostra o registro para ADMIN e RECEPCAO.
- A V24 copia o pagamento em vigor das cobranças já pagas. Os estornos de antes dela
  ficam só na trilha.

## Alternativas descartadas
- **Só aumentar as colunas**, mantendo o teto antigo: um valor mensal de R$ 99 milhões
  não faz sentido numa academia, e um teto realista pega erros de digitação.
- **Tirar o pagamento da linha da cobrança** e deixá-lo só no registro: seria mais
  normalizado, mas mudaria todas as consultas e o "pode treinar?" sem ganho para o caixa.
- **Guardar o estorno como data na linha do pagamento:** exigiria `UPDATE` no registro, e
  a garantia de "só se acrescenta" deixaria de valer também contra injeção de SQL.

## Consequências
- O fechamento de caixa (quanto entrou por dia e por forma, menos os estornos) sai do
  registro na etapa 8. Para ele, falta um índice por data (`registrado_em`).
- Os pagamentos de antes da V24 que já tinham sido estornados não aparecem no registro.
- `scripts/usuario-validacao.sh` agora dá senha nova ao usuário que já existe, em vez de
  recriá-lo: o usuário que registrou um pagamento não pode ser apagado (chave
  estrangeira), e o script falhava.

## Verificação
- Cada parte com testes novos e mutações à mão: 2 na subida (sem o tratamento e "parar no
  primeiro erro"), 7 no teto (Java, API e as travas da V23) e 11 no registro (gravação,
  estorno do pagamento em vigor, permissão e as travas da V24). Todas morreram.
- O PIT achou duas condições redundantes em `Lancamento.doPagamento` (paga sempre tem
  pagamento): ficou uma, e o PIT terminou sem sobreviventes.
- No Docker: a simulação que antes causava 4 reinícios agora sobe com o erro no log; o
  pior caso do teto matricula e paga; a V24 copiou as cobranças pagas que já existiam;
  10 estornos simultâneos da mesma cobrança, em duas rodadas, deram 1 sucesso e 9 × 409;
  e, conectado como `academia_app`, o banco recusa `UPDATE`, `DELETE` e `TRUNCATE` no
  registro.
