package br.edu.puccampinas.consultafacil.support;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.*;
import br.edu.puccampinas.consultafacil.repository.*;
import org.bson.types.ObjectId;
import java.util.*;

/** Dublês para regras e HTTP; não substituem a verificação dos índices no MongoDB. */
public final class CadastrosEmMemoria {
    public final Map<String, MedicoResponse> medicos = new LinkedHashMap<>();
    public final Map<String, EspecialidadeResponse> especialidades = new LinkedHashMap<>();

    public final MedicoCadastroRepository medicoRepo = new MedicoCadastroRepository() {
        public MedicoResponse cadastrar(MedicoResponse m) {
            if (medicos.values().stream().anyMatch(atual -> !atual.id().equals(m.id())
                    && atual.crmNumero().equals(m.crmNumero()) && atual.crmUf().equals(m.crmUf())))
                throw ApiException.conflict("CRM já cadastrado nesta UF");
            medicos.put(m.id(), m);
            return m;
        }
        public Optional<MedicoResponse> atualizar(MedicoResponse m) {
            return medicos.containsKey(m.id()) ? Optional.of(cadastrar(m)) : Optional.empty();
        }
        public Optional<MedicoResponse> consultar(ObjectId id) { return Optional.ofNullable(medicos.get(id.toHexString())); }
        public List<MedicoResponse> listar() { return List.copyOf(medicos.values()); }
        public boolean existeAtivoComEspecialidade(ObjectId id) {
            return medicos.values().stream().anyMatch(m -> m.ativo() && m.especialidadeIds().contains(id.toHexString()));
        }
    };

    public final EspecialidadeRepository especialidadeRepo = new EspecialidadeRepository() {
        public EspecialidadeResponse cadastrar(EspecialidadeResponse e) {
            if (especialidades.values().stream().anyMatch(atual -> !atual.id().equals(e.id()) && atual.codigo().equals(e.codigo())))
                throw ApiException.conflict("Código de especialidade já cadastrado");
            especialidades.put(e.id(), e);
            return e;
        }
        public Optional<EspecialidadeResponse> atualizar(EspecialidadeResponse e) {
            return especialidades.containsKey(e.id()) ? Optional.of(cadastrar(e)) : Optional.empty();
        }
        public Optional<EspecialidadeResponse> consultar(ObjectId id) { return Optional.ofNullable(especialidades.get(id.toHexString())); }
        public List<EspecialidadeResponse> listar() { return List.copyOf(especialidades.values()); }
    };
}
