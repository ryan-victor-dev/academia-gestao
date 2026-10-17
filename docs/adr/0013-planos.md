# ADR 0013 — Planos: preço, nome único e quem vê o quê

- **Status:** Aceito; fidelidade e forma de pagamento acrescentadas pelo [ADR 0018](0018-fidelidade-trancamento-troca-e-vencimento.md)
- **Data:** 2026-10-16

## Contexto

Planos são o que a academia vende (Mensal, Trimestral, Anual...). A tabela
existia desde a V1, sem regras além de valor e duração positivos. A matrícula
(etapa 5) vai depender deles, e o preço é a informação mais sensível: um
reajuste não pode mudar, sem aviso, o que um aluno já matriculado paga.

## Decisão

### Preço
- O ADMIN pode reajustar o valor de um plano. **O reajuste vale só para
  matrículas novas:** cada matrícula vai guardar o valor do dia em que foi
  feita (regra a implementar na etapa 5, ADR próprio).
- Valor mensal maior que zero, com no máximo 2 casas decimais (129,999 não
  existe em reais) e até 99.999.999,99 (`NUMERIC(10,2)`). Na API, o valor sai
  sempre com duas casas (`129.90`).
- Duração de 1 a 60 meses: acima disso, quase certamente é erro de digitação.
  A regra existe no Java e num `CHECK` do banco.
- A resposta traz também o valor total (mensal × duração).

### Nome único entre os ativos
- Dois planos **ativos** não podem ter o mesmo nome: "Mensal", "MENSAL" e
  "Mênsal" são o mesmo (409). Um plano inativo antigo pode repetir o nome de
  um novo; reativá-lo, nesse caso, dá 409.
- A regra mora no banco: um índice único parcial (`WHERE ativo`) sobre a função
  `nome_normalizado` (V10), que envolve o `unaccent` fixando o dicionário. O
  `unaccent` puro não pode ir num índice, porque não é `IMMUTABLE`.
- A checagem no Java (mensagem clara) usa a **mesma função** do índice, para as
  duas nunca divergirem. Numa corrida (dois cadastros iguais ao mesmo tempo),
  quem barra é o índice, e o erro também vira 409.

### Quem pode o quê

| Operação | ADMIN | RECEPCAO | INSTRUTOR | ALUNO |
|---|:-:|:-:|:-:|:-:|
| Listar / consultar | ✓ | ✓ | ✓ | só ativos, resumido |
| Cadastrar / editar / inativar / reativar | ✓ | | | |

- Só o ADMIN altera: preço é decisão do dono ou do gerente. A recepção consulta
  para matricular.
- O aluno vê só os planos ativos (o `incluirInativos` é ignorado para ele), sem
  `ativo` nem `criadoEm`. Plano inativo pedido pelo id dá **404**, não 403:
  para o aluno, ele não existe.
- Edição com ETag/If-Match, como em alunos (ADR 0009). A classe que confere o
  `If-Match` passou para o módulo `compartilhado`.

## Consequências

- A recepção não consegue escolher, por engano, entre dois planos com o mesmo
  nome.
- A etapa 5 precisa copiar o valor do plano para a matrícula; sem isso, a regra
  "reajuste vale só para novas" não se cumpre.

## Lições registradas

- **Checar antes de alterar a entidade.** A primeira versão da edição alterava
  o plano e só depois conferia o nome. Como a conferência é uma consulta SQL
  nativa, o Hibernate grava as alterações pendentes antes dela (*auto-flush*), e
  o índice único estourava dentro da própria checagem: 500 em vez de 409. O
  teste pegou; a conferência agora vem antes.
