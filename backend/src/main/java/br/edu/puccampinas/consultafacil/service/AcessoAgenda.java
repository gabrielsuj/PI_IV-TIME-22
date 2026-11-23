/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.repository.MedicoDoUsuarioRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import org.bson.types.ObjectId;

import java.util.Optional;

/** Escopo de acesso à jornada, aos bloqueios e ao calendário operacional. */
public final class AcessoAgenda {
    private final MedicoDoUsuarioRepository medicos;

    public AcessoAgenda(MedicoDoUsuarioRepository medicos) { this.medicos = medicos; }

    /** A secretaria tem acesso a todos. Um médico sem cadastro vinculado recebe 403. */
    public Optional<ObjectId> restricao(UsuarioLogado ator) {
        if (ator == null) throw ApiException.unauthorized("Faça login para acessar a agenda");
        return switch (ator.perfil()) {
            case SECRETARIA_ADMIN -> Optional.empty();
            case PACIENTE -> throw ApiException.forbidden("Seu perfil não tem acesso à agenda");
            case MEDICO -> Optional.of(medicos.buscar(ator.id()).orElseThrow(() ->
                    ApiException.forbidden("Seu login não está ligado a um cadastro de médico. Procure a secretaria.")));
        };
    }

    public ObjectId medicoAutorizado(UsuarioLogado ator, String solicitado) {
        Optional<ObjectId> proprio = restricao(ator);
        if (proprio.isPresent() && "meu".equals(solicitado)) return proprio.get();
        ObjectId id = Query.objectId(solicitado, "medicoId");
        if (proprio.isPresent() && !proprio.get().equals(id))
            throw ApiException.forbidden("O médico só pode acessar a própria agenda");
        return id;
    }
}
