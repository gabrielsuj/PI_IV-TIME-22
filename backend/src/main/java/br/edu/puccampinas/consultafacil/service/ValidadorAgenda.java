package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.AgendaRequest;
import br.edu.puccampinas.consultafacil.api.dto.FaixaDto;
import br.edu.puccampinas.consultafacil.api.dto.JornadaDto;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Regras da agenda que o validador do banco não cobre (docs/modelagem/medicos-agenda.md, seção 5.2). */
public final class ValidadorAgenda {
    private ValidadorAgenda() {}

    public static void validar(AgendaRequest r) {
        if (r.duracaoMinutos() == null || r.duracaoMinutos() < 5 || r.duracaoMinutos() > 240)
            throw ApiException.badRequest("duracaoMinutos deve estar entre 5 e 240");
        if (r.fusoHorario() != null) {
            try {
                ZoneId.of(r.fusoHorario());
            } catch (DateTimeException e) {
                throw ApiException.badRequest("fusoHorario inválido");
            }
        }
        if (r.jornadas() == null) throw ApiException.badRequest("jornadas é obrigatório");

        Set<Integer> dias = new HashSet<>();
        for (JornadaDto j : r.jornadas()) {
            if (j == null || j.diaSemana() == null || j.diaSemana() < 1 || j.diaSemana() > 7)
                throw ApiException.badRequest("diaSemana deve estar entre 1 (segunda) e 7 (domingo)");
            if (!dias.add(j.diaSemana()))
                throw ApiException.badRequest("diaSemana repetido: " + j.diaSemana());
            if (j.faixas() == null || j.faixas().isEmpty())
                throw ApiException.badRequest("O dia " + j.diaSemana() + " precisa de pelo menos uma faixa");

            for (FaixaDto f : j.faixas()) {
                if (f == null || f.inicioMinuto() == null || f.fimMinuto() == null
                        || f.inicioMinuto() < 0 || f.fimMinuto() > 1440
                        || f.inicioMinuto() >= f.fimMinuto())
                    throw ApiException.badRequest("Faixa com horário inválido no dia " + j.diaSemana());
                if (f.fimMinuto() - f.inicioMinuto() < r.duracaoMinutos())
                    throw ApiException.badRequest("A duração da consulta não cabe em uma faixa do dia " + j.diaSemana());
            }

            List<FaixaDto> ordenadas = new ArrayList<>(j.faixas());
            ordenadas.sort(Comparator.comparing(FaixaDto::inicioMinuto));
            for (int i = 1; i < ordenadas.size(); i++) {
                // intervalos [inicio, fim): faixas que apenas se encostam são permitidas
                if (ordenadas.get(i).inicioMinuto() < ordenadas.get(i - 1).fimMinuto())
                    throw ApiException.badRequest("Faixas sobrepostas no dia " + j.diaSemana());
            }
        }
    }
}
