# ADR 0046 — Decisões do dono sobre as últimas pendências

- **Status:** Aceito
- **Data:** 2026-10-29

## Contexto

Da lista de pendências de 23/10, sobraram seis itens que pediam uma decisão do dono, e não uma
correção técnica: 3, 7, 8, 9, 10 e 15. Em 29/10 ele decidiu os seis. Cada item tem uma seção abaixo,
com o que foi decidido, o que ficou de fora e o que mudou no sistema. Cada um entrou num commit.

### 9 — O motivo do trancamento fica fora da trilha
Antes: o retrato da matrícula na trilha (ADR 0031) levava o último trancamento com o motivo. O motivo
é texto livre e muitas vezes tem dado de saúde ("cirurgia no joelho", "gravidez"), um dado sensível
pela LGPD, e a trilha fica 5 anos (ADR 0022).

**Decisão do dono:** a trilha não grava o motivo do trancamento. Ela continua registrando quem trancou,
quando, o início, a volta prevista e a volta. O motivo continua no próprio trancamento, onde a recepção
e o ADMIN o veem na matrícula, e sai junto com ela.

Ficaram de fora: manter o motivo na trilha (5 anos de um dado de saúde, só para justificar uma alteração
que o próprio trancamento já justifica) e não guardar o motivo em lugar nenhum (perde a informação de
por que a pessoa trancou).

O motivo do cancelamento continua na trilha: é a justificativa de uma alteração que mexe em dinheiro
(multa), e a decisão foi só sobre o trancamento. As linhas antigas da trilha não foram reescritas: o
sistema ainda não foi ao ar, e só existem dados de desenvolvimento.

O `TrilhaDasMatriculasTest` confere que a linha `TRANCADA` não tem o motivo.

### 15 — A observação do exercício na ficha vai até 200 caracteres
Antes: a ficha aceitava 7 divisões de 20 exercícios, cada um com observação de até 500 caracteres. A
maior ficha que as regras aceitavam dava uns 98 KB, e o limite de cada pedido é 64 KB (ADR 0012): ela
seria recusada com 413, embora válida.

**Decisão do dono:** a observação do exercício na ficha passa a ir até 200 caracteres. A maior ficha
cai para uns 55 KB e cabe no limite. A observação do catálogo de exercícios continua em 500 (ela vai
num pedido pequeno).

Ficaram de fora: um limite maior só nas rotas da ficha (o servidor passaria a aceitar pedidos maiores
nelas) e só documentar.

- A coluna no banco continua com 500: as fichas de antes seguem valendo e aparecem inteiras. Corrigir
  uma delas exige encurtar a observação longa (400, com o campo).
- A tela para de aceitar letras no 200º caractere.
- A conta vale para texto comum. Uma ficha no limite de tudo, com todas as observações cheias só de
  letras acentuadas (2 bytes cada no UTF-8), ainda passaria de 64 KB e receberia 413. Uma ficha de
  verdade fica muito longe disso.

O `FichaControllerTest.aMaiorFichaCabeNoLimiteDoCorpo` monta a maior ficha pelas constantes da regra e
confere o 201 (com 500, dá 413).

### 7 — A recepção ainda consegue somar o dinheiro do dia: risco aceito
A contagem às cegas (ADR 0031) esconde da recepção o dinheiro recebido e o esperado do caixa aberto.
Mas ela vê a forma e o valor de cada pagamento, nos lançamentos da cobrança e na lista de cobranças
do aluno, e pode somá-los para saber o esperado antes de contar.

**Decisão do dono:** aceitar o risco e documentar. É a própria recepção quem registra cada pagamento,
então ela sempre sabe quanto recebeu (pode anotar num papel); esconder os lançamentos atrapalharia o
atendimento ("já paguei esse mês?") sem fechar a brecha. A contagem às cegas continua valendo para o
que ela faz bem: pegar o erro de troco e o desvio casual, de quem não se deu ao trabalho de somar.
Contra o desvio de propósito, valem a trilha (quem registrou cada pagamento, e quando) e a conferência
do ADMIN.

Ficou de fora: tirar da recepção a lista de lançamentos, deixando só a situação da cobrança. O README
diz o limite da contagem às cegas.

### 8 — O troco da abertura comparado com o que ficou na gaveta
Antes: a abertura aceitava qualquer troco, sem comparar com a contagem do fechamento anterior.
Quem abria podia declarar um troco menor do que estava na gaveta e embolsar a diferença: o
fechamento do dia bateria, porque o esperado parte do troco declarado.

**Decisão do dono:** comparar. A abertura continua às cegas (quem abre não vê o que tinha ficado) e é
aceita mesmo com um troco diferente; a diferença aparece para o ADMIN, no caixa do dia e no relatório.

Comparar direto com o contado não serviria: o dinheiro costuma sair da gaveta depois do fechamento
(para o banco), e o ADMIN veria uma diferença todo dia. Então, **decisão do dono também**: quem fecha
informa quanto tirou da gaveta, e o troco esperado na abertura seguinte é `contado − retirado`.

