# ADR 0031 — Caixa do dia e auditoria completa

- **Status:** Aceito
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
- As decisões de implementação (onde fica o caixa, tabelas, travas contra corrida entre
  fechar e pagar) são registradas aqui ao fim da etapa.
