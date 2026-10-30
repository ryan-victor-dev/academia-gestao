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
