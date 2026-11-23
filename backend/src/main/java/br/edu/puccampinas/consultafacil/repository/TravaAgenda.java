/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

package br.edu.puccampinas.consultafacil.repository;

import org.bson.types.ObjectId;
import java.util.List;
import java.util.stream.IntStream;

/** Monitores compartilhados na JVM pelas operações que alteram a disponibilidade de um médico. */
public final class TravaAgenda {
    private static final List<Object> TRAVAS = IntStream.range(0, 256).mapToObj(i -> new Object()).toList();

    private TravaAgenda() {}

    public static Object porMedico(ObjectId medicoId) {
        return TRAVAS.get(Math.floorMod(medicoId.hashCode(), TRAVAS.size()));
    }
}
