# ADR 0037 — Cartão de crédito e de débito pelo app

- **Status:** Aceito (completa os ADRs 0034, 0035 e 0036)
- **Data:** 2026-10-24

## Contexto

O dono quer (2026-10-24) que o aluno também pague a mensalidade pelo app com cartão de crédito
ou de débito, além do Pix e do boleto (ADR 0034). Decisões do dono:

- **Cada mensalidade**, como o Pix: o aluno entra no app e paga quando quiser. Sem cobrança
  automática e sem cartão guardado.
- **Crédito só à vista**: sem parcelamento.
- **Contestação** (o aluno contesta no banco, que devolve o dinheiro a ele semanas depois): a
  mensalidade **volta em aberto**, com as regras de atraso e bloqueio, e o ADMIN é avisado.

## Decisões

### O cartão é digitado na página da empresa (decisão técnica, segurança)
- O número do cartão nunca passa pelo servidor da academia: o aluno é levado à página segura da
  empresa de pagamentos e volta ao app no fim. Receber ou guardar dados de cartão traria as
  exigências de segurança do PCI DSS, que um sistema como este não deve assumir.
- O débito online exige que o banco do aluno confirme a compra, e nem toda empresa oferece:
  cada empresa diz quais formas tem.

### Meios novos na cobrança (parte 11)
- `CREDITO_ONLINE` e `DEBITO_ONLINE` ("Crédito pelo app" e "Débito pelo app"), separados dos
  cartões do balcão (`CARTAO_CREDITO`, `CARTAO_DEBITO`): como o Pix online e o boleto, o dinheiro
  cai na conta pela empresa, **sem caixa** (V47). O balcão não registra esses meios.

### Formas de cartão no pagamento online (parte 12)
- `ProvedorDePagamento` ganha as formas `CREDITO` e `DEBITO`, `oferece(forma)` (cada empresa diz o
  que tem) e, no que ela gera, o `linkDePagamento`: a página onde o aluno digita o cartão. O
  aluno vai até ela e volta; a confirmação vem como a do Pix (aviso e consulta, ADR 0034).
- O link vale até o fim do dia, como o Pix, com o valor do dia, e segue a tolerância do Pix
  (1 h): a resposta do cartão chega em minutos, ao contrário da compensação do boleto.
- O ADMIN liga e desliga crédito e débito (V48, ligados de início, como o Pix e o boleto). Uma
  forma só aparece ao aluno se estiver ligada **e** a empresa oferecer; a tela do ADMIN avisa ao
  lado da chave quando a empresa não oferece.
- Devolução de cartão pelo sistema (estorno na fatura), como o Pix (ADR 0035), se a empresa
  devolver. O simulado oferece todas as formas e devolve tudo, menos boleto; a "página da
  empresa" dele é uma tela do próprio app, que não pede cartão nenhum.

### O aluno paga com cartão (parte 13)
- Na tela Pagamentos, cada mensalidade que se paga hoje ganha "Cartão de crédito" e "Cartão de
  débito" (só as formas disponíveis). O toque vai à página da empresa: a que ainda vale, ou uma
  nova. Link de fora (empresa de verdade): o navegador sai do app e volta no fim. Link do próprio
  app (o simulado): a navegação fica dentro dele.
- A "página da empresa" do simulado é a rota `/cartao-simulado/{id}`, só do ALUNO: avisa que é
  teste, não pede cartão, e "Aprovar pagamento (teste)" faz o mesmo que o simular do Pix. A API
  recusa a simulação fora do provedor simulado ou para o pagamento de outro aluno.
