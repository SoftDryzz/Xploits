package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Qué nombres hay que escribir en la lista de amigos de Meteor y cuáles hay que quitar (spec §14),
 * en lógica pura: el adaptador le pasa lo que quiere sincronizar y lo que hay de verdad en la lista
 * ahora mismo, y ejecuta lo que este devuelve. No sabe nada de Meteor ni de Minecraft.
 *
 * <p><b>Por qué hace falta.</b> {@link AllyPolicy} solo gobierna el objetivo que elige {@code
 * auto-pvp}, y {@code auto-pvp} no ataca: enciende módulos. Los cinco que dirige eligen su propio
 * objetivo —{@code crystal-aura} llama a {@code Friends.get().shouldAttack()} él mismo, y {@code
 * auto-trap}, {@code auto-web}, {@code auto-anvil} y {@code auto-city} llaman a {@code
 * TargetUtils.getPlayerTarget()}, cuyo único filtro social es ese mismo {@code shouldAttack}
 * (verificado contra las fuentes de meteor-client 1.21.11)—, así que <b>el único mecanismo que
 * respetan los cinco es la lista de amigos de Meteor</b>. Sincronizar a los nuestros con esa lista
 * es lo que hace que un courier pegado a ti no acabe entelado por el módulo que acaba de encender
 * {@code auto-pvp}.
 *
 * <p><b>Y es una lista que no es nuestra.</b> {@code Friends} es configuración global de Meteor,
 * persiste en {@code friends.nbt} y la mantiene el jugador a mano. De ahí la única invariante que
 * manda sobre todas las demás: <b>nunca se borra un amigo que el jugador ya tenía</b>. Este registro
 * existe para poder distinguirlos: solo sale de la lista lo que entró por aquí.
 *
 * <p>La línea es la de {@code ModuleLedger} y {@code BorrowedModule}: se anota lo que se tomó, y
 * <b>si el jugador lo mueve a mano después, su decisión es más reciente que nuestra anotación</b> y
 * manda. Un amigo que pusimos y que el jugador quita deja de ser nuestro y no se vuelve a poner
 * mientras dure la activación.
 *
 * <h2>Mayúsculas: dos comparaciones distintas, a propósito</h2>
 *
 * <ul>
 *   <li><b>Para decidir si hace falta añadir</b>, se mira <b>sin</b> distinguir mayúsculas, porque
 *       es lo que hace Meteor: {@code Friends.add} rechaza el nombre si {@code get(name) != null},
 *       y {@code Friends.get(String)} compara con {@code equalsIgnoreCase}. Con "stormaegis44" ya
 *       en la lista, añadir "StormAegis44" devuelve {@code false}; pedirlo sería pedir algo que no
 *       va a pasar, y apuntarlo como nuestro sería apuntar como nuestro al amigo del jugador.</li>
 *   <li><b>Para decidir si algo sigue siendo nuestro</b>, se exige el nombre <b>exacto</b>. Si lo
 *       que hay en la lista ya no está escrito como lo escribimos nosotros, no podemos demostrar
 *       que sea el nuestro, y ante la duda se suelta: como mucho se queda un nombre de más en la
 *       lista del jugador —molesto—, nunca se borra uno suyo —irreversible—. Y pasa de verdad:
 *       {@code Friend.updateInfo()} sustituye el nombre por el canónico de Mojang, y lo llaman la
 *       pestaña Friends de la ClickGUI y el comando {@code .reload}.</li>
 * </ul>
 */
public final class FriendLedger {
    /** Los nombres que este registro metió en la lista de amigos y siguen ahí, tal y como se escribieron. */
    private final Set<String> added = new LinkedHashSet<>();

    /**
     * Nombres que no se vuelven a intentar en esta activación: o los quitó el jugador a mano después
     * de que los pusiéramos nosotros, o Meteor se negó a añadirlos. Es la misma memoria que
     * {@code released} de {@code ModuleLedger}, y se olvida igual, con {@link #reset()}.
     */
    private final Set<String> abandoned = new LinkedHashSet<>();

    /** Lo que hay que hacerle a la lista de amigos ahora mismo. */
    public record Result(List<String> toAdd, List<String> toRemove) {
        public Result {
            toAdd = List.copyOf(toAdd);
            toRemove = List.copyOf(toRemove);
        }

        /** true si no hay que tocar nada, que es el caso normal y el que no escribe en disco. */
        public boolean isEmpty() {
            return toAdd.isEmpty() && toRemove.isEmpty();
        }
    }