- **Fechamento:** campo `retirado`, opcional (sem ele, zero), de 0 até o contado, em centavos. Não
  muda o esperado nem a diferença do fechamento: sai depois de contar. Vai para a trilha.
- **Abertura:** grava em `troco_esperado` o que ficou no **último** fechamento do caixa mais recente
  antes de hoje (todos já estão fechados: é a regra para abrir). Nulo se não há caixa antes. Como o
  troco, o troco esperado não muda depois (o gatilho da V35 passou a conferir os dois). Vai para a
  trilha.
- **Quem vê:** só o ADMIN recebe `trocoEsperado` e `diferencaNaAbertura` (troco − troco esperado;
  negativa = entrou menos do que ficou). A recepção não recebe nem com o caixa fechado: os dois
  revelariam a contagem do fechamento anterior. O relatório traz a diferença de cada dia e a soma
  do período (`diferencaDasAberturas`).
- **No banco:** `retirado` entre 0 e o contado, `troco_esperado` não negativo. Os fechamentos de
  antes ficaram com `retirado` zero, e os caixas de antes, sem troco esperado.

Ficaram de fora: barrar a abertura com troco diferente (deixaria de ser às cegas: quem abre
descobriria o valor por tentativa) e só documentar.

Um limite: o troco esperado é o que se sabia na abertura. Se o ADMIN reabrir depois um dia anterior e
ele for fechado de novo com outro retirado, a abertura seguinte não muda; a reabertura e o fechamento
novo ficam na trilha.

O `CaixaTest` e o `CaixaControllerTest.aberturaComparadaComOQueFicouNaGavetaSoParaOAdmin` cobrem a
conta, o último fechamento do dia, quem vê e o relatório; o `bancoRecusaCaixaEFechamentoIncoerentes`, as
travas do banco. Na tela, quem fecha tem o campo do retirado, e o ADMIN vê a diferença da abertura
no caixa do dia e uma coluna no relatório.

### 10 — Código da transação no Pix e no cartão do balcão
Antes: a recepção registrava um pagamento no Pix ou no cartão sem nada que o ligasse ao dinheiro de
verdade. O dinheiro, o fechamento do caixa confere; o Pix e o cartão, só o ADMIN, comparando o total
do dia com o extrato e a maquininha. Um pagamento registrado sem ter sido recebido (ou o mesmo
comprovante usado em duas cobranças) só aparecia como uma diferença no total, sem dizer qual era.

**Decisão do dono:** o código da transação passa a ser obrigatório no Pix e no cartão do balcão, e o
código repetido é recusado (409). Detalhes, também do dono:

- **Pix:** o código (o ID da transação) nunca repete entre as cobranças pagas. O estorno libera: o Pix
  foi devolvido, e o mesmo comprovante pode pagar de novo.
- **Cartão:** o código (NSU ou autorização) não repete na mesma forma (débito ou crédito) no mesmo dia,
  no fuso da academia: a numeração da maquininha recomeça. O estorno também libera.
- **Formato:** texto livre, de 1 a 100 caracteres, guardado sem espaços (inclusive o que não quebra, do
  copiar e colar) e em maiúsculas: "e2e 123" e "E2E123" são o mesmo comprovante. Caractere de controle
  é recusado. Dinheiro não tem código (400 se vier um).
- **Lista do dia:** o ADMIN vê cada pagamento e estorno do caixa do dia (hora, forma, valor, código,
  aluno e quem registrou) em `GET /api/caixas/{dia}/lancamentos` e na tela do caixa. A recepção não
  vê: a lista daria o dinheiro da contagem às cegas. Só o balcão: o online não passa pelo caixa, e a
  empresa de pagamentos já confirma cada um.

Ficaram de fora: só documentar, e exigir o código também no dinheiro (não há comprovante).

- **No banco (V54):** `codigo_transacao` na cobrança (o pagamento em vigor) e no registro de
  lançamentos (o estorno repete o do pagamento). Quem barra o repetido são dois índices únicos
  parciais, um do Pix e um do cartão por forma e dia (no fuso de São Paulo, que o `FusoNoBancoTest`
  confere também nos índices); o serviço traduz a violação em 409, também numa corrida. Não há
  checagem antes no Java: daria a mesma resposta, e repetiria a regra do dia. O código só aparece no
  Pix e no cartão, e limpo (CHECK). Todo pagamento novo nessas formas tem código (CHECK `NOT VALID`:
  os de antes ficaram sem, e o registro só aceita INSERT).
- **Trilha:** o pagamento leva `codigoTransacao`.
- **Nomes de quem registrou:** a lista pede ao módulo de autenticação, pela porta nova `NomesDaEquipe`
  (só o nome, nem o login nem o e-mail).

O `PagamentoTest` cobre a limpeza e os limites; o `CobrancaControllerTest`, a recusa sem o código, o Pix
repetido até o estorno, o cartão no mesmo dia e em outro, e dois pagamentos ao mesmo tempo com o mesmo
Pix (sempre um 200 e um 409, cada lado ganhando em parte das rodadas); o `CobrancaRepositoryTest`, as
travas do banco; o `CaixaControllerTest`, a lista do dia só para o ADMIN.
