package br.com.academiagestao.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O nome de quem é da equipe, para as listas que mostram quem registrou cada coisa (hoje, os
 * lançamentos do dia no caixa, ADR 0046, item 10). Só o nome, nunca o login nem o e-mail.
 */
@Service
public class NomesDaEquipe {

    private final UsuarioRepository repository;

    NomesDaEquipe(UsuarioRepository repository) {
        this.repository = repository;
    }

    /** O nome de cada usuário entre {@code ids}; inexistentes ficam de fora. */
    @Transactional(readOnly = true)
    public Map<UUID, String> nomes(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
    }
}
