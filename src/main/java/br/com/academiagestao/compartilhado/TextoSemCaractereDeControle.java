package br.com.academiagestao.compartilhado;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.jdk.StringDeserializer;

/**
 * Todo texto do JSON passa por aqui: com caractere de controle, 400 em vez de 500 do banco
 * (ver {@link CaractereDeControleException}). O nome do campo vem do caminho no JSON, como
 * "divisoes[0].exercicios[1].carga".
 */
@JacksonComponent
class TextoSemCaractereDeControle extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        String valor = StringDeserializer.instance.deserialize(p, ctxt);
        CaractereDeControleException.conferir(campo(p), valor);
        return valor;
    }

    private static String campo(JsonParser p) {
        StringBuilder campo = new StringBuilder();
        for (String parte : p.streamReadContext().pathAsPointer().toString().split("/")) {
            if (parte.isEmpty()) {
                continue;
            }
            if (parte.chars().allMatch(Character::isDigit)) {
                campo.append('[').append(parte).append(']');
            } else {
                campo.append(campo.isEmpty() ? "" : ".").append(parte);
            }
        }
        return campo.isEmpty() ? "texto" : campo.toString();
    }
}
