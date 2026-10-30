package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PUBLICACAO;

/**
 * Os anexos das publicações (ADR 0047, decisão 6). O envio chega em pedaços de 1 MB, cada um gravado numa transação
 * curta (um envio lento pela rede não segura uma conexão do banco, ADR 0020), com o anexo em ENVIANDO; no fim, numa
 * transação, ele vira PRONTO e entra na trilha. Qualquer falha no meio apaga o que chegou. Tetos: poucos envios ao
 * mesmo tempo (cada um ocupa 1 MB de memória e uma linha de escrita no banco), 10 anexos por publicação e um
 * espaço total, para os anexos nunca encherem o disco do banco.
 */
@Service
class AnexoService {

    static final int TAMANHO_DA_PARTE = 1024 * 1024;
    static final int MAXIMO_POR_PUBLICACAO = 10;
    static final int TAMANHO_MAXIMO_NOME = 200;
    /** Um envio parado há mais que isto foi interrompido (o maior, de 50 MB, leva minutos numa rede ruim). */
    static final Duration ENVIO_PARADO = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(AnexoService.class);

    private final Anexos anexos;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Auditoria auditoria;
    private final Clock relogio;
    private final Semaphore envios;
    private final long espacoTotal;

    AnexoService(Anexos anexos, JdbcTemplate jdbc, PlatformTransactionManager transacoes, Auditoria auditoria, Clock relogio,
                 @Value("${academia.anexos.envios-ao-mesmo-tempo:3}") int enviosAoMesmoTempo,
                 @Value("${academia.anexos.espaco-total:5GB}") DataSize espacoTotal) {
        this.anexos = anexos;
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(transacoes);
        this.auditoria = auditoria;
        this.relogio = relogio;
        this.envios = new Semaphore(enviosAoMesmoTempo);
        this.espacoTotal = espacoTotal.toBytes();
    }

