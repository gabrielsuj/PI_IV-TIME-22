package br.edu.puccampinas.consultafacil.api.dto;

public record ValidateCPFResponse(String cpf, boolean valid, String message) {}