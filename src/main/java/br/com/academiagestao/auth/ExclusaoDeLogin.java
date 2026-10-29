package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.ExclusaoRecusadaException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Exclui o login que nunca foi usado (ADR 0043): o do aluno excluído e o da equipe. Usado é o que
 * entrou ou abriu um cadastro (registro de acessos), é autor na trilha, ou para o qual alguma
 * tabela aponta (caixa, pagamento, ficha...). Os dois primeiros guardam só o id, sem chave
 * estrangeira, e mostram o nome pelo cadastro: o nome sumiria. As sessões saem em cascata, e os
 * tokens caem (a validação não acha mais o usuário).
 */
@Component
class ExclusaoDeLogin {

    private final UsuarioRepository repository;
    private final TrilhaDeUsuarios trilha;

    ExclusaoDeLogin(UsuarioRepository repository, TrilhaDeUsuarios trilha) {
        this.repository = repository;
        this.trilha = trilha;
    }

    /** Recusa com o título e o detalhe de quem pede (409), se o login já foi usado. */
    @Transactional(propagation = Propagation.MANDATORY)
    void excluir(Usuario usuario, String titulo, String detalhe) {
        if (repository.foiUsado(usuario.getId())) {
            throw new ExclusaoRecusadaException(titulo, detalhe);
        }
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        try {
            repository.delete(usuario);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            if (!ExclusaoRecusadaException.temHistorico(e)) {
                throw e;
            }
            throw new ExclusaoRecusadaException(titulo, detalhe);
        }
        trilha.excluido(usuario, antes);
    }
}
