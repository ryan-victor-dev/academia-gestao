# ADR 0024 — Cobrança: mensalidades, atraso, pagamento e inadimplência

- **Status:** Aceito (completa o ADR 0018; substitui a máquina de estados do ADR 0004)
- **Data:** 2026-10-19

## Contexto

Até aqui o módulo `cobranca` tinha só a máquina de estados da mensalidade, sem uso nem
banco (diagnóstico de 2026-10-18, R9). O ADR 0018 decidiu o vencimento (o dia do início da
matrícula; sem esse dia no mês, o último dia dele) e as duas formas de pagamento (mensal:
uma mensalidade por mês; à vista: uma cobrança com o total no início). Este ADR registra o
resto, decidido pelo dono do projeto em 2026-10-19, e como foi implementado.

## Decisões do dono

### Atraso
- **Sem tolerância:** venceu no dia 10 e não pagou, no dia 11 já está atrasada.
- **Acréscimo único**, em **percentual ou em reais** (ex.: R$ 7), e **juros por dia de
  atraso**. Padrões: acréscimo de 2% e juros de 1% ao mês.
- **Teto de 2%:** o acréscimo cobrado nunca passa de 2% do valor da cobrança (limite da
  multa de mora do Código de Defesa do Consumidor, art. 52 §1º). Um acréscimo de R$ 7 numa
  mensalidade de R$ 100 vira R$ 2. Os juros ficam fora do teto.
- **Configuração única da academia**, editada pelo ADMIN. A mudança **vale para todos**,
  inclusive matrículas antigas: o atraso é calculado com a configuração do dia do
  pagamento.

### Inadimplência
- O "pode treinar?" passa a dizer **não** quando o aluno tem uma cobrança com **mais de 10
  dias de atraso** (do 11º dia em diante). Os 10 dias são editáveis pelo ADMIN.

### Pagamento
- **A recepção registra** o pagamento feito no balcão (dinheiro, Pix, cartão de débito ou de
  crédito). Não há integração com banco nem gateway por enquanto.
- **Só o valor exato:** o devido no dia (valor + acréscimo + juros), sem pagamento parcial
  e sem desconto.
- **RECEPCAO e ADMIN** registram pagamentos. **Só o ADMIN estorna ou cancela** uma
  cobrança, sempre com motivo. Tudo vai para a trilha de auditoria.
- **Estorno desfaz o pagamento:** a cobrança volta a ficar em aberto (e pode atrasar). Se o
  aluno não deve mais, o ADMIN também a cancela.

### Matrícula
- **Trancamento:** as mensalidades **pausam e são empurradas** pelos dias parados, como o
  fim da matrícula. A volta antecipada desfaz a parte não usada.
- **Cancelamento ou troca com fidelidade:** as mensalidades futuras são canceladas e a
  multa já calculada (ADR 0018) vira uma **cobrança avulsa**, que atrasa como as outras.
- **À vista cancelado:** sem devolução automática; a academia combina fora do sistema.
- **Matrículas que já existiam:** geram só as mensalidades que vencem **do dia da
  implantação em diante**. As anteriores são tratadas como acertadas fora do sistema, e
  ninguém fica atrasado de repente.

## Decisões da implementação

### Domínio
- A `Mensalidade` vira **`Cobranca`**, com um tipo: `MENSALIDADE`, `A_VISTA` ou `MULTA`.
- **Estados guardados:** `PENDENTE`, `PAGA` e `CANCELADA`. Transições: `pagar`
  (pendente → paga), `estornar` (paga → pendente) e `cancelar` (pendente → cancelada). Paga
  não se cancela: estorne antes.
- **"Atrasada" é calculada** pela data (pendente e vencimento antes de hoje), como a
  situação da matrícula. Saem `VENCIDA` e `ESTORNADA` do ADR 0004: guardar `VENCIDA`
  exigiria uma tarefa diária para marcar, e uma data errada nela deixaria o estado errado.
- O domínio continua em **Java puro**, agora em `cobranca.dominio` (o `ArquiteturaTest`
  confere esse pacote). O resto do módulo (service, controller, repositório) fica em
  `cobranca` e usa Spring e `JdbcTemplate`.

