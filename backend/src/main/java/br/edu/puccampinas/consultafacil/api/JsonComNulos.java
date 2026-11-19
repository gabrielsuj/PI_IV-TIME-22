package br.edu.puccampinas.consultafacil.api;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** Usado por @JsonAdapter: preserva nulos somente no DTO anotado, sem alterar os demais contratos JSON. */
public final class JsonComNulos implements TypeAdapterFactory {
    @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> tipo) {
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, tipo);
        return new TypeAdapter<>() {
            @Override public void write(JsonWriter out, T valor) throws IOException {
                boolean anterior = out.getSerializeNulls();
                out.setSerializeNulls(true);
                try { delegate.write(out, valor); }
                finally { out.setSerializeNulls(anterior); }
            }

            @Override public T read(JsonReader in) throws IOException { return delegate.read(in); }
        };
    }
}
