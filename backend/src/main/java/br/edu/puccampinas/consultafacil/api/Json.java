package br.edu.puccampinas.consultafacil.api;

import com.google.gson.Gson;

public final class Json {
    private static final Gson GSON = new Gson();

    private Json() {}

    public static String toJson(Object obj) { return GSON.toJson(obj); }

    public static <T> T fromJson(String json, Class<T> type) { return GSON.fromJson(json, type); }
}
