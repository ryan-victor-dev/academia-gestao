# ADR 0031 — Caixa do dia e auditoria completa

- **Status:** Aceito; completado pelo [ADR 0032](0032-pendencias-medias-da-etapa-8.md)
- **Data:** 2026-10-23

## Contexto

A etapa 8 traz o fechamento de caixa e completa a trilha de auditoria. A recepção já
registra pagamentos e estornos no balcão, e cada um vira um lançamento que nunca se apaga
(ADR 0025). A trilha já guarda as alterações de planos, alunos, usuários, cobranças,
configuração, exercícios e fichas por 5 anos (ADRs 0021 e 0022).

As regras de negócio abaixo foram decididas com o dono antes da implementação.

## Decisões do dono

### O caixa do dia
- **Um caixa para a academia por dia**, no horário da academia (não um por recepcionista).
- **Abertura:** a recepção (ou o ADMIN) abre o caixa do dia informando o **troco** que está
  na gaveta.
- **Sem caixa aberto, nada de dinheiro entra nem sai:** pagamento e estorno, em qualquer
  forma, são recusados até alguém abrir o caixa do dia.
- **Dia anterior esquecido aberto:** o caixa de hoje só abre depois de fechar o anterior,
  com a contagem e, se for o caso, a justificativa. Nenhum dia fica sem fechamento.

### O fechamento
- A recepção (ou o ADMIN) **conta só o dinheiro da gaveta** e informa o total. PIX e
  cartões já ficam registrados com a forma certa; quem os confere, com o extrato e a
  maquininha, é o ADMIN pelo relatório.
- O sistema calcula o **esperado** = troco + pagamentos em dinheiro − estornos em dinheiro do
  dia, e a **diferença** = contado − esperado.
- **Diferença diferente de zero:** o fechamento é aceito e a diferença fica gravada, mas
  exige uma **justificativa** escrita (por exemplo, "dei R$ 10 de troco a mais"). O ADMIN vê
  a diferença e a justificativa depois.
- **Dia fechado não recebe:** pagamento ou estorno depois do fechamento é recusado até o
  dia ser reaberto.
- **Reabrir** um dia fechado: **só o ADMIN, com motivo**. O fechamento anterior continua no
  histórico; a recepção corrige e fecha de novo.

### O relatório
- Quanto entrou em cada dia e em cada forma (dinheiro, PIX, débito, crédito), menos os
  estornos, com o troco, o esperado, o contado e a diferença de cada fechamento.
- A **recepção** vê o relatório e fecha o caixa; o **ADMIN** vê todos os dias e todos os
  fechamentos.

### Auditoria completa
Entram na trilha, além do que já entra:
- **Matrículas:** criar, trancar, registrar a volta, cancelar e trocar de plano.
- **Caixa:** abertura, fechamento (com a diferença e a justificativa) e reabertura (com o
  motivo).
- **Consultas a dados pessoais:** quem da equipe abriu o cadastro de qual aluno. Buscas e
  listas não entram, para não gerar uma linha por aluno listado.
- **Logins:** cada entrada e cada senha errada, com a conta e o IP. O bloqueio por
  tentativas continua como está (ADR 0022).
- **Retenção:** consultas e logins ficam **1 ano** (são muitos e guardam quem consultou e o
  IP: dado pessoal, e a LGPD pede guardar o mínimo; um ano cobre uma investigação de acesso
  indevido). As alterações continuam 5 anos (ADR 0022).

### Decisão minha (a confirmar na revisão)
- A consulta é registrada para **qualquer perfil da equipe** que abra o cadastro do aluno,
  inclusive o instrutor: ele vê menos campos (ADR 0009), mas vê as observações, que podem
  ter dados de saúde ("lesão no joelho").

## Consequências
- Pagar e estornar passam a depender do caixa do dia: a recepção precisa abrir o caixa
  antes de receber o primeiro pagamento e fechá-lo no fim do dia.
- O e2e e os testes que pagam precisam abrir o caixa antes.
- As decisões de implementação estão abaixo.

## Decisões do dono durante a implementação (23/10)

- **Reabrir um dia passado:** o ADMIN reabre qualquer dia fechado. Como o pagamento entra
  sempre no caixa de hoje, um dia passado reaberto só aceita nova contagem; o dia seguinte
  só abre depois de fechá-lo de novo.
- **Troco digitado errado** não se corrige: a diferença aparece no fechamento e vai na
  justificativa.
- **O que a recepção vê:** o caixa de hoje e o dos dias ainda abertos (para fechá-los). Os
  dias fechados, com as diferenças e justificativas, só o ADMIN.
- **Contagem às cegas:** com o caixa aberto, a recepção não vê o dinheiro recebido nem o
  esperado (nem o de um fechamento anterior a uma reabertura); depois de fechar, vê a
  diferença. Assim a contagem não é ajustada para bater.
- **Logins:** a falha com uma conta que não existe guarda o e-mail digitado; entram também
  o login bloqueado pelo limite e a senha atual errada na troca de senha. Só o ADMIN vê o
  registro de consultas e logins.

