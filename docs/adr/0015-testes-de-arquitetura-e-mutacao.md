# ADR 0015 — Testes de arquitetura (ArchUnit) e de mutação (PIT)

- **Status:** Aceito; portas de `alunos` e `planos` ampliadas no [ADR 0017](0017-matriculas-decisoes-da-implementacao.md)
- **Data:** 2026-10-17

## Contexto

Duas perguntas que os testes comuns não respondem:

1. As fronteiras entre módulos (ADR 0002) e o domínio em Java puro (ADR 0004)
   continuam valendo? Eram convenção: um `import` errado passaria sem nenhum
   teste reclamar.
2. Os testes de fato pegam erros? Cobertura de linha só diz que o código rodou,
   não que algum teste falharia se ele estivesse errado.

## Decisão

### ArchUnit (roda em todo build)
`ArquiteturaTest`, com 9 regras sobre o código de produção:

- módulos sem ciclos entre si;
- `compartilhado` não depende de nenhum módulo; `auth` não depende de nenhum
  outro módulo (os outros dependem dele);
- **porta pública**: de fora de um módulo, só se usam as classes listadas como
  porta pública dele. Hoje, `auth` expõe `LoginsDeAlunos`, `ClaimsDoToken`,
  `CredenciaisTemporarias` e `RecuperacaoDeAdmin`; `alunos`, `planos` e
  `cobranca` não expõem nada. Um módulo novo que precise de outro ganha uma
  porta pública explícita, decidida e revisada;
- `cobranca` só depende de Java puro;
- entidades JPA não dependem do Spring nem de controllers, services ou
  repositórios.

Escolhido em vez do Spring Modulith porque ele assume que o interno de cada
módulo fica em subpacotes, e os módulos daqui são planos: adotá-lo exigiria
reorganizar todos os pacotes.

### PIT (sob demanda)
Perfil Maven `mutacao`, nas regras de domínio, com os testes de unidade:

```bash
./mvnw -P mutacao test-compile org.pitest:pitest-maven:mutationCoverage
# relatório: target/pit-reports/index.html
```

Não roda no CI: leva mais tempo e serve para revisar a qualidade dos testes
quando o domínio muda. A primeira rodada achou 22 mutações sobreviventes,
inclusive uma regra do CPF sem teste de verdade; hoje não sobra nenhuma.

## Consequências

- Uma violação de fronteira quebra o build e diz qual regra e qual classe.
  Cada regra foi validada cometendo a violação de propósito.
- Ficou de fora, conscientemente, a regra "controller não acessa repositório
  direto": dois controllers do `auth` fazem isso hoje para consultas simples.