    /** Recebe um arquivo para a publicação. Sem transação: cada passo abre a sua, curta. */
    Anexos.Anexo receber(UUID publicacaoId, String nome, InputStream conteudo, UUID autor) throws IOException {
        String nomeLimpo = nomeDoArquivo(nome);
        if (!envios.tryAcquire()) {
            throw new AnexoRecusadoException(HttpStatus.SERVICE_UNAVAILABLE, "Muitos envios ao mesmo tempo",
                    "Outros arquivos estão sendo enviados agora; tente de novo em instantes");
        }
        try {
            garantirQueCabeNaPublicacao(publicacaoId);
            byte[] primeira = conteudo.readNBytes(TAMANHO_DA_PARTE);
            if (primeira.length == 0) {
                throw new AnexoRecusadoException(HttpStatus.BAD_REQUEST, "Arquivo vazio", "O arquivo está vazio");
            }
            TipoDeArquivo tipo = TipoDeArquivo.reconhecer(primeira).orElseThrow(() -> new AnexoRecusadoException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de arquivo não aceito",
                    "Envie foto (JPEG, PNG, WebP ou GIF), vídeo (MP4 ou WebM) ou documento PDF"));
            // O espaço livre é medido no começo e conferido a cada pedaço. Envios ao mesmo tempo medem o mesmo espaço
            // livre e podem passar juntos do teto, no máximo pelo que cabe no teto de envios ao mesmo tempo.
            long livre = espacoTotal - anexos.totalGuardado();
            UUID id = UUID.randomUUID();
            if (primeira.length > livre) {
                throw espacoEsgotado();
            }
            anexos.criarEnviando(id, publicacaoId, tipo, nomeLimpo, relogio.instant(), autor);
            try {
                long tamanho = gravarPartes(id, tipo, primeira, conteudo, livre);
                transacao.executeWithoutResult(t -> concluir(publicacaoId, id, tipo, nomeLimpo, tamanho));
                return new Anexos.Anexo(id, publicacaoId, tipo, nomeLimpo, tamanho);
            } catch (DataIntegrityViolationException e) {
                // A publicação foi excluída no meio do envio: a cascata levou o anexo, e o pedaço seguinte esbarrou
                // na chave estrangeira. É um 404, e não um erro nosso.
                anexos.excluir(id);
                if (jdbc.queryForList("SELECT 1 FROM publicacoes WHERE id = ?", Integer.class, publicacaoId).isEmpty()) {
                    throw new PublicacaoNaoEncontradaException();
                }
                throw e;
            } catch (RuntimeException | IOException e) {
                anexos.excluir(id);
                throw e;
            }
        } finally {
            envios.release();
        }
    }

    private long gravarPartes(UUID id, TipoDeArquivo tipo, byte[] primeira, InputStream conteudo, long livre) throws IOException {
        long tamanho = 0;
        int ordem = 0;
        for (byte[] parte = primeira; parte.length > 0; parte = conteudo.readNBytes(TAMANHO_DA_PARTE)) {
            tamanho += parte.length;
            if (tamanho > tipo.grupo().tamanhoMaximo()) {
                throw new AnexoRecusadoException(HttpStatus.CONTENT_TOO_LARGE, "Arquivo grande demais",
                        "O limite é de " + tipo.grupo().tamanhoMaximo() / TAMANHO_DA_PARTE + " MB para "
                                + nomeDoGrupo(tipo.grupo()));
            }
            if (tamanho > livre) {
                throw espacoEsgotado();
            }
            anexos.gravarParte(id, ordem++, parte);
        }
        return tamanho;
    }

    /**
     * A publicação trava (FOR UPDATE) e a contagem é refeita: dois envios ao mesmo tempo na mesma publicação não
     * passam juntos do teto, e uma exclusão no meio espera (ou acontece antes, e o envio é recusado).
     */
    private void concluir(UUID publicacaoId, UUID id, TipoDeArquivo tipo, String nome, long tamanho) {
        if (jdbc.queryForList("SELECT 1 FROM publicacoes WHERE id = ? FOR UPDATE", Integer.class, publicacaoId).isEmpty()) {
            throw new PublicacaoNaoEncontradaException();
        }
        if (anexos.prontosDaPublicacao(publicacaoId) >= MAXIMO_POR_PUBLICACAO) {
            throw publicacaoCheia();
        }
        anexos.concluir(id, tamanho);
        auditoria.registrar(PUBLICACAO, publicacaoId, "ANEXO_ADICIONADO", Map.of(),
                Auditoria.campos("anexo", id, "nome", nome, "tipo", tipo.conteudoTipo(), "tamanho", tamanho));
    }

    private void garantirQueCabeNaPublicacao(UUID publicacaoId) {
        if (jdbc.queryForList("SELECT 1 FROM publicacoes WHERE id = ?", Integer.class, publicacaoId).isEmpty()) {
            throw new PublicacaoNaoEncontradaException();
        }
        if (anexos.prontosDaPublicacao(publicacaoId) >= MAXIMO_POR_PUBLICACAO) {
            throw publicacaoCheia();
        }
    }

    void excluir(UUID publicacaoId, UUID anexoId) {
        transacao.executeWithoutResult(t -> {
            Anexos.Anexo anexo = anexos.pronto(anexoId).filter(a -> a.publicacaoId().equals(publicacaoId))
                    .orElseThrow(AnexoNaoEncontradoException::new);
            anexos.excluir(anexoId);
            auditoria.registrar(PUBLICACAO, publicacaoId, "ANEXO_EXCLUIDO",
                    Auditoria.campos("anexo", anexoId, "nome", anexo.nome(), "tipo", anexo.tipo().conteudoTipo()), Map.of());
        });
    }

    Anexos.Anexo pronto(UUID anexoId) {
        return anexos.pronto(anexoId).orElseThrow(AnexoNaoEncontradoException::new);
    }

    /** Lido sob demanda, pedaço a pedaço, enquanto a resposta é escrita. */
    org.springframework.core.io.Resource conteudo(Anexos.Anexo anexo) {
        return new ConteudoDoAnexo(anexos, anexo);
    }

    /** Envios interrompidos (a rede caiu, o navegador fechou) não ficam ocupando espaço. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    void limparEnviosParados() {
        int apagados = anexos.excluirEnviosParados(relogio.instant().minus(ENVIO_PARADO));
        if (apagados > 0) {
            log.info("{} envio(s) de anexo interrompido(s) apagado(s)", apagados);
        }
    }

    /** O nome que o arquivo tinha: só para mostrar e para o download. Sem caminho, sem caractere de controle. */
    static String nomeDoArquivo(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new DadosInvalidosException("X-Nome-Do-Arquivo: é obrigatório");
        }
        String semCaminho = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1).strip();
        if (semCaminho.isEmpty() || semCaminho.chars().anyMatch(Character::isISOControl)) {
            throw new DadosInvalidosException("X-Nome-Do-Arquivo: nome inválido");
        }
        if (semCaminho.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("X-Nome-Do-Arquivo: pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        return semCaminho;
    }

    private static AnexoRecusadoException espacoEsgotado() {
        return new AnexoRecusadoException(HttpStatus.INSUFFICIENT_STORAGE, "Espaço dos anexos esgotado",
                "O espaço para anexos acabou; exclua anexos antigos ou fale com o administrador do sistema");
    }

    private static AnexoRecusadoException publicacaoCheia() {
        return new AnexoRecusadoException(HttpStatus.CONFLICT, "Publicação com anexos demais",
                "Cada publicação tem no máximo " + MAXIMO_POR_PUBLICACAO + " anexos");
    }

    private static String nomeDoGrupo(TipoDeArquivo.Grupo grupo) {
        return switch (grupo) {
            case IMAGEM -> "fotos";
            case VIDEO -> "vídeos";
            case DOCUMENTO -> "documentos";
        };
    }
}