## Decisões da implementação

### Onde fica o caixa
- No módulo `cobranca`: o caixa soma os lançamentos, e o pagamento consulta o caixa; em
  módulos separados, um dependeria do outro nos dois sentidos. As regras (abrir, fechar,
  reabrir, esperado, diferença, o que a recepção vê) ficam em `cobranca.dominio.Caixa` e o
  movimento por forma em `MovimentoDoDia`, em Java puro.
- **Tabelas (V35):** `caixas` (um por `dia`, chave única), `fechamentos_de_caixa` e
  `reaberturas_de_caixa` (o histórico; o usuário da aplicação só insere e lê) e
  `lancamentos.caixa_id`. Do caixa só muda a situação (um gatilho recusa mudar o dia, o
  troco ou quem abriu). O banco confere que a diferença é contado − esperado e que
  diferença diferente de zero tem justificativa.
- **Lançamentos antigos:** os de antes da V35 ficam sem caixa (`CHECK ... NOT VALID`: a
  trava vale só para as linhas novas) e entram no relatório pelo dia do registro, no fuso da
  academia (V36: um índice parcial só para eles).
- **O dia** é o de hoje no fuso da academia (`Calendario.hoje`), tirado do mesmo instante
  que vai para o registro do pagamento. Um gatilho recusa um lançamento num caixa que não
  está aberto ou que não é o do dia do `registrado_em`.

### Travas contra a corrida entre fechar e pagar
- Pagar e estornar leem a linha do caixa de hoje com `FOR SHARE`, antes de travar a
  cobrança (sempre nessa ordem); vários pagamentos não se esperam entre si. Fechar e
  reabrir travam a linha com `FOR UPDATE` e só então somam o dinheiro. Um pagamento em
  andamento termina antes e entra no esperado; um que chega depois espera o fechamento e o
  encontra fechado (409). O fechamento só trava o caixa, nunca uma cobrança: sem deadlock.
- Duas aberturas ao mesmo tempo: a chave única do dia deixa uma ganhar; a outra recebe 409.
  Uma conferência "já existe caixa hoje?" antes de inserir foi retirada: era redundante (a
  mutação dela sobreviveu, por ser equivalente à chave única).

### API
- `POST /api/caixas` abre o de hoje; `POST /api/caixas/{dia}/fechar` e `/reabrir`;
  `GET /api/caixas/{dia}`, `/abertos` e `/relatorio?de=&ate=` (só o ADMIN, até 366 dias). A
  soma das diferenças do relatório usa o último fechamento de cada dia fechado; um dia
  reaberto e ainda sem a nova contagem fica de fora. Quem fecha vê o resultado do próprio
  fechamento, mesmo de um dia passado.

### Auditoria
- **Trilha das matrículas** (`MATRICULA`, V37): criar, trancar, registrar a volta, cancelar e
  trocar, com o retrato de antes e de depois (plano contratado, datas, último trancamento
  com o motivo, cancelamento, troca, crédito; do aluno, só o id). Na troca, a atual fica
  `TROCADA` e a nova, `CRIADA`, aponta para ela. O caixa entra como `CAIXA` (aberto, fechado
  com o esperado, o contado, a diferença e a justificativa, reaberto com o motivo).
- **Registro de acessos** (`acessos`, V38), separado da trilha: são muitos mais registros, com
  outro prazo. A porta `RegistroDeAcessos` fica em `compartilhado` (o `auth` não depende de
  outros módulos) e a implementação, no módulo `auditoria`. Grava **fora de transação**
  (`Propagation.NEVER`, que recusa ser chamada de dentro de uma): a transação do login com
  senha errada é desfeita e a da consulta é só de leitura; sem `REQUIRES_NEW`, nada de uma
  segunda conexão do pool (ADR 0020). A consulta é gravada antes de responder: se o registro
  falhar, o cadastro não sai. O login bloqueado vem sem o id do usuário (a conta não é
  consultada antes do limite); filtre pela conta.
- **Retenção:** a função `apagar_acessos_vencidos` (do dono do banco, em lotes de até 10 mil,
  como a da trilha) apaga o que tem mais de 1 ano; a tarefa diária chama as duas. O usuário
  da aplicação não altera nem apaga o registro.

## Verificação
- Cada parte com testes novos e mutações à mão: 26 no caixa (uma equivalente, retirada), 24
  no relatório (duas pediram reforço nos testes antes de morrer), 13 na trilha das matrículas
  e 21 no registro de acessos. Todas as outras morreram.
- Os testes de corrida (duas aberturas; fechar × pagar em 30 rodadas) exigem que cada lado
  ganhe em parte das rodadas, e foram repetidos à parte.
- Cada parte foi validada no Docker de verdade (16, 11, 8 e 13 checagens, inclusive a
  corrida, as permissões como `academia_app` e o IP do cliente no registro, e não o do
  Caddy), e o `scripts/e2e.sh` cobre o caixa, o relatório, a trilha e os acessos numa
  instalação do zero.
