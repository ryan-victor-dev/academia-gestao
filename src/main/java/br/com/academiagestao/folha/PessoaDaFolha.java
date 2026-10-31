package br.com.academiagestao.folha;

import java.util.UUID;

/**
 * Uma pessoa na folha (ADR 0049). {@code id} nulo: alguém da equipe com login cujo vínculo o ADMIN ainda não
 * definiu (aparece para ele definir). {@code usuarioId} nulo: quem não tem login. {@code perfil}: o do login (ADMIN,
 * RECEPCAO ou INSTRUTOR), ou nulo sem login. A situação de quem tem login é a do login.
 */
record PessoaDaFolha(UUID id, UUID usuarioId, String nome, String cpf, String perfil, Combinado combinado,
                     boolean ativo, long versao) {

    boolean temLogin() {
        return usuarioId != null;
    }
}
