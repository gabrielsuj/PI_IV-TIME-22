package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.api.JsonComNulos;
import com.google.gson.annotations.JsonAdapter;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Identidade registrada no evento; perfil histórico pode ser legado, e SISTEMA não possui ID. */
@JsonAdapter(JsonComNulos.class)
public record HistoricoAtorResponse(String perfil, String id, String nome) {
    public static HistoricoAtorResponse de(Document documento) {
        if (documento == null) return new HistoricoAtorResponse(null, null, "Responsável não identificado");
        String perfil = documento.get("perfil") instanceof String texto ? texto : null;
        if ("SISTEMA".equals(perfil)) return new HistoricoAtorResponse(perfil, null, "Sistema");
        Object referencia = documento.get("id");
        String id = referencia instanceof ObjectId oid ? oid.toHexString()
                : referencia instanceof String texto && ObjectId.isValid(texto) ? new ObjectId(texto).toHexString() : null;
        return new HistoricoAtorResponse(perfil, id, id == null ? "Responsável não identificado" : "Usuário não encontrado");
    }

    /** Nome atual de usuarios; não muda a identidade nem o perfil registrados no evento. */
    public HistoricoAtorResponse comNome(String nome) {
        if (id == null || "SISTEMA".equals(perfil)) return this;
        return new HistoricoAtorResponse(perfil, id, nome == null || nome.isBlank() ? "Nome não disponível" : nome.strip());
    }
}
