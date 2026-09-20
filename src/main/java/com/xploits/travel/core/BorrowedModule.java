package com.xploits.travel.core;

/**
 * Un módulo de Meteor que el viaje toma prestado mientras vuela y devuelve al aterrizar (spec §6.3),
 * en lógica pura: anota cómo estaba antes de despegar y decide qué hay que hacerle en cada momento.
 * No sabe nada de Meteor ni de Minecraft -el adaptador le pasa un booleano y ejecuta lo que
 * devuelva-, así que se prueba sin arrancar el juego.
 *
 * <p><b>Por qué esto no es un ajuste declarado.</b> La primera versión devolvía los dos módulos a un
 * valor declarado en dos ajustes {@code *-resting}, con el argumento de spec §6.1: <i>los ajustes de
 * Baritone se pueden escribir pero no leer, así que hay que declarar a qué se vuelve</i>. Ese
 * argumento es cierto para Baritone y <b>falso para estos dos</b>, que son módulos de Meteor:
 * {@code Module.isActive()} se lee perfectamente. Declarar el reposo significaba que un jugador que
 * jamás activó {@code elytra-fly} -lo normal en un anarchy, donde un vuelo raro te delata- aterrizara
 * con él encendido y leyera "Entorno restaurado". Aquí se anota el estado real y se devuelve a ése.
 *
 * <p><b>Un movimiento a mano durante el vuelo manda sobre lo anotado</b>, igual que en
 * {@code ModuleLedger} de auto-pvp: si al soltarlo el módulo ya no está en el estado que la
 * preparación le impuso, alguien lo ha movido después que nosotros, y su decisión es más reciente que
 * nuestra anotación. En ese caso no se toca.
 *
 * <p><b>Y hay un momento en el que no se puede tocar ningún módulo:</b> el desmontaje de salida del
 * mundo. {@code Modules.onGameLeft} desuscribe y desactiva los módulos activos <b>sin</b> poner
 * {@code active = false}, para que vuelvan solos en la siguiente entrada; encender uno ahí lo deja
 * suscrito, y {@code Modules.onGameJoined} lo suscribe otra vez al volver -{@code EventBus.insert()}
 * de orbit no deduplica-, así que sus handlers correrían dos veces por evento el resto de la sesión,
 * y ni apagarlo lo arregla porque {@code unsubscribe} usa {@code List.remove}, que quita una sola
 * copia. Para eso está el {@code canToggle} de {@link #release(boolean, boolean)}: con {@code false}
 * la decisión no se pierde, se queda <b>pendiente</b> y el adaptador la aplica cuando se puede.
 */
public final class BorrowedModule {
    /** Lo que hay que hacerle al módulo ahora mismo. */
    public enum Action {
        ENCENDER,
        APAGAR,
        NADA;

        static Action towards(boolean wanted) {
            return wanted ? ENCENDER : APAGAR;
        }
    }

    /** El nombre del módulo, tal y como el jugador lo ve en la ClickGUI. Solo para los avisos. */
    private final String name;

    /** El estado que la preparación le impone mientras se vuela (spec §6.2). */
    private final boolean inFlight;

    /** Cómo estaba justo antes de despegar, o {@code null} si ahora mismo no está prestado. */
    private Boolean atTakeoff;

    /** Lo que quedó por hacer porque no se podía tocar el módulo cuando tocaba. */
    private Action pending = Action.NADA;

    public BorrowedModule(String name, boolean inFlight) {
        this.name = name;
        this.inFlight = inFlight;
    }

    public String name() {
        return name;
    }

    /** El estado que este módulo tiene que tener mientras el viaje dura. */
    public boolean inFlight() {
        return inFlight;
    }

    /**
     * Toma el módulo al despegar: anota cómo está y contesta qué hay que hacerle para dejarlo en el
     * estado de vuelo.
     *
     * <p>Tomarlo olvida cualquier pendiente: un pendiente es la devolución de un viaje anterior, y si
     * empieza otro viaje esa devolución ya no tiene sentido -lo que hay que devolver es lo que se
     * anota ahora-. El adaptador aplica los pendientes antes de tomar nada, para que "lo que se anota
     * ahora" sea de verdad el estado de reposo del jugador y no el que dejó el viaje anterior.
     *
     * @param active si el módulo está encendido ahora mismo
     */
    public Action take(boolean active) {
        atTakeoff = active;
        pending = Action.NADA;
        return active == inFlight ? Action.NADA : Action.towards(inFlight);
    }

    /**
     * Suelta el módulo al terminar el viaje y contesta qué hay que hacerle.
     *
     * @param active    si el módulo está encendido ahora mismo
     * @param canToggle si ahora mismo se puede encender o apagar un módulo de Meteor sin romperlo.
     *                  Con {@code false} -el desmontaje de salida del mundo- no se devuelve ninguna
     *                  acción: la que tocaba se queda pendiente y se consulta con {@link #pending()}
     * @return lo que hay que hacerle ahora, que es {@code NADA} si no estaba prestado, si el jugador
     *         lo movió a mano durante el vuelo, si ya está donde estaba, o si no se puede tocar
     */
    public Action release(boolean active, boolean canToggle) {
        // Sin anotación no hay nada que devolver, y un pendiente de antes no se toca: el segundo
        // camino de salida que llega no puede borrar lo que dejó apuntado el primero.
        if (atTakeoff == null) return Action.NADA;

        boolean wanted = atTakeoff;
        atTakeoff = null;
        pending = Action.NADA;

        // Ya no está como lo dejó la preparación: alguien lo movió después que nosotros, y eso es más
        // reciente que nuestra anotación. No se toca.
        if (active != inFlight) return Action.NADA;
        if (wanted == active) return Action.NADA;

        Action action = Action.towards(wanted);
        if (canToggle) return action;

        pending = action;
        return Action.NADA;
    }

    /** Lo que quedó por hacer, o {@code NADA} si no hay nada pendiente. */
    public Action pending() {
        return pending;
    }

    public boolean hasPending() {
        return pending != Action.NADA;
    }

    /** Devuelve lo pendiente y lo olvida, para que no se aplique dos veces. */
    public Action claimPending() {
        Action action = pending;
        pending = Action.NADA;
        return action;
    }

    /** Olvida la anotación y lo pendiente. Para cuando el módulo ni siquiera está registrado. */
    public void forget() {
        atTakeoff = null;
        pending = Action.NADA;
    }
}
