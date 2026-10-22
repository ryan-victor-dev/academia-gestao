package br.com.academiagestao.compartilhado;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;

/**
 * Parâmetros e cabeçalhos de texto (ex.: ?busca= dos alunos) com caractere de controle: 400
 * em vez de 500 do banco (ver {@link CaractereDeControleException}).
 */
@ControllerAdvice
@ConditionalOnWebApplication
class TextoNaUrlSemCaractereDeControle {

    @InitBinder
    void conferirTextos(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String texto) {
                CaractereDeControleException.conferir(binder.getObjectName(), texto);
                setValue(texto);
            }
        });
    }
}
