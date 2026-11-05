package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.dto.PacienteRequest;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.domain.model.Paciente;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import org.bson.types.ObjectId;
import java.util.List;
import java.util.Optional;

public class PacienteService {
    private final PacienteRepository repository;
    private final PacienteValidator validator;

    public PacienteService(PacienteRepository repository, PacienteValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public Paciente cadastrar(PacienteRequest request) {
        var dados = validator.validar(request);
        validarUnicidade(dados, null);
        return repository.cadastrar(new Paciente(new ObjectId().toHexString(), dados.nome(), dados.cpf(), dados.email()));
    }

    // PUT substitui os três campos editáveis; todos continuam obrigatórios.
    public Optional<Paciente> atualizar(String id, PacienteRequest request) {
        id = validarId(id);
        var dados = validator.validar(request);
        if (repository.consultar(id).isEmpty()) return Optional.empty();
        validarUnicidade(dados, id);
        return repository.atualizar(new Paciente(id, dados.nome(), dados.cpf(), dados.email()));
    }

    public Optional<Paciente> consultar(String id) { return repository.consultar(validarId(id)); }
    public List<Paciente> listar(String busca) { return repository.listar(busca == null ? "" : busca.strip()); }

    private String validarId(String id) {
        if (id == null || !ObjectId.isValid(id)) throw new ValidacaoException("ID do paciente inválido.");
        return new ObjectId(id).toHexString();
    }

    private void validarUnicidade(PacienteRequest dados, String excetoId) {
        if (repository.cpfEmUso(dados.cpf(), excetoId)) throw new ValidacaoException("CPF já cadastrado para outro paciente.");
        if (repository.emailEmUso(dados.email(), excetoId)) throw new ValidacaoException("E-mail já cadastrado para outro paciente.");
    }
}
