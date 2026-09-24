# Arquitectura

## La regla que gobierna todo el repo

Cada módulo se parte en dos, y la frontera es dura:

```
  <módulo>/core/     Núcleo puro. No importa net.minecraft ni meteordevelopment.
                     Toda la lógica que decide. Con tests unitarios.

  <módulo>/          Adaptador. Habla con Minecraft, Meteor y Baritone.
                     No decide nada. Sin tests: se verifica jugando.
```

**Si algo se puede decidir sin arrancar Minecraft, va en el núcleo.** No es purismo: es que el
adaptador no se puede probar, y lo que no se prueba se rompe sin que nadie lo note. Esta regla se ha
saltado tres veces en la vida del repo y las tres costaron una ronda entera de revisión.

El caso que mejor lo explica: en `nether-sweep`, el cálculo de la distancia que queda por volar
alimenta la proyección de cohetes, que decide si cortar el vuelo. Estaba en el adaptador, sin red.
Bajarlo al núcleo costó una clase y quince tests; dejarlo arriba habría costado que alguien se
quedara sin cohetes a cien mil bloques de casa sin que ningún test dijera nada.

`FronteraTest` lee el código fuente y hace cumplir tres cosas que ningún test de un núcleo puede ver:
que todo módulo y comando herede de las bases, que nadie escriba al chat por `ChatUtils` sin
registrarlo aparte, y que ni los núcleos ni la ventana importen nada del juego.

## Qué hay dentro

```
com/xploits/                    Registro de módulos y comando (XploitsAddon).
com/xploits/commands/           El comando .xploits.
com/xploits/shared/chat/        Protocolo de chat de SnifferBuddy, compartido.
com/xploits/shared/XploitsModule.java   Base de todos los módulos; ComandoBase.java, de todos los
                                        comandos. Lo que dicen por el chat va también a la consola.

com/xploits/kitrequester/       Pedir kits y aceptar al courier.
      .../core/                 Máquina de estados, cola y progreso.
      .../inventory/            Vaciado de shulkers en el ender chest.

com/xploits/autotpy/            Aceptar TPAs.
      .../core/                 Política de aceptación.

com/xploits/stash/              Índice pasivo de contenedores.
      .../core/                 Índice, claves y búsqueda.

com/xploits/elytra/             Cambio de elytra.
      .../core/                 Política de cambio.

com/xploits/pvp/                Dirección de los módulos de combate.
      .../core/                 Fase ofensiva, postura defensiva, catálogo de módulos
                                dirigidos, quién es de los nuestros, y qué se escribe
                                en la lista de amigos de Meteor.

com/xploits/travel/             Viaje con patrón de despiste.
      .../core/                 Geometría de la ruta, los patrones, el guion de comandos
                                de Baritone, la red de chat, el vigilante de atasco y el
                                préstamo de módulos ajenos.

com/xploits/sweep/              Barrido del Nether.
      .../core/                 Rectángulo, planificador de pasadas, cobertura previa,
                                recuento de lo que llega, presupuesto de cohetes,
                                cuentakilómetros y sonda de anchura.

com/xploits/console/            El adaptador: el módulo, el colector de la cabecera y el sumidero,
                                que escribe desde el único hilo propio del addon.
      .../core/                 Todo lo que decide la consola: formato de los ficheros, qué se pinta
                                y cómo se degrada, la vida de la ventana, el centinela de coordenadas.
                                Puro y con tests.
      .../ventana/              La ventana. Se ejecuta en otro proceso, fuera del juego, con
                                java -cp <jar del mod>; por eso solo puede tocar el JDK y console/core.
```

## Lo que sería portable a otro cliente

Esto importa si algún día esto deja de ser un addon de Meteor.

**Portable tal cual — son Java puro, sin una sola dependencia del cliente:**

