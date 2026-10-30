package br.com.academiagestao.auth;

/** Todas as vagas do BCrypt ocupadas, mesmo depois da espera (ADR 0046, item 3): 503 com Retry-After. */
class MuitasSenhasAoMesmoTempoException extends RuntimeException {

    MuitasSenhasAoMesmoTempoException() {
        super("Muitos acessos ao mesmo tempo. Tente de novo em alguns segundos.");
    }
}
