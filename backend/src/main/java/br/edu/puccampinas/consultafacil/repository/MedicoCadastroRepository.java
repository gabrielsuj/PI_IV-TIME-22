package br.edu.puccampinas.consultafacil.repository;

import br.edu.puccampinas.consultafacil.api.dto.MedicoResponse;
import org.bson.types.ObjectId;
import java.util.List;
import java.util.Optional;

/** Contrato do cadastro, testável sem MongoDB. Agenda continua usando MedicoRepository. */
public interface MedicoCadastroRepository {
    MedicoResponse cadastrar(MedicoResponse medico);
    Optional<MedicoResponse> atualizar(MedicoResponse medico);
    Optional<MedicoResponse> consultar(ObjectId id);
    List<MedicoResponse> listar();
    boolean existeAtivoComEspecialidade(ObjectId especialidadeId);
}
