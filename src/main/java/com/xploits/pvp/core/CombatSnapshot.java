package com.xploits.pvp.core;

import java.util.Map;

/**
 * La situación traducida a valores simples (spec §5). Es la frontera: de aquí en adelante todo el
 * criterio es lógica pura y se puede probar sin arrancar el juego.
 *
 * <p>La vida del objetivo no está aquí a propósito y sigue sin estarlo (rediseño §10): ninguna
 * transición la necesita y no todos los servidores la mandan. <b>La tuya sí</b>: el cliente la sabe
 * siempre, y las dos decisiones defensivas del rediseño (§5) se toman con ella y con el daño que ya
 * te apunta, no con un proxy.
 *
 * <p>{@code cityBlockDistance} es la distancia real del jugador al bloque de rodeado -no al
 * objetivo- (spec §4.2.1, corregido): {@code targetDistance} no sirve de proxy porque el bloque es
 * un vecino horizontal del objetivo y puede caer al lado contrario de donde estás tú, así que estar
 * cerca del objetivo no garantiza estar cerca del bloque. Solo tiene sentido cuando
 * {@code targetSurrounded} es verdadero; en caso contrario su valor no se usa.
 *
 * @param hasTarget                         si el director tiene un objetivo elegido este tick
 * @param targetDistance                    distancia real al objetivo
 * @param targetSurrounded                  si {@code EntityUtils.getCityBlock()} encontró bloque
 * @param cityBlockDistance                 distancia real al bloque de rodeado, no al objetivo
 * @param targetBurrowed                    si el objetivo está <b>protegido</b> dentro de un bloque.
 *                                          Rediseño §4.1: la pregunta no es "¿hay algo sólido en sus
 *                                          pies?" sino "¿le protege de un cristal?", así que el
 *                                          adaptador debe medirlo por resistencia a explosiones
 *                                          (&ge; 600, el umbral que ya usa {@code
 *                                          PlayerUtils.isInHole}) y con el cubo completo, no con
 *                                          {@code blocksMovement()}: una losa inferior da 0,833 de
 *                                          lado medio y {@code blocksMovement()} la daba por buena,
 *                                          así que estar de pie sobre una losa, una escalera, un
 *                                          cofre o una trampilla se clasificaba ENTERRADO
 * @param targetGliding                     si el <b>objetivo</b> va con elytra desplegada
 * @param selfGliding                       si vas tú con elytra desplegada. <b>Ya no clasifica
 *                                          nada</b> (rediseño §4.1): en este servidor se vuela casi
 *                                          siempre y que vueles tú no dice nada del enemigo. Se
 *                                          conserva solo para informar
 * @param selfTotems                        tótems que llevas encima
 * @param resources                         cuánto llevas de cada recurso
 * @param targetId                          identidad estable del objetivo (el nombre sirve) o
 *                                          {@code null} si no hay. Solo se usa para saber cuándo la
 *                                          serie de distancias de {@link RetreatWatch} deja de
 *                                          referirse al mismo jugador
 * @param unprotectedHostilesInCrystalRange cuántos hostiles hay a rango de cristal que no estén
 *                                          protegidos (ni enterrados ni rodeados). Rediseño §4.4:
 *                                          {@code crystal-aura} pelea contra todos a la vez, así que
 *                                          atarla a la fase de un solo jugador abre el cebo obvio
 *                                          -uno se entierra, el otro te cristalea-. Cuenta hostiles,
 *                                          no aliados: {@link AllyPolicy} decide quién es cuál
 * @param selfTotalHealth                   tu vida más absorción ({@code PlayerUtils.getTotalHealth()})
 * @param incomingDamage                    el daño que <b>ya te apunta</b>
 *                                          ({@code PlayerUtils.possibleHealthReductions()}):
 *                                          cristales colocados, jugadores con espada a &le;5, camas
 *                                          en el Nether y caída
 * @param selfInHole                        si estás en un agujero ({@code PlayerUtils.isInHole(false)})
 * @param selfOnGround                      si estás tocando el suelo
 */
public record CombatSnapshot(boolean hasTarget, double targetDistance,
                             boolean targetSurrounded, double cityBlockDistance,
                             boolean targetBurrowed, boolean targetGliding,
                             boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources,
                             String targetId, int unprotectedHostilesInCrystalRange,
                             double selfTotalHealth, double incomingDamage,
                             boolean selfInHole, boolean selfOnGround) {
    /** Vida llena sin absorción: el valor neutro cuando nadie ha medido la de verdad. */
    public static final double FULL_HEALTH = 20.0;

    public CombatSnapshot {
        resources = Map.copyOf(resources);
    }

    /**
     * Constructor de transición con la forma que tenía el snapshot antes del rediseño.
     *
     * <p><b>Es un andamio, no la forma definitiva.</b> Existe solo para que el adaptador
     * ({@code AutoPvp}) siga compilando mientras se le añade la lectura de los campos nuevos, que es
     * una tarea aparte. Rellena esos campos con valores <b>neutros</b>, no con los reales: sin
     * objetivo identificado, sin hostiles conocidos a rango de cristal, vida llena, nada de daño
     * apuntándote, ni en agujero ni en el suelo. Con ellos el núcleo se comporta en los dos ejes
     * nuevos como si no existieran: la postura sale siempre {@code TRANQUILO} y la regla del aura de
     * §4.4 nunca se dispara.
     *
     * <p>Lo que el adaptador tiene que pasar a rellenar, y de dónde sale cada cosa, está en el
     * javadoc de los parámetros del constructor canónico. Cuando lo haga, este constructor sobra.
     */
    public CombatSnapshot(boolean hasTarget, double targetDistance,
                          boolean targetSurrounded, double cityBlockDistance,
                          boolean targetBurrowed, boolean targetGliding,
                          boolean selfGliding, int selfTotems,
                          Map<Resource, Integer> resources) {
        this(hasTarget, targetDistance, targetSurrounded, cityBlockDistance, targetBurrowed,
            targetGliding, selfGliding, selfTotems, resources,
            null, 0, FULL_HEALTH, 0, false, false);
    }

    /** Sin objetivo y sin nada encima. */
    public static CombatSnapshot none() {
        return new CombatSnapshot(false, 0, false, 0, false, false, false, 0, Map.of());
    }

    public int amountOf(Resource resource) {
        return resources.getOrDefault(resource, 0);
    }

    /** El mismo snapshot con otra identidad de objetivo. */
    public CombatSnapshot withTargetId(String id) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurrounded, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            id, unprotectedHostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround);
    }

    /** El mismo snapshot con otra cuenta de hostiles sin proteger a rango de cristal (§4.4). */
    public CombatSnapshot withHostiles(int hostiles) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurrounded, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostiles, selfTotalHealth, incomingDamage, selfInHole, selfOnGround);
    }

    /** El mismo snapshot con otra lectura defensiva (§5). */
    public CombatSnapshot withDefense(double totalHealth, double incoming, boolean inHole, boolean onGround) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurrounded, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, unprotectedHostilesInCrystalRange, totalHealth, incoming, inHole, onGround);
    }

    /** El mismo snapshot a otra distancia del objetivo. */
    public CombatSnapshot withTargetDistance(double distance) {
        return new CombatSnapshot(hasTarget, distance, targetSurrounded, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, unprotectedHostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround);
    }
}