    /**
     * Los nombres de los nuestros que se pueden sincronizar, a partir de las dos listas de origen.
     *
     * <p><b>Con {@code trust-unknown-couriers} encendido no se sincroniza ningún courier</b> (spec
     * §14.2). Ese ajuste hace que cualquiera que imite el mensaje READY y mande una TPA durante la
     * espera de un pedido se añada solo a {@code known-couriers}; con la sincronización, eso dejaría
     * de ser "consigue un teletransporte" y pasaría a ser "entra en tu lista de amigos de Meteor y
     * los cinco módulos de combate dejan de tocarle", de forma persistente y a disco, con una línea
     * de chat. Mientras el ajuste esté encendido la lista entera deja de ser fiable, y no se escribe
     * de ella nada en la lista de amigos. La lista {@code users} de {@code auto-tpy} no tiene ese
     * problema —solo se escribe a mano— y se sincroniza igual.
     *
     * <p>Se descarta además lo que Meteor no podría guardar: {@code Friends.add} rechaza el nombre
     * vacío y el que lleva un espacio. Aquí se rechaza cualquier blanco interior, no solo el
     * espacio: ningún nombre de Minecraft lleva ninguno, y pedir un añadido que va a devolver
     * {@code false} solo sirve para tener que deshacerlo.
     */
    public static Set<String> syncable(Collection<String> couriers, boolean trustUnknownCouriers,
                                       Collection<String> tpyUsers) {
        Set<String> result = new LinkedHashSet<>();
        if (!trustUnknownCouriers) collectWritable(result, couriers);
        collectWritable(result, tpyUsers);
        return result;
    }

    private static void collectWritable(Set<String> target, Collection<String> names) {
        if (names == null) return;
        for (String entry : names) {
            if (entry == null) continue;
            String name = entry.trim();
            if (name.isEmpty() || hasWhitespace(name)) continue;
            target.add(name);
        }
    }

    private static boolean hasWhitespace(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Decide qué tocar de la lista de amigos. No escribe nada: el adaptador ejecuta el
     * {@link Result} y le contesta con {@link #disown(String)} si Meteor rechazó algún añadido.
     *
     * @param wanted  los nombres que deberían estar en la lista de amigos por nuestra causa
     *                ({@link #syncable})
     * @param present los nombres que hay de verdad en la lista de amigos ahora mismo, tal y como los
     *                guarda Meteor
     */
    public Result reconcile(Set<String> wanted, Set<String> present) {
        Set<String> exact = present == null ? Set.of() : present;
        Set<String> insensitive = lowercased(exact);
        Set<String> targets = wanted == null ? Set.of() : wanted;

        List<String> toRemove = new ArrayList<>();
        for (String name : new ArrayList<>(added)) {
            // Ya no está escrito como lo escribimos: el jugador lo movió a mano, y eso es más
            // reciente que nuestra anotación. Deja de ser nuestro -no lo borraremos nunca- y no se
            // vuelve a poner en esta activación, igual que un módulo soltado a mano en ModuleLedger.
            if (!exact.contains(name)) {
                added.remove(name);
                abandoned.add(name);
                continue;
            }
            // Sigue puesto y es nuestro, pero ya no está en ninguna de las dos listas de origen: el
            // jugador lo sacó de known-couriers o de users, y lo que entró por aquí sale por aquí.
            if (!targets.contains(name)) {
                toRemove.add(name);
                added.remove(name);
            }
        }

        List<String> toAdd = new ArrayList<>();
        for (String name : targets) {
            if (abandoned.contains(name)) continue;
            // Ya está en la lista: o es nuestro y sigue puesto, o es del jugador. En los dos casos
            // no hay nada que añadir, y en el segundo tampoco nada que apuntar como propio.
            if (insensitive.contains(lower(name))) continue;
            toAdd.add(name);
            added.add(name);
        }

        return new Result(toAdd, toRemove);
    }

    /**
     * Lo que hay que quitar al soltar la lista entera —al apagar el módulo—: todo lo nuestro que
     * siga puesto, y nada más. Lo que el jugador quitó a mano mientras tanto ya no es nuestro y no
     * aparece aquí.
     */
    public Result release(Set<String> present) {
        return reconcile(Set.of(), present);
    }

    /**
     * El adaptador avisa de que Meteor <b>no</b> añadió este nombre: deja de contar como nuestro
     * —así nunca se borrará— y no se vuelve a intentar en esta activación. Con {@link #syncable} y
     * la comprobación de presencia delante no debería pasar nunca; está porque la consecuencia de
     * equivocarse en este sentido es borrar un amigo del jugador.
     */
    public void disown(String name) {
        added.remove(name);
        abandoned.add(name);
    }

    /** Los nombres que este registro tiene puestos en la lista de amigos ahora mismo. */
    public Set<String> added() {
        return Set.copyOf(added);
    }

    /** Olvida lo puesto y lo abandonado. Se llama al encender o apagar el módulo entero. */
    public void reset() {
        added.clear();
        abandoned.clear();
    }

    private static Set<String> lowercased(Set<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) result.add(lower(name));
        }
        return result;
    }

    /**
     * Minúsculas con {@code Locale.ROOT} para imitar el {@code equalsIgnoreCase} de
     * {@code Friends.get}. No son la misma operación en todo Unicode, pero un nombre de Minecraft
     * es ASCII, y donde difieren el resultado es un añadido de más que Meteor rechazará y que
     * {@link #disown(String)} recoge.
     */
    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
