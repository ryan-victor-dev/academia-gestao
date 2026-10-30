package br.com.academiagestao.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * O e-mail de quem é da equipe, para os avisos da academia (ADR 0047, decisão 8): só os ativos e só a equipe (o
 * aluno é avisado pelo cadastro dele). A equipe não tem telefone no sistema: o aviso dela é só por e-mail.
 */
@Service
public class ContatosDaEquipe {

    public record ContatoDaEquipe(UUID id, String nome, String email) {
    }

    private final UsuarioRepository repository;

    ContatosDaEquipe(UsuarioRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ContatoDaEquipe> ativos() {
        return repository.findByPerfilNot(Perfil.ALUNO, org.springframework.data.domain.Sort.by("nome")).stream()
                .filter(Usuario::isAtivo).map(u -> new ContatoDaEquipe(u.getId(), u.getNome(), u.getEmail())).toList();
    }

    /** Os de {@code ids} que ainda são da equipe e estão ativos. */
    @Transactional(readOnly = true)
    public List<ContatoDaEquipe> ativosEntre(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().filter(u -> u.isAtivo() && u.getPerfil() != Perfil.ALUNO)
                .map(u -> new ContatoDaEquipe(u.getId(), u.getNome(), u.getEmail())).toList();
    }
}
