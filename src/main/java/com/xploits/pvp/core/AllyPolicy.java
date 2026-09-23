package com.xploits.pvp.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ¿Este jugador es de los nuestros? (spec §13). Decisión pura: el adaptador solo recoge los nombres
 * de los otros módulos y pregunta.
 *
 * <p>"Los nuestros" son los amigos de Meteor, los couriers de {@code kit-requester} y la lista
 * {@code users} de {@code auto-tpy}. El trato es el mismo que da Meteor a su lista de amigos:
 * <b>incondicional</b>. No es "no iniciar pero responder si te pega": a los nuestros no se les
 * ataca nunca, aunque te peguen ellos.
 *
 * <p><b>Esto decide a quién elige auto-pvp, y nada más.</b> auto-pvp no ataca: enciende módulos, y
 * los cinco que dirige eligen su propio objetivo con el único filtro social que conocen, la lista de
 * amigos de Meteor. Que los couriers y la lista users también se respeten ahí es cosa de
 * {@link FriendLedger} (spec §14); sin esa sincronización, esta decisión se queda en el objetivo del
 * director y el courier acaba entelado por el módulo que el director acaba de encender.
 *
 * <p>El caso {@code AMIGO} es exactamente la negación de {@code Friends.get().shouldAttack()}, el
 * único filtro social que aplicaba {@code TargetUtils.getPlayerTarget}: al absorberlo aquí, el
 * predicado de selección sigue descartando a los amigos igual que antes, y el motivo que se le
 * enseña al jugador sale de un único sitio para los tres conjuntos.
 */
public final class AllyPolicy {
    /** De quién es el jugador que se ha mirado, y por qué no se le ataca. */
    public enum Allegiance {
        /** No está en ninguna de las tres listas: objetivo válido. */
        AJENO(null),
        /** Amigo de Meteor (.friends add). */
        AMIGO(PvpText.ALLY_FRIEND),
        /** Courier de la lista known-couriers de kit-requester. */
        COURIER(PvpText.ALLY_COURIER),
        /** Nombre de la lista users de auto-tpy. */
        USUARIO_TPY(PvpText.ALLY_TPY_USER);

        private final PvpText reason;

        Allegiance(PvpText reason) {
            this.reason = reason;
        }

        /** true si es de los nuestros y auto-pvp no debe atacarle jamás. */
        public boolean isOurs() {
            return this != AJENO;
        }

        /** Por qué no se le ataca, para el aviso y para el estado. Vacío en {@link #AJENO}. */
        public PvpText reason() {
            return reason == null ? PvpText.NOTHING : reason;
        }
    }

    private AllyPolicy() {}

    /**
     * Una lista de origen lista para preguntarle: recortada, sin entradas en blanco y sin nulos.
     *
     * <p>El recorte se hace <b>una sola vez por lista y por tick</b>, aquí, y no una vez por jugador
     * mirado dentro de {@link #of}. El predicado de selección se evalúa sobre todas las entidades
     * del mundo, así que recortar dentro salía a un barrido lineal de la lista —y un {@code trim()}
     * por entrada— por cada jugador a la vista: con las listas de fábrica da igual, con doscientos
     * nombres no. Normalizado de una vez, {@link #of} pregunta por hash.
     *
     * <p>Lo que se perdona son los espacios de alrededor: un nombre de Minecraft no puede llevar
     * espacios —Meteor rechaza añadir un amigo cuyo nombre los tenga—, así que " StormAegis44 " en
     * la lista no puede significar otra cosa que ese jugador. Perdonarlos solo puede dejar de atacar
     * a alguien, nunca atacar a uno más; en {@code AutoTpyPolicy} el error caería del lado contrario
     * —aceptar una TPA de más—, y por eso allí no se perdona nada.
     */
    public static Set<String> names(Collection<String> list) {
        if (list == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String entry : list) {
            if (entry == null) continue;
            String name = entry.trim();
            // Una entrada en blanco no puede emparejar con nadie: el nombre mirado se rechaza en
            // of() si llega vacío, así que guardarla solo serviría para ocupar sitio.
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    /**
     * @param name                 nombre del jugador mirado (el del perfil, como lo muestra auto-pvp)
     * @param meteorFriend         el adaptador ya consultó los amigos de Meteor
     * @param kitRequesterCouriers ajuste known-couriers de kit-requester, pasado por {@link #names}
     * @param autoTpyUsers         ajuste users de auto-tpy, pasado por {@link #names}
     */
    public static Allegiance of(String name, boolean meteorFriend,
                                Set<String> kitRequesterCouriers, Set<String> autoTpyUsers) {
        if (name == null) return Allegiance.AJENO;
        String candidate = name.trim();
        // Un nombre ilegible no protege a nadie: ningún jugador real tiene el nombre en blanco, y
        // dar por nuestro a quien no sabemos identificar sería dejar de defenderse contra cualquiera
        // que consiga que el nombre llegue vacío.
        if (candidate.isEmpty()) return Allegiance.AJENO;

        if (meteorFriend) return Allegiance.AMIGO;
        // Comparación exacta y que distingue mayúsculas, que es lo que prometen las descripciones de
        // las dos listas y lo que ya hacen AutoTpyPolicy y CourierPolicy con esas mismas listas. Un
        // "StormAegis44" no es un "stormaegis44".
        if (contains(kitRequesterCouriers, candidate)) return Allegiance.COURIER;
        if (contains(autoTpyUsers, candidate)) return Allegiance.USUARIO_TPY;
        return Allegiance.AJENO;
    }

    private static boolean contains(Set<String> list, String candidate) {
        return list != null && list.contains(candidate);
    }
}