### Cronograma
- A matrícula avisa a cobrança a cada mudança (criação, trancamento, volta, cancelamento e
  troca), na mesma transação. A cobrança **recalcula o cronograma inteiro** a partir dos
  dados da matrícula e acerta o banco. Recalcular dá sempre o mesmo resultado e cobre todos
  os casos com uma só conta, sem regras separadas para cada evento.
- **Parcela k** (mensal) vence em `início + (k − 1) meses`. Sem o dia no mês, cai no último
  dia (31/01 → 28/02 → 31/03). Depois soma os dias de cada trancamento que começou até
  esse vencimento.
- **À vista:** uma parcela, com o total (valor mensal × duração), vencendo no início.
- **Encerramento:**
  - Cancelada antes de começar: todas as parcelas são canceladas.
  - Cancelada depois de começar: ficam as que vencem até o dia do cancelamento (o mês em
    curso conta como usado, como na multa) e as outras são canceladas.
  - Trocada: ficam as que vencem antes do dia da troca, porque a nova começa com a
    primeira dela nesse dia.
- Parcela paga que sai do cronograma (paga adiantada e depois cancelada) continua paga; a
  devolução, se houver, é combinada fora do sistema.
- **Multa:** uma cobrança `MULTA`, vencendo no dia do cancelamento ou da troca.
- **A partir de quando:** cada matrícula guarda desde quando tem cobranças no sistema, o
  maior entre o início e o dia em que foi vista pela primeira vez. Para as novas, é o
  início (nunca é no passado); para as que já existiam, é o dia da implantação. Ao subir,
  a aplicação gera o cronograma das matrículas que ainda não têm um.

### Valor devido no dia
- `dias de atraso = hoje − vencimento` (só se positivo).
- `acréscimo = mín(configurado, 2% do valor)`, onde o configurado é um percentual do valor
  ou um valor em reais.
- `juros = valor × taxa ao mês × dias / 30` (juros simples, proporcionais ao dia).
- Cada parte é arredondada em centavos (meio para cima). O pagamento guarda as três
  partes, a forma, quem registrou e quando.
- **O valor informado precisa ser igual ao devido.** Se a tela ficou aberta da véspera e
  o valor mudou, o pagamento é recusado (409) com o valor atual. Isso evita receber menos
  sem perceber.

### Configuração
- Uma linha só no banco: tipo do acréscimo (percentual ou valor), o acréscimo, os juros ao
  mês e os dias para bloquear. ADMIN e RECEPCAO leem; só o ADMIN altera, com `If-Match`
  como nos outros cadastros, e a alteração vai para a trilha.
- **Faixas aceitas:**
  - Acréscimo percentual: de 0 a 2%. Acima disso o teto cortaria de qualquer jeito, e
    aceitar daria a impressão errada.
  - Acréscimo em reais: de 0 a R$ 1.000, ainda limitado aos 2% na hora de cobrar.
  - Juros: de 0 a 10% ao mês. É só uma trava contra erro de digitação; a responsabilidade
    pelo valor escolhido é da academia.
  - Dias para bloquear: de 0 a 90. Zero bloqueia no primeiro dia de atraso.

### Quem vê
- ADMIN e RECEPCAO veem as cobranças de qualquer aluno e a lista de atrasadas.
- O aluno vê as próprias.
- O INSTRUTOR continua vendo só o "pode treinar?".

## Consequências
- O "pode treinar?" passa a depender do pagamento. Um pagamento esquecido de registrar
  bloqueia o aluno depois de 10 dias, e a recepção resolve registrando.
- Mudar o acréscimo ou os juros muda o valor de todas as cobranças atrasadas a partir
  daquele dia. Foi a escolha do dono; a trilha registra quem mudou e quando.
- Sem pagamento parcial, desconto ou devolução no sistema: o que fugir disso fica fora dele.
- **Vale conferir com um advogado ou contador** o acréscimo, os juros e a multa de
  fidelidade escolhidos.
