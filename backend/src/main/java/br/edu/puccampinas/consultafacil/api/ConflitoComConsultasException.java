package br.edu.puccampinas.consultafacil.api;

import br.edu.puccampinas.consultafacil.api.dto.ConflitoConsultasResponse;
import br.edu.puccampinas.consultafacil.api.dto.ConsultaCalendarioResponse;

import java.util.List;

/**
 * 409 com a lista das consultas ativas afetadas (#44). O cliente mostra a lista e pode
 * repetir a operação com ?confirmar=true. Os handlers de agenda e bloqueios devolvem o corpo
 * completo; em qualquer outro lugar vira um 409 comum pelo Router.
 */
public class ConflitoComConsultasException extends ApiException {
    private final List<ConsultaCalendarioResponse> consultas;

    public ConflitoComConsultasException(String mensagem, List<ConsultaCalendarioResponse> consultas) {
        super(409, mensagem);
        this.consultas = List.copyOf(consultas);
    }

    public List<ConsultaCalendarioResponse> consultas() { return consultas; }

    public ConflitoConsultasResponse resposta() { return new ConflitoConsultasResponse(409, getMessage(), consultas); }
}
