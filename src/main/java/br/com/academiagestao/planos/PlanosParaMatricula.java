package br.com.academiagestao.planos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * O que o módulo de matrículas pode pedir ao de planos: as condições atuais de um plano.
 * A matrícula copia esses valores; um reajuste posterior não a afeta (ADR 0013).
 */
@Service
public class PlanosParaMatricula {

    private final PlanoRepository repository;

    PlanosParaMatricula(PlanoRepository repository) {
        this.repository = repository;
    }

    /**
     * Com o plano travado até o fim da matrícula (FOR SHARE): uma exclusão ao mesmo tempo
     * espera a matrícula (e é recusada, 409) ou é esperada por ela (e o plano não existe
     * mais). Sem a trava, a matrícula esbarraria na chave estrangeira (500). ADR 0043.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlanoOfertado> buscar(UUID planoId) {
        return repository.travarParaMatricula(planoId)
                .map(p -> new PlanoOfertado(p.getId(), p.getNome(), p.getValorMensal(), p.getDuracaoMeses(),
                        p.isFidelidade(), p.getPercentualMulta(), p.getFormaPagamento(), p.isAtivo()));
    }
}
