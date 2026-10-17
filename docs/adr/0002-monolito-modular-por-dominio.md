# ADR 0002 — Monolito modular com pacotes por domínio

- **Status:** Aceito; fronteiras verificadas por testes desde o [ADR 0015](0015-testes-de-arquitetura-e-mutacao.md)
- **Data:** 2026-10-16

## Contexto

O sistema tem áreas bem distintas: alunos, planos, matrículas, cobrança,
treinos, autenticação e auditoria. A equipe é uma pessoa, o cliente é uma
academia só, e o servidor é modesto.

Opções consideradas:

1. **Pacotes por camada** (`controller/`, `service/`, `repository/`): simples no
   começo, mas tudo de todos os domínios fica misturado e o acoplamento cresce
   sem ninguém perceber.
2. **Microsserviços**: isolamento forte, mas exige deploy, rede, observabilidade
   e consistência distribuída — complexidade que este projeto não paga.
3. **Monolito modular**: um único deploy, com o código organizado por domínio.

## Decisão

Monolito modular. Cada domínio é um pacote de topo em `br.com.academiagestao`
(`alunos`, `planos`, `matriculas`, `cobranca`, `treinos`, `auth`, `auditoria`)
e o que é realmente comum fica em `compartilhado`.

Regra de convivência: um módulo não acessa as tabelas nem as classes internas
de outro; conversa por classes públicas expostas de propósito.

## Consequências

- Um só `.jar`, um só banco, um só pipeline — fácil de rodar e de manter.
- As fronteiras existem no código desde o início; se um módulo precisar virar
  serviço no futuro, o corte já está desenhado.
- Por enquanto as fronteiras são combinadas, não verificadas. Um próximo passo
  natural é verificá-las com Spring Modulith ou ArchUnit em teste.
