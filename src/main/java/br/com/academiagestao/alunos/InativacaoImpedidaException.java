package br.com.academiagestao.alunos;

class InativacaoImpedidaException extends RuntimeException {

    InativacaoImpedidaException(String motivo) {
        super(motivo);
    }
}
