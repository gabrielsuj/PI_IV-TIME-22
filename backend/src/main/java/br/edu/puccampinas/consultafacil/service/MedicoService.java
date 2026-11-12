package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.MedicoRequest;
import br.edu.puccampinas.consultafacil.api.dto.MedicoResponse;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoCadastroRepository;
import org.bson.types.ObjectId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MedicoService {
    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private final MedicoCadastroRepository medicos;
    private final EspecialidadeRepository especialidades;

    public MedicoService(MedicoCadastroRepository medicos, EspecialidadeRepository especialidades) {
        this.medicos = medicos;
        this.especialidades = especialidades;
    }

    public List<MedicoResponse> listar() { return medicos.listar(); }

    public MedicoResponse consultar(String id) {
        return medicos.consultar(Query.objectId(id, "ID do médico"))
                .orElseThrow(() -> ApiException.notFound("Médico não encontrado"));
    }

    public MedicoResponse cadastrar(MedicoRequest request) {
        return medicos.cadastrar(validar(new ObjectId().toHexString(), request));
    }

    public MedicoResponse atualizar(String id, MedicoRequest request) {
        var atual = consultar(id);
        return medicos.atualizar(validar(atual.id(), request))
                .orElseThrow(() -> ApiException.notFound("Médico não encontrado"));
    }

    private MedicoResponse validar(String id, MedicoRequest r) {
        if (r == null) throw ApiException.badRequest("Dados do médico são obrigatórios");
        String nome = CadastroValidator.texto(r.nome(), "Nome", 3, 120);
        String numero = CadastroValidator.formato(r.crmNumero(), "Número do CRM", "[0-9]{1,10}");
        if (r.crmUf() == null || !UFS.contains(r.crmUf())) throw ApiException.badRequest("UF do CRM inválida");
        if (r.ativo() == null) throw ApiException.badRequest("ativo deve ser um booleano");
        if (r.especialidadeIds() == null || r.especialidadeIds().isEmpty())
            throw ApiException.badRequest("Informe pelo menos uma especialidade ativa");
        var ids = r.especialidadeIds().stream().map(v -> Query.objectId(v, "ID da especialidade")).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw ApiException.badRequest("Especialidades não podem se repetir");
        for (ObjectId especialidadeId : ids) {
            var especialidade = especialidades.consultar(especialidadeId);
            if (especialidade.isEmpty() || !especialidade.get().ativa())
                throw ApiException.badRequest("Especialidade inexistente ou inativa: " + especialidadeId.toHexString());
        }
        return new MedicoResponse(id, nome, numero, r.crmUf(), ids.stream().map(ObjectId::toHexString).toList(), r.ativo());
    }
}
