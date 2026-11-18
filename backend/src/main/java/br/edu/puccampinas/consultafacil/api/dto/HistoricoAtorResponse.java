package br.edu.puccampinas.consultafacil.api.dto;

import br.edu.puccampinas.consultafacil.api.JsonComNulos;
import com.google.gson.annotations.JsonAdapter;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Identidade registrada no evento; perfil histórico pode ser legado, e SISTEMA não possui ID. */
@JsonAdapter(JsonComNulos.class)
public record HistoricoAtorResponse(String perfil, String id) {
    public static HistoricoAtorResponse de(Document documento) {
        if (documento == null) return null;
        String perfil = documento.getString("perfil");
        ObjectId id = "SISTEMA".equals(perfil) ? null : documento.getObjectId("id");
        return new HistoricoAtorResponse(perfil, id == null ? null : id.toHexString());
    }
}
