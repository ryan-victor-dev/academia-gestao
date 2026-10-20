package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.ConfiguracaoDaCobrancaRepository.ConfiguracaoDaCobranca;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.CONFIGURACAO;

@Service
class ConfiguracaoDaCobrancaService {

    /** A trilha pede um id; a configuração é uma só. */
    static final UUID ID_NA_TRILHA = UUID.nameUUIDFromBytes("configuracao-cobranca".getBytes(StandardCharsets.UTF_8));

    private final ConfiguracaoDaCobrancaRepository repository;
    private final Auditoria auditoria;

    ConfiguracaoDaCobrancaService(ConfiguracaoDaCobrancaRepository repository, Auditoria auditoria) {
        this.repository = repository;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    ConfiguracaoDaCobranca ler() {
        return repository.ler();
    }

    /** Vale para todas as cobranças a partir de agora (decisão do dono, ADR 0024). */
    @Transactional
    ConfiguracaoDaCobranca alterar(RegrasDeAtraso novas, String ifMatch) {
        ConfiguracaoDaCobranca atual = repository.travar();
        VersaoEsperada.conferir(ifMatch, atual.versao(), "a configuração da cobrança");
        ConfiguracaoDaCobranca gravada = repository.gravar(novas);
        auditoria.registrar(CONFIGURACAO, ID_NA_TRILHA, "EDITADA", retrato(atual.regras()), retrato(gravada.regras()));
        return gravada;
    }

    private static Map<String, Object> retrato(RegrasDeAtraso regras) {
        return Auditoria.campos("tipoAcrescimo", regras.tipoAcrescimo().name(), "acrescimo", regras.acrescimo(),
                "jurosAoMes", regras.jurosAoMes(), "diasParaBloquear", regras.diasParaBloquear());
    }
}
