package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Query;
import br.edu.puccampinas.consultafacil.api.dto.EspecialidadeRequest;
import br.edu.puccampinas.consultafacil.api.dto.EspecialidadeResponse;
import br.edu.puccampinas.consultafacil.repository.EspecialidadeRepository;
import br.edu.puccampinas.consultafacil.repository.MedicoCadastroRepository;
import org.bson.types.ObjectId;
import java.util.List;

public class EspecialidadeService {
    private final EspecialidadeRepository especialidades;
    private final MedicoCadastroRepository medicos;

    public EspecialidadeService(EspecialidadeRepository especialidades, MedicoCadastroRepository medicos) {
        this.especialidades = especialidades;
        this.medicos = medicos;
    }

    public List<EspecialidadeResponse> listar() { return especialidades.listar(); }

    public EspecialidadeResponse consultar(String id) {
        return especialidades.consultar(Query.objectId(id, "ID da especialidade"))
                .orElseThrow(() -> ApiException.notFound("Especialidade não encontrada"));
    }

    public EspecialidadeResponse cadastrar(EspecialidadeRequest request) {
        return especialidades.cadastrar(validar(new ObjectId().toHexString(), request));
    }

    public EspecialidadeResponse atualizar(String id, EspecialidadeRequest request) {
        var atual = consultar(id);
        var nova = validar(atual.id(), request);
        if (!nova.ativa() && medicos.existeAtivoComEspecialidade(new ObjectId(atual.id())))
            throw ApiException.conflict("Desvincule ou desative os médicos ativos antes de desativar a especialidade");
        return especialidades.atualizar(nova).orElseThrow(() -> ApiException.notFound("Especialidade não encontrada"));
    }

    private EspecialidadeResponse validar(String id, EspecialidadeRequest r) {
        if (r == null) throw ApiException.badRequest("Dados da especialidade são obrigatórios");
        String nome = CadastroValidator.texto(r.nome(), "Nome", 1, 100);
        String codigo = CadastroValidator.formato(r.codigo(), "Código", "[a-z0-9-]+");
        if (r.ativa() == null) throw ApiException.badRequest("ativa deve ser um booleano");
        return new EspecialidadeResponse(id, codigo, nome, r.ativa());
    }
}
