package br.com.academiagestao.compartilhado;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Formato das respostas paginadas da API. Um record próprio, em vez de serializar o
 * Page do Spring Data, mantém o JSON estável mesmo se a biblioteca mudar.
 */
public record Pagina<T>(List<T> conteudo, int pagina, int tamanho, long totalElementos, int totalPaginas) {

    public static <E, T> Pagina<T> de(Page<E> page, Function<E, T> conversor) {
        return new Pagina<>(page.getContent().stream().map(conversor).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
