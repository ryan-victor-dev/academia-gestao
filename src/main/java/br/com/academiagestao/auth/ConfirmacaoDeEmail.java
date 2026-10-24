package br.com.academiagestao.auth;

import br.com.academiagestao.auth.CodigosDeEmail.Finalidade;
import br.com.academiagestao.auth.CodigosDeEmail.Resultado;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.EmailDoAlunoCorrigido;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * A confirmação do e-mail no primeiro acesso (ADR 0038): aluno e equipe recebem um código de 6
 * dígitos e só usam o resto do sistema depois de confirmar. O ADMIN fica de fora, e com o e-mail
 * desligado ninguém precisa confirmar. O envio vai fora de transação (rede).
 */
@Service
class ConfirmacaoDeEmail {

    private final UsuarioRepository repository;
    private final CodigosDeEmail codigos;
    private final Correio correio;
    private final TrilhaDeUsuarios trilha;
    private final ApplicationEventPublisher eventos;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    ConfirmacaoDeEmail(UsuarioRepository repository, CodigosDeEmail codigos, Correio correio, TrilhaDeUsuarios trilha,
                       ApplicationEventPublisher eventos, PlatformTransactionManager transacoes, Clock relogio) {
        this.repository = repository;
        this.codigos = codigos;
        this.correio = correio;
        this.trilha = trilha;
        this.eventos = eventos;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
    }

    /** Precisa confirmar: e-mail ligado, não é ADMIN, e ainda não confirmou. */
    static boolean exigida(Usuario usuario, boolean correioLigado) {
        return correioLigado && usuario.getPerfil() != Perfil.ADMIN && usuario.getEmailConfirmadoEm() == null;
    }

    boolean exigida(Usuario usuario) {
        return exigida(usuario, correio.ligado());
    }

    /** Manda (de novo) o código para o e-mail do login. */
    void enviarCodigo(UUID usuarioId) {
        Envio envio = transacao.execute(t -> {
            Usuario usuario = pendente(usuarioId);
            return new Envio(usuario, codigos.emitir(usuario.getId(), Finalidade.CONFIRMACAO, usuario.getEmail()));
        });
        enviar(envio);
    }

    /** O código confere: o e-mail fica confirmado. A tentativa errada fica contada. */
    void confirmar(UUID usuarioId, String codigo) {
        Usuario lido = pendente(usuarioId);
        if (codigos.conferir(usuarioId, Finalidade.CONFIRMACAO, lido.getEmail(), codigo) != Resultado.CONFERE) {
            throw new CodigoInvalidoException();
        }
        transacao.executeWithoutResult(t -> {
            Usuario usuario = pendente(usuarioId);
            usuario.confirmarEmail(lido.getEmail(), relogio.instant());
            trilha.ato(usuario, "EMAIL_CONFIRMADO");
        });
    }

    /**
     * O e-mail estava errado: o usuário corrige e recebe o código no novo. No aluno, o cadastro
     * acompanha (evento para o módulo de alunos, na mesma transação).
     */
    void corrigir(UUID usuarioId, String novoEmail) {
        String normalizado = Usuario.normalizarEmail(novoEmail);
        Envio envio = transacao.execute(t -> {
            Usuario usuario = pendente(usuarioId);
            if (!normalizado.equals(usuario.getEmail())) {
                // Antes de alterar a entidade: a consulta dispara o flush do Hibernate (CLAUDE.md).
                if (repository.existsByEmail(normalizado)) {
                    throw new LoginJaExisteException("Este e-mail já é usado por outro login");
                }
                Map<String, Object> antes = Auditoria.campos("email", usuario.getEmail());
                usuario.corrigirEmail(normalizado);
                trilha.alterado(usuario, "EMAIL_CORRIGIDO", antes);
                if (usuario.getAlunoId() != null) {
                    eventos.publishEvent(new EmailDoAlunoCorrigido(usuario.getAlunoId(), normalizado));
                }
            }
            return new Envio(usuario, codigos.emitir(usuario.getId(), Finalidade.CONFIRMACAO, normalizado));
        });
        enviar(envio);
    }

    private Usuario pendente(UUID usuarioId) {
        Usuario usuario = repository.findById(usuarioId).orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));
        if (!exigida(usuario)) {
            throw new OperacaoNaoPermitidaException("Não há e-mail a confirmar");
        }
        return usuario;
    }

    private void enviar(Envio envio) {
        correio.enviar(new Correio.Mensagem(envio.email(), "Confirme seu e-mail na academia",
                "Olá, " + envio.nome() + "!\n\nSeu código para confirmar o e-mail é: " + envio.codigo()
                        + "\n\nEle vale 15 minutos. Se você não pediu, ignore este e-mail."));
    }

    private record Envio(String email, String nome, String codigo) {
        Envio(Usuario usuario, String codigo) {
            this(usuario.getEmail(), usuario.getNome(), codigo);
        }
    }
}
