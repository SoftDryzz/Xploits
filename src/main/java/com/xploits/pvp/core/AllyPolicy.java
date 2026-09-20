package com.xploits.pvp.core;

import java.util.Collection;

/**
 * ¿Este jugador es de los nuestros? (spec §13). Decisión pura: el adaptador solo recoge los nombres
 * de los otros módulos y pregunta.
 *
 * <p>"Los nuestros" son los amigos de Meteor, los couriers de {@code kit-requester} y la lista
 * {@code users} de {@code auto-tpy}. El trato es el mismo que da Meteor a su lista de amigos:
 * <b>incondicional</b>. No es "no iniciar pero responder si te pega": a los nuestros no se les
 * ataca nunca, aunque te peguen ellos.
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
        AMIGO("es amigo de Meteor"),
        /** Courier de la lista known-couriers de kit-requester. */
        COURIER("es courier de kit-requester"),
        /** Nombre de la lista users de auto-tpy. */
        USUARIO_TPY("está en la lista users de auto-tpy");

        private final String reason;

        Allegiance(String reason) {
            this.reason = reason;
        }

        /** true si es de los nuestros y auto-pvp no debe atacarle jamás. */
        public boolean isOurs() {
            return this != AJENO;
        }

        /** Por qué no se le ataca, para el aviso y para el estado. Vacío en {@link #AJENO}. */
        public String reason() {
            return reason == null ? "" : reason;
        }
    }

    private AllyPolicy() {}

    /**
     * @param name                 nombre del jugador mirado (el del perfil, como lo muestra auto-pvp)
     * @param meteorFriend         el adaptador ya consultó los amigos de Meteor
     * @param kitRequesterCouriers ajuste known-couriers de kit-requester
     * @param autoTpyUsers         ajuste users de auto-tpy
     */
    public static Allegiance of(String name, boolean meteorFriend,
                                Collection<String> kitRequesterCouriers, Collection<String> autoTpyUsers) {
        if (name == null) return Allegiance.AJENO;
        String candidate = name.trim();
        // Un nombre ilegible no protege a nadie: ningún jugador real tiene el nombre en blanco, y
        // dar por nuestro a quien no sabemos identificar sería dejar de defenderse contra cualquiera
        // que consiga que el nombre llegue vacío.
        if (candidate.isEmpty()) return Allegiance.AJENO;

        if (meteorFriend) return Allegiance.AMIGO;
        if (contains(kitRequesterCouriers, candidate)) return Allegiance.COURIER;
        if (contains(autoTpyUsers, candidate)) return Allegiance.USUARIO_TPY;
        return Allegiance.AJENO;
    }

    /**
     * Comparación exacta y que distingue mayúsculas, que es lo que prometen las descripciones de
     * las dos listas y lo que ya hacen {@code AutoTpyPolicy} y {@code CourierPolicy} con esas mismas
     * listas. Un "StormAegis44" no es un "stormaegis44".
     *
     * <p>Lo único que se perdona son los espacios de alrededor: un nombre de Minecraft no puede
     * llevar espacios -Meteor rechaza añadir un amigo cuyo nombre los tenga-, así que
     * " StormAegis44 " en la lista no puede significar otra cosa que ese jugador. Perdonarlos aquí
     * solo puede dejar de atacar a alguien, nunca atacar a uno más; en AutoTpyPolicy el error
     * caería del lado contrario -aceptar una TPA de más-, y por eso allí no se perdona nada.
     *
     * <p>Una entrada en blanco no empareja con nadie sin necesidad de comprobarlo aquí: el nombre
     * que llega ya se ha rechazado arriba si estaba en blanco, así que nunca puede ser igual a "".
     */
    private static boolean contains(Collection<String> list, String candidate) {
        if (list == null) return false;
        for (String entry : list) {
            if (entry != null && entry.trim().equals(candidate)) return true;
        }
        return false;
    }
}
