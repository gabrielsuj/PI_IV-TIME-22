package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.Optional;

/**
 * Quem pode ver e mexer em qual consulta (#27, #30). Uma regra só para listagem, detalhe e cancelamento:
 * paciente → as próprias; médico → as que ele atende; secretaria → todas.
 */
public class AcessoConsulta {

    /** Busca o cadastro (paciente ou médico) ligado a um login. */
    @FunctionalInterface
    public interface VinculoDoUsuario {
        Optional<ObjectId> buscar(String usuarioId);
    }

    private final VinculoDoUsuario pacienteDoUsuario;
    private final VinculoDoUsuario medicoDoUsuario;

    public AcessoConsulta(VinculoDoUsuario pacienteDoUsuario, VinculoDoUsuario medicoDoUsuario) {
        this.pacienteDoUsuario = pacienteDoUsuario;
        this.medicoDoUsuario = medicoDoUsuario;
    }

    /** 403 se o ator não pode acessar esta consulta. */
    public void verificar(Document consulta, UsuarioLogado ator) {
        exigirLogin(ator);
        switch (ator.perfil()) {
            case SECRETARIA_ADMIN -> { }
            case PACIENTE -> {
                if (!pacienteDe(ator).equals(consulta.getObjectId("pacienteId")))
                    throw ApiException.forbidden("O paciente só pode acessar as próprias consultas");
            }
            case MEDICO -> {
                if (!medicoDe(ator).equals(consulta.getObjectId("medicoId")))
                    throw ApiException.forbidden("O médico só pode acessar as próprias consultas");
            }
        }
    }

    /** Filtro que limita uma listagem ao que o ator pode ver; null para a secretaria (sem limite). */
    public Bson restricao(UsuarioLogado ator) {
        exigirLogin(ator);
        return switch (ator.perfil()) {
            case SECRETARIA_ADMIN -> null;
            case PACIENTE -> Filters.eq("pacienteId", pacienteDe(ator));
            case MEDICO -> Filters.eq("medicoId", medicoDe(ator));
        };
    }

    private static void exigirLogin(UsuarioLogado ator) {
        if (ator == null || ator.perfil() == null) throw ApiException.unauthorized("Faça login para acessar as consultas");
    }

    private ObjectId pacienteDe(UsuarioLogado ator) {
        return pacienteDoUsuario.buscar(ator.id()).orElseThrow(() ->
                ApiException.forbidden("Seu login não está ligado a um cadastro de paciente. Procure a secretaria."));
    }

    private ObjectId medicoDe(UsuarioLogado ator) {
        return medicoDoUsuario.buscar(ator.id()).orElseThrow(() ->
                ApiException.forbidden("Seu login não está ligado a um cadastro de médico. Procure a secretaria."));
    }
}
