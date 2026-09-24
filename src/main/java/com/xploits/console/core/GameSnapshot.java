package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * La foto de la cabecera de la consola (spec consola §9). Cada campo es {@code null} cuando no se
 * sabe: un dato desconocido se pinta {@code ?}, nunca {@code 0}.
 *
 * <p>{@code elytraPct == -1} significa que el jugador no lleva elytra puesta, que no es lo mismo que
 * no saberlo. Las cantidades de munición son <b>solo de la hotbar</b>, lo que usan los módulos que
 * dirige auto-pvp.
 */
public record Instantanea(
    String dimension,
    Integer jugadores,
    Integer nuestros,
    Integer cohetes,
    Integer elytraPct,
    Progreso viaje,
    Progreso barrido,
    Double vida,
    Integer armadura,
    Integer obsidiana,
    Integer cristales,
    Integer telas,
    Integer yunques,
    List<EstadoModulo> modulos,
    Language idioma) {

    /** Por dónde va un viaje o un barrido: {@code actual} de {@code total}, y los bloques que faltan. */
    public record Progreso(int actual, int total, long restantes) {
    }

    /** Un módulo de Xploits: si está encendido y qué hace ahora, en pocas palabras. */
    public record EstadoModulo(String nombre, boolean activo, String ahora) {
        public EstadoModulo {
            Objects.requireNonNull(nombre, "un módulo sin nombre"); // i18n: allowed: exception message
            Objects.requireNonNull(ahora, "ahora vacío es \"\", no null"); // i18n: allowed: exception message
        }
    }

    private static final String DESCONOCIDO = "-";
    private static final List<String> CLAVES =
        List.of("dim", "jug", "nue", "coh", "ely", "via", "bar", "vid", "arm", "obs", "cri", "tel", "yun", "mod", "lng");

    public Instantanea {
        modulos = List.copyOf(Objects.requireNonNull(modulos, "la lista de módulos puede estar vacía, no ser null")); // i18n: allowed: exception message
        Objects.requireNonNull(idioma, "a snapshot says which language the window speaks");
    }

    /** Sin jugador en el mundo: todo desconocido salvo los módulos. */
    public static Instantanea sinJugador(List<EstadoModulo> modulos, Language idioma) {
        return new Instantanea(null, null, null, null, null, null, null, null, null, null, null, null, null, modulos, idioma);
    }

    /** La misma foto con otra lista de módulos: el centinela la usa para retener un "ahora" sospechoso. */
    public Instantanea conModulos(List<EstadoModulo> otros) {
        return new Instantanea(dimension, jugadores, nuestros, cohetes, elytraPct, viaje, barrido, vida, armadura,
            obsidiana, cristales, telas, yunques, otros, idioma);
    }

    public String codificar() {
        StringJoiner sj = new StringJoiner(";");
        sj.add("dim=" + (dimension == null ? DESCONOCIDO : Escape.escapar(dimension)));
        sj.add("jug=" + numero(jugadores));
        sj.add("nue=" + numero(nuestros));
        sj.add("coh=" + numero(cohetes));
        sj.add("ely=" + numero(elytraPct));
        sj.add("via=" + progreso(viaje));
        sj.add("bar=" + progreso(barrido));
        sj.add("vid=" + (vida == null ? DESCONOCIDO : Double.toString(vida)));
        sj.add("arm=" + numero(armadura));
        sj.add("obs=" + numero(obsidiana));
        sj.add("cri=" + numero(cristales));
        sj.add("tel=" + numero(telas));
        sj.add("yun=" + numero(yunques));
        StringJoiner mods = new StringJoiner("|");
        for (EstadoModulo m : modulos) {
            mods.add(Escape.escapar(m.nombre()) + "," + (m.activo() ? "1" : "0") + "," + Escape.escapar(m.ahora()));
        }
        sj.add("mod=" + mods);
        sj.add("lng=" + idioma.code());
        return sj.toString();
    }

    public static Instantanea decodificar(String s) {
        Map<String, String> valores = new LinkedHashMap<>();
        for (String par : Escape.partir(s, ';')) {
            List<String> kv = Escape.partir(par, '=');
            if (kv.size() != 2) throw new IllegalArgumentException("par mal formado en la instantánea");
            String clave = kv.get(0);
            if (!CLAVES.contains(clave)) throw new IllegalArgumentException("clave desconocida en la instantánea: " + clave);
            if (valores.put(clave, kv.get(1)) != null) throw new IllegalArgumentException("clave repetida en la instantánea: " + clave);
        }
        for (String clave : CLAVES) {
            if (!valores.containsKey(clave)) throw new IllegalArgumentException("falta la clave " + clave + " en la instantánea");
        }
        return new Instantanea(
            texto(valores.get("dim")),
            entero(valores.get("jug")),
            entero(valores.get("nue")),
            entero(valores.get("coh")),
            entero(valores.get("ely")),
            progreso(valores.get("via")),
            progreso(valores.get("bar")),
            DESCONOCIDO.equals(valores.get("vid")) ? null : Double.valueOf(valores.get("vid")),
            entero(valores.get("arm")),
            entero(valores.get("obs")),
            entero(valores.get("cri")),
            entero(valores.get("tel")),
            entero(valores.get("yun")),
            modulos(valores.get("mod")),
            Language.fromCode(valores.get("lng")).orElse(Language.EN));
    }

    private static String numero(Integer n) {
        return n == null ? DESCONOCIDO : n.toString();
    }

    private static String progreso(Progreso p) {
        return p == null ? DESCONOCIDO : p.actual() + "/" + p.total() + "/" + p.restantes();
    }

    private static String texto(String v) {
        return DESCONOCIDO.equals(v) ? null : Escape.desescapar(v);
    }

    private static Integer entero(String v) {
        return DESCONOCIDO.equals(v) ? null : Integer.valueOf(v);
    }

    private static Progreso progreso(String v) {
        if (DESCONOCIDO.equals(v)) return null;
        String[] partes = v.split("/", -1);
        if (partes.length != 3) throw new IllegalArgumentException("progreso mal formado: " + v);
        return new Progreso(Integer.parseInt(partes[0]), Integer.parseInt(partes[1]), Long.parseLong(partes[2]));
    }

    private static List<EstadoModulo> modulos(String v) {
        if (v.isEmpty()) return List.of();
        List<EstadoModulo> lista = new ArrayList<>();
        for (String pieza : Escape.partir(v, '|')) {
            List<String> campos = Escape.partir(pieza, ',');
            if (campos.size() != 3) throw new IllegalArgumentException("módulo mal formado en la instantánea");
            boolean activo = switch (campos.get(1)) {
                case "1" -> true;
                case "0" -> false;
                default -> throw new IllegalArgumentException("estado de módulo desconocido: " + campos.get(1));
            };
            lista.add(new EstadoModulo(Escape.desescapar(campos.get(0)), activo, Escape.desescapar(campos.get(2))));
        }
        return lista;
    }
}
