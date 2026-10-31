package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PESSOA_DA_FOLHA;

/**
 * Quem recebe pela folha e o combinado com cada um (ADR 0049). Quem chama já conferiu que é o ADMIN. Cada alteração
 * vai para a trilha, com o valor combinado (a trilha também é só do ADMIN).
 */
@Service
class FolhaService {

    private final PessoasDaFolha pessoas;
    private final Auditoria auditoria;
    private final Clock relogio;

    FolhaService(PessoasDaFolha pessoas, Auditoria auditoria, Clock relogio) {
        this.pessoas = pessoas;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    List<PessoaDaFolha> listar(boolean incluirInativos) {
        return pessoas.listar(incluirInativos);
    }

    @Transactional(readOnly = true)
    PessoaDaFolha buscar(UUID id) {
        return pessoas.buscar(id).orElseThrow(() -> new PessoaNaoEncontradaException("Pessoa da folha não encontrada"));
    }

    /** Alguém da equipe com login entra na folha: o nome, o CPF e a situação continuam os do login. */
    @Transactional
    PessoaDaFolha incluirLogin(UUID usuarioId, Combinado combinado) {
        pessoas.travarLoginDaEquipe(usuarioId)
                .orElseThrow(() -> new PessoaNaoEncontradaException("Login da equipe não encontrado"));
        UUID id = UUID.randomUUID();
        // Quem barra o repetido é o índice único (também na corrida de dois pedidos ao mesmo tempo).
        try {
            pessoas.inserirComLogin(id, usuarioId, combinado, relogio.instant());
        } catch (DuplicateKeyException e) {
            throw new PessoaRepetidaException("Essa pessoa da equipe já está na folha: edite o combinado dela");
        }
        auditoria.registrar(PESSOA_DA_FOLHA, id, "INCLUIDA", Map.of(), retrato(usuarioId, null, combinado, true));
        return buscar(id);
    }

    /** Quem trabalha na academia sem usar o sistema: só para a folha, sem acesso nenhum. */
    @Transactional
    PessoaDaFolha cadastrarSemLogin(SemLogin dados, Combinado combinado) {
        conferirCpf(dados.cpf());
        UUID id = UUID.randomUUID();
        try {
            pessoas.inserirSemLogin(id, dados, combinado, relogio.instant());
        } catch (DuplicateKeyException e) {
            throw new PessoaRepetidaException("Esse CPF já está na folha");
        }
        auditoria.registrar(PESSOA_DA_FOLHA, id, "INCLUIDA", Map.of(), retrato(null, dados, combinado, true));
        return buscar(id);
    }

    /**
     * Quem tem login muda só o combinado ({@code nome}, {@code cpf} e {@code ativo} nulos): o resto é do login, na aba
     * Equipe. Quem não tem muda tudo; {@code ativo} nulo mantém a situação.
     */
    @Transactional
    PessoaDaFolha editar(UUID id, String ifMatch, String nome, String cpf, Combinado combinado, Boolean ativo) {
        PessoaDaFolha antes = pessoas.travar(id)
                .orElseThrow(() -> new PessoaNaoEncontradaException("Pessoa da folha não encontrada"));
        VersaoEsperada.conferir(ifMatch, antes.versao(), "a pessoa da folha");
        if (antes.temLogin()) {
            if (nome != null || cpf != null || ativo != null) {
                throw new DadosInvalidosException("nome: o nome, o CPF e a situação de quem tem login mudam na aba Equipe");
            }
            pessoas.atualizarCombinado(id, combinado);
            auditoria.registrar(PESSOA_DA_FOLHA, id, "EDITADA", retrato(antes.usuarioId(), null, antes.combinado(), true),
                    retrato(antes.usuarioId(), null, combinado, true));
            return buscar(id);
        }
        SemLogin dados = new SemLogin(nome, cpf);
        conferirCpf(dados.cpf());
        boolean ficaAtivo = ativo == null ? antes.ativo() : ativo;
        try {
            pessoas.atualizarSemLogin(id, dados, combinado, ficaAtivo);
        } catch (DuplicateKeyException e) {
            throw new PessoaRepetidaException("Esse CPF já está na folha");
        }
        auditoria.registrar(PESSOA_DA_FOLHA, id, "EDITADA",
                retrato(null, new SemLogin(antes.nome(), antes.cpf()), antes.combinado(), antes.ativo()),
                retrato(null, dados, combinado, ficaAtivo));
        return buscar(id);
    }

    /**
     * O CPF de alguém da equipe com login fica de fora (essa pessoa entra pelo login). O repetido na folha, quem barra é
     * o índice único, também na corrida (o {@code DuplicateKeyException} de quem chama).
     */
    private void conferirCpf(String cpf) {
        if (pessoas.cpfDeLoginDaEquipe(cpf)) {
            throw new PessoaRepetidaException("Esse CPF é de alguém da equipe com login: inclua a pessoa pelo login");
        }
    }

    private static Map<String, Object> retrato(UUID usuarioId, SemLogin dados, Combinado combinado, boolean ativo) {
        return Auditoria.campos("usuarioId", usuarioId, "nome", dados == null ? null : dados.nome(),
                "cpf", dados == null ? null : dados.cpf(), "vinculo", combinado.vinculo(),
                "valorCombinado", combinado.valor(), "ativo", ativo);
    }
}
