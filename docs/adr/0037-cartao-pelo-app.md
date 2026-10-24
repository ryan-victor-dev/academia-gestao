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
