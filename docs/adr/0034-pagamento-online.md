# ADR 0034 — Pagamento online (Pix e boleto) sem escolher a empresa

- **Status:** Aceito (atualiza os ADRs 0024, 0025 e 0031)
- **Data:** 2026-10-24

## Contexto

O dono quer que o aluno pague a mensalidade pelo app, com Pix ou boleto. O site não recebe
dinheiro sozinho: quem gera o Pix e o boleto e avisa que foram pagos é uma empresa de pagamentos
(Asaas, Mercado Pago, Efí, um banco com API etc.). O dono pediu (2026-10-24) **a estrutura
completa sem escolher a empresa**, como um esqueleto que sirva para outras academias e outros
projetos. Para as regras, aceitou os valores iniciais propostos:

1. O pagamento online entra **separado do caixa da recepção** ("recebido online").
2. O Pix e o boleto são gerados **quando o aluno pede**, com o valor do dia.
3. O aluno pode pagar **as atrasadas e a próxima a vencer** (configurável pelo ADMIN).

## Decisões

### Uma porta para qualquer empresa

- Módulo novo `pagamentos`. O resto do sistema não conhece empresa nenhuma: só a interface
  `ProvedorDePagamento` (gerar Pix ou boleto, consultar, cancelar e conferir um aviso).
  Cada empresa vira uma classe que implementa a interface, escolhida no `.env`
  (`ACADEMIA_PAGAMENTOS_PROVEDOR`). Trocar de empresa não muda nada fora dessa classe.
- **`desligado` é o padrão**: sem empresa configurada, o app não mostra os botões de pagar.
- **`simulado`** vem pronto, para desenvolvimento e testes: gera um Pix e um boleto de mentira e
  tem um botão "simular pagamento". Só existe com esse provedor ligado, e a tela avisa
  "modo de teste". Nunca use em produção (não sai dinheiro de ninguém, mas a mensalidade fica paga).
- **O aviso da empresa só dispara uma consulta.** O sistema não acredita no corpo do aviso:
  pergunta à empresa (`consultar`) se aquele pagamento foi pago e por quanto. Um aviso falso, no
  máximo, faz o sistema perguntar à toa. Mesmo assim, cada empresa confere a autenticidade do
  aviso (token ou assinatura) antes.
- **Consulta periódica**: de tempos em tempos, o sistema pergunta pelos pagamentos ainda em
  aberto. Cobre o aviso perdido e o ambiente sem endereço público (o computador local, onde o
  aviso não chega).

### Regras do pagamento online

- **O valor é o do dia em que o aluno gerou**, guardado com as partes (valor, acréscimo, juros).
  O Pix vale até o fim daquele dia (fuso da academia). O boleto vale até o vencimento da
  cobrança, se ela ainda não venceu; se já venceu, por 3 dias (configurável): os juros desses
  dias não são cobrados, porque o boleto tem valor fixo.
- **Pedir de novo** devolve o mesmo Pix ou boleto enquanto ele vale (com o valor combinado). Depois
  do prazo, o antigo é cancelado na empresa e um novo é gerado, com o valor do dia. Duas abas
  pedindo ao mesmo tempo podiam gerar dois; desde o ADR 0035, fica um só.
- **Só INSERT no registro**: o pagamento online vira um lançamento como o do balcão (ADR 0025),
  com a forma `PIX_ONLINE` ou `BOLETO` e **sem caixa** (o dinheiro cai na conta, não na gaveta).
  O fechamento do caixa não muda; o relatório do ADMIN mostra o online à parte.
- **Quem registrou**: o login do aluno que gerou o pagamento (é ele quem pagou).
- **Dinheiro que chega sem ter o que pagar**: a cobrança foi paga no balcão, cancelada, ou o
  valor recebido é diferente do gerado. O sistema não paga nada e marca o pagamento online como
  **a devolver**. O ADMIN vê a lista, devolve pelo painel da empresa e marca como devolvido (com
  motivo, na trilha). Desde o ADR 0035, o ADMIN confirma e o sistema devolve pela empresa, e
  quem paga a mais fica com a cobrança paga e recebe a sobra.
- **Estorno** de um pagamento online (ADMIN): como o balcão, mas sem caixa; o dinheiro volta pelo
  painel da empresa.
- **Concorrência**: o aviso e a consulta periódica podem chegar juntos. A linha do pagamento
  online é travada antes da cobrança, e só um deles registra. O balcão trava caixa e cobrança;
  o online, pagamento online e cobrança: as ordens não se cruzam.

### Configuração (ADMIN)

Tabela de uma linha, como a da cobrança: Pix ligado, boleto ligado, até onde adiantar
(`NENHUMA`: só as vencidas e as que vencem hoje; `PROXIMA`: mais a próxima; `TODAS`) e quantos
dias vale o boleto de uma cobrança vencida. O que não virou configuração: o pagamento online
dentro do caixa (não funcionaria de madrugada nem com o caixa fechado) e o boleto automático
antes do vencimento (pode vir depois, se o dono quiser).

## Consequências

- O fluxo inteiro funciona e é testado sem empresa nenhuma (provedor simulado).
- Cada empresa de verdade precisa de uma conta (no nome da academia), de testes no modo de
  teste dela e de um endereço público para o aviso. Antes disso, só a consulta periódica.
- O módulo `pagamentos` depende da cobrança só pela porta `CobrancasParaPagamentoOnline`, e dos
  alunos só para o nome, o CPF e o e-mail de quem paga (o boleto exige). Para outro projeto,
  basta trocar essas duas portas.