| Pieza | Qué resuelve |
|---|---|
| `travel/core/RoutePlanner` | Geometría de los cuatro patrones de despiste y sus rechazos |
| `travel/core/BaritoneScript` | El vocabulario exacto de comandos de Baritone |
| `travel/core/SafetyNet` | Reconocer si un texto es un comando dirigido por nosotros |
| `travel/core/StallWatch` | Detectar que no se avanza, atado al waypoint por construcción |
| `travel/core/BorrowedModule` | Prestar un módulo ajeno y devolverlo al estado real |
| `pvp/core/*` | El criterio de combate entero: fases, postura, propiedad de módulos |
| `sweep/core/*` | Planificación de pasadas, cobertura, presupuesto de cohetes |
| `elytra/core/ElytraPolicy` | Cuándo cambiar la elytra y por cuál |
| `stash/core/*` | El índice de contenedores |

**No portable — es la traducción a este cliente concreto:**

Los adaptadores (`NetherSweep.java`, `AutoTravel.java`, `AutoPvp.java`…). Cada uno hace tres cosas:
suscribirse a eventos, traducir el estado del juego a los valores simples que el núcleo entiende, y
ejecutar lo que el núcleo decide.

**La proporción no es casual.** Los adaptadores son grandes en líneas pero delgados en decisiones. Un
puerto a otro cliente reescribe los adaptadores y **no toca el núcleo**, que es donde están las
horas de pensar y todas las cicatrices.

## La frontera: `CombatSnapshot` como ejemplo

El mejor ejemplo de cómo se dibuja la frontera es `pvp/core/CombatSnapshot`: un record de valores
simples —distancias, booleanos, conteos— que el adaptador rellena y el núcleo consume.

Nada de `PlayerEntity`, `BlockState` ni `World` cruza esa línea. Eso es lo que hace que la decisión
de combate entera se pueda probar con 169 tests sin arrancar el juego, y lo que haría que portarla
fuese cambiar quién rellena el record.

Lo mismo con `Coverage`, que recibe **líneas de texto ya leídas** en vez de rutas: quien toca el
disco es el adaptador, así que el parseo —donde está el riesgo de contar como visto un chunk que no
se vio— queda con red.

## Interfaces con terceros

| Con quién | Cómo | Riesgo |
|---|---|---|
| **Meteor** | API normal. `Module`, `Settings`, `EVENT_BUS` | Meteor desuscribe un módulo **antes** de `onDeactivate()`: cualquier oyente que tenga que sobrevivir a la restauración va suscrito aparte |
| **Baritone** | **Comandos de chat.** Su API está ofuscada y no se puede compilar contra ella | Un comando que Baritone no intercepte **se publica en el chat del servidor**. De ahí la red de seguridad |
| **Trouser Streak** | Leyendo sus ficheros de chunks | Replicamos su saneado de nombres carácter por carácter: si divergiera, leeríamos una carpeta que no existe y daríamos por no visto lo que sí se vio |

**Baritone es la interfaz más frágil de todas.** No hay contrato: hay comandos de chat y un mixin
suyo que puede o no interceptarlos. Todo lo que sabemos de él está verificado leyendo su bytecode, y
está escrito en las specs con el dato exacto (por ejemplo: empieza a aterrizar a **48 bloques** de su
objetivo, valor sacado de una comparación contra `2304.0d`).

## Cómo se verifica que algo funciona

Tres niveles, y los tres hacen falta:

1. **Tests unitarios del núcleo.** ~640 en el repo.
2. **Verificación por mutación.** Un test verde solo demuestra que el test pasa. Antes de dar por
   buena una protección, se rompe a propósito lo que protege y se comprueba que algún test grita. En
   este repo **ha habido tests en verde durante dos rondas protegiendo código que podía romperse sin
   que se enteraran**.
3. **Verificación dentro del juego.** Los adaptadores no tienen tests, y los fallos de interfaz con
   Meteor o Baritone solo aparecen jugando. El caso que lo demuestra: Baritone aterrizaba en cada
   waypoint del patrón y ninguna de las ocho rondas de revisión lo vio, porque no es un fallo de
   nuestra lógica sino de una suposición sobre la suya.

Ver [Convenciones](convenciones.md) para cómo se aplica esto en la práctica.
