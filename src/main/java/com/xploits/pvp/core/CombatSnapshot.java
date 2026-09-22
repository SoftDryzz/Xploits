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
 * {@code targetSurroundSides} llega al mínimo; en caso contrario su valor no se usa.
 *
 * @param hasTarget               si el director tiene un objetivo elegido este tick
 * @param targetDistance          distancia real al objetivo
 * @param targetSurroundSides     cuántos de los <b>cuatro</b> vecinos horizontales del objetivo, a
 *                                la altura de sus pies, son de los que {@code
 *                                EntityUtils.getCityBlock()} considera minables (obsidiana, bloque
 *                                de netherita, obsidiana llorosa, ancla y escombros antiguos).
 *                                Rediseño §4.2.1, corregido por M1: {@code getCityBlock() != null}
 *                                no mide "tiene surround", mide "hay <b>un</b> bloque minable
 *                                pegado a él", así que un enemigo de pie junto al muro de obsidiana
 *                                de cualquier base -o junto a la obsidiana que tu propio {@code
 *                                auto-trap} acaba de colocar- clasificaba {@code RODEADO} y el
 *                                director se ponía a minar la pared. Contar los cuatro lados sí
 *                                distingue un surround de un muro, y la decisión de cuántos hacen
 *                                falta es del núcleo ({@link CombatDirector#SURROUND_MIN_SIDES})
 * @param cityBlockDistance       distancia real al bloque de rodeado, no al objetivo
 * @param targetBurrowed          si el objetivo está <b>protegido</b> dentro de un bloque.
 *                                Rediseño §4.1: la pregunta no es "¿hay algo sólido en sus pies?"
 *                                sino "¿le protege de un cristal?", así que el adaptador debe
 *                                medirlo por resistencia a explosiones (&ge; 600, el umbral que ya
 *                                usa {@code PlayerUtils.isInHole}) y con el cubo completo, no con
 *                                {@code blocksMovement()}: una losa inferior da 0,833 de lado medio
 *                                y {@code blocksMovement()} la daba por buena, así que estar de pie
 *                                sobre una losa, una escalera, un cofre o una trampilla se
 *                                clasificaba ENTERRADO
 * @param targetGliding           si el <b>objetivo</b> va con elytra desplegada
 * @param selfGliding             si vas tú con elytra desplegada. <b>Ya no clasifica nada</b>
 *                                (rediseño §4.1): en este servidor se vuela casi siempre y que
 *                                vueles tú no dice nada del enemigo. Se conserva solo para informar
 * @param selfTotems              tótems que llevas encima
 * @param resources               cuánto llevas de cada recurso
 * @param targetId                identidad estable del objetivo (el nombre sirve) o {@code null} si
 *                                no hay. Solo se usa para saber cuándo la serie de distancias de
 *                                {@link RetreatWatch} deja de referirse al mismo jugador
 * @param hostilesInCrystalRange  cuántos hostiles hay a rango de cristal, <b>protegidos o no</b>.
 *                                Rediseño §4.4, corregido por el crítico C1: la pregunta que decide
 *                                si quieres el aura no es "¿puedo yo cristalear a alguien?" sino
 *                                "¿hay alguien que pueda cristalearme a mí?". Que el otro esté
 *                                enterrado o rodeado le salva a él de tus cristales; no te salva a
 *                                ti de los suyos, y romper no te cuesta ningún cristal (§2). Cuenta
 *                                hostiles, no aliados: {@link AllyPolicy} decide quién es cuál
 * @param selfTotalHealth         tu vida más absorción ({@code PlayerUtils.getTotalHealth()})
 * @param incomingDamage          el daño que <b>ya te apunta</b>
 *                                ({@code PlayerUtils.possibleHealthReductions()}): cristales
 *                                colocados, jugadores con espada a &le;5, camas en el Nether y caída
 * @param selfInHole              si estás en un agujero ({@code PlayerUtils.isInHole(false)})
 * @param selfOnGround            si estás tocando el suelo
 * @param selfYChanged            si tu altura ha cambiado en este tick o en el anterior. Es
 *                                literalmente la condición con la que {@code Surround} se apaga
 *                                solo ({@code toggle-on-y-change}, {@code defaultValue(true)}:
 *                                {@code prevY != getY()} comprobado en {@code TickEvent.Pre}), y
 *                                por eso la postura no pide {@code surround} mientras sea cierta
 *                                (§5, crítico C2): pedirlo en un tick en el que el módulo se va a
 *                                apagar solo es lo que impedía distinguir su autoapagado de que lo
 *                                apagaras tú
 * @param crystalAuraAntiSuicide  si el ajuste {@code anti-suicide} de {@code CrystalAura} está
 *                                encendido. Es {@code defaultValue(true)}, y con él Meteor se niega
 *                                a colocar o a romper un cristal cuyo daño a ti mismo te mataría:
 *                                la misma decisión que tomaba el suelo de tótems, pero con el daño
 *                                exacto en vez de con un contador de ítems. <b>Pero es solo un
 *                                valor por defecto</b>: si el jugador lo apaga, esa protección no
 *                                existe, y entonces -y solo entonces- el suelo de tótems vuelve a
 *                                hacer falta
 */
public record CombatSnapshot(boolean hasTarget, double targetDistance,
                             int targetSurroundSides, double cityBlockDistance,
                             boolean targetBurrowed, boolean targetGliding,
                             boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources,
                             String targetId, int hostilesInCrystalRange,
                             double selfTotalHealth, double incomingDamage,
                             boolean selfInHole, boolean selfOnGround, boolean selfYChanged,
                             boolean crystalAuraAntiSuicide) {
    /** Vida llena sin absorción: el valor neutro cuando nadie ha medido la de verdad. */
    public static final double FULL_HEALTH = 20.0;

    public CombatSnapshot {
        resources = Map.copyOf(resources);
    }

    /**
     * Sin objetivo y sin nada encima. El {@code anti-suicide} se da por <b>apagado</b>, que es el
     * valor prudente: sin nadie que haya leído el ajuste de verdad, el suelo de tótems sigue en pie.
     */
    public static CombatSnapshot none() {
        return new CombatSnapshot(false, 0, 0, 0, false, false, false, 0, Map.of(),
            null, 0, FULL_HEALTH, 0, false, false, false, false);
    }

    public int amountOf(Resource resource) {
        return resources.getOrDefault(resource, 0);
    }

    /** El mismo snapshot con otra identidad de objetivo. */
    public CombatSnapshot withTargetId(String id) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            id, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, selfYChanged, crystalAuraAntiSuicide);
    }

    /** El mismo snapshot con otra cuenta de hostiles a rango de cristal (§4.4, corregido por C1). */
    public CombatSnapshot withHostiles(int hostiles) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostiles, selfTotalHealth, incomingDamage, selfInHole, selfOnGround,
            selfYChanged, crystalAuraAntiSuicide);
    }

    /** El mismo snapshot con otra lectura defensiva (§5). */
    public CombatSnapshot withDefense(double totalHealth, double incoming, boolean inHole, boolean onGround) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, totalHealth, incoming, inHole, onGround,
            selfYChanged, crystalAuraAntiSuicide);
    }

    /** El mismo snapshot con tu altura moviéndose o quieta (§5, crítico C2). */
    public CombatSnapshot withSelfYChanged(boolean changed) {
        return new CombatSnapshot(hasTarget, targetDistance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, changed, crystalAuraAntiSuicide);
    }

    /** El mismo snapshot a otra distancia del objetivo. */
    public CombatSnapshot withTargetDistance(double distance) {
        return new CombatSnapshot(hasTarget, distance, targetSurroundSides, cityBlockDistance,
            targetBurrowed, targetGliding, selfGliding, selfTotems, resources,
            targetId, hostilesInCrystalRange, selfTotalHealth, incomingDamage,
            selfInHole, selfOnGround, selfYChanged, crystalAuraAntiSuicide);
    }
}
