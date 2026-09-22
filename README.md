# Xploits

Addon de Meteor Client (MC 1.21.11) para 6b6t con siete módulos independientes.

| Módulo | Qué hace |
|---|---|
| `kit-requester` | Pide kits a SnifferBuddy en lotes de hasta 5 y acepta la TPA del courier que los entrega. |
| `auto-tpy` | Acepta al instante las TPA de tu lista y de tus amigos de Meteor. |
| `stash-keeper` | Apunta pasivamente el contenido de los contenedores que abres y de los shulkers que ves, sin mover nada. |
| `elytra-replace` | Cambia la elytra puesta por una de repuesto antes de que se rompa y avisa con toast y sonido si no hay ninguna válida. Funciona sin ElytraFly. |
| `auto-pvp` | Dirige los módulos de combate de Meteor por dos ejes a la vez —en qué fase está el enemigo y qué te está apuntando a ti—, y solo apaga los que encendió él. No ejecuta ninguna acción de combate. Nunca elige como objetivo a los tuyos, y mientras está encendido los mete en tu lista de amigos de Meteor para que los cinco módulos que eligen su propio objetivo tampoco les ataquen. |
| `auto-travel` | Prepara el entorno, lanza el vuelo con elytra de Baritone por una ruta con patrón de despiste (zigzag, quiebro, espiral o señuelo) y lo restaura todo al aterrizar. Encenderlo no vuela: el viaje se lanza con `.xploits travel go`. **Requiere Baritone**. |
| `nether-sweep` | Barre un rectángulo del Nether con pasadas de cortacésped, volando con elytra de Baritone solo lo que `NewerNewChunks` todavía no ha registrado. Encenderlo no vuela: el barrido se lanza con `.xploits sweep go`. **Este módulo vuela, no detecta**: ver la nota más abajo. **Requiere Baritone**. |

## Estructura de paquetes

Agrupada por función, bajo `com.xploits`:

```
com/xploits/                    Addon: registro de módulos y comando (XploitsAddon).
com/xploits/commands/           Comando .xploits (XploitsCommand).
com/xploits/shared/chat/        Protocolo de chat de SnifferBuddy, compartido por ambos módulos.
com/xploits/kitrequester/       Módulo kit-requester (adaptador a Meteor).
com/xploits/kitrequester/core/  Máquina de estados, cola de kits y progreso de kit-requester.
com/xploits/kitrequester/inventory/  Vaciado de shulkers en el ender chest.
com/xploits/autotpy/            Módulo auto-tpy (adaptador a Meteor).
com/xploits/autotpy/core/       Política de aceptación de TPA de auto-tpy.
com/xploits/stash/              Módulo stash-keeper (adaptador a Meteor).
com/xploits/stash/core/         Índice de contenedores, claves y búsqueda de stash-keeper.
com/xploits/elytra/             Módulo elytra-replace (adaptador a Meteor).
com/xploits/elytra/core/        Política de cambio de elytra-replace.
com/xploits/pvp/                Módulo auto-pvp (adaptador a Meteor).
com/xploits/pvp/core/           Máquina de fases, postura defensiva, catálogo de módulos dirigidos, quién es
                                de los nuestros y qué se escribe en la lista de amigos de Meteor, de auto-pvp.
com/xploits/travel/             Módulo auto-travel (adaptador a Meteor).
com/xploits/travel/core/        Geometría de la ruta, patrones de despiste y comandos de Baritone de auto-travel.
com/xploits/sweep/              Módulo nether-sweep (adaptador a Meteor).
com/xploits/sweep/core/         Geometría del rectángulo, planificador de pasadas, cobertura, presupuesto de
                                cohetes y sonda de anchura de nether-sweep.
```

## Uso

1. Compilar: `./gradlew build`.
2. Copiar `build/libs/xploits-0.1.0.jar` a `mods/`.
3. Los datos (cola de kits y progreso) se guardan en `<instancia>/meteor-client/xploits/`. El índice de
   stash-keeper, por mundo, en `<instancia>/meteor-client/xploits/stash/<mundo>/index.json`.
4. Comandos: `.xploits status`, `.xploits reload`, `.xploits stash`, `.xploits find <ítem>`, `.xploits pvp`,
   `.xploits travel` (sin más, el estado del viaje; `go` lo lanza, `stop` lo corta y restaura) y `.xploits sweep`
   (sin más, el estado del barrido; `go` lo lanza, `stop` lo corta y restaura).

**`auto-travel` y `nether-sweep` requieren Baritone** instalado junto a Meteor: son los dos únicos módulos del addon
que lo usan, y los otros cinco funcionan igual sin él. Los dos dirigen el vuelo con elytra de Baritone por sus
comandos de chat, así que sin Baritone cargado se niegan a lanzar nada y lo dicen. Si has cambiado el prefijo de
Baritone, cámbialo también en el ajuste `baritone-prefix` de cada módulo por separado —cada uno tiene el suyo—. Ese
prefijo **no puede empezar por `/`**: con barra el comando iría por el camino de comando del servidor, que Baritone
no escucha, y la red de seguridad del módulo se comería de paso todos los demás comandos con barra mientras durase
el viaje o el barrido. Cada módulo lo rechaza al lanzar y explica por qué.

**`nether-sweep` vuela; no detecta nada.** El módulo solo consigue que el terreno pase por delante del cliente
—vuela un rectángulo del Nether con pasadas de cortacésped—, pero quien registra lo que ve son otros tres mods, y
tienen que estar **encendidos** para que el barrido sirva de algo: `BaseFinder` (de trouser-streak), `stash-finder`
(de Meteor) y `NewerNewChunks` (de trouser-streak, y además es de donde `nether-sweep` lee qué chunks ya están
vistos para no repetirlos). Si alguno está apagado o no está instalado, el barrido lo avisa fuerte justo antes de
despegar —con toast y en el chat, uno por cada detector que falte— pero **no** se niega a lanzar por eso: si sigues
adelante, el barrido puede volar una hora entera y no registrar nada.

**Antes de lanzar un viaje** tienes que llevar la elytra puesta y algún fuego artificial: `elytra-replace` cambia la
elytra que lleves, pero no te pone ninguna, y sin fuegos Baritone no despega. Y si tienes `elytra-fly` encendido con
su `chest-swap` en `Always` o `WaitForGround`, el módulo **se niega a lanzar**: la preparación tiene que apagar
`elytra-fly`, y apagarlo con `chest-swap` puesto te cambia la elytra por la pechera —justo antes del despegue, o en
el aterrizaje—. Pon `chest-swap` en `Never` o apaga `elytra-fly` a mano.

**Al aterrizar**, `elytra-fly` y `elytra-replace` vuelven al estado que tenían antes del viaje, no a uno declarado; si
los mueves a mano durante el vuelo, se respeta lo que tú dejaste. La única excepción es desconectarte con el viaje en
marcha: ahí Meteor está desmontando sus módulos y tocarlos los dejaría rotos para el resto de la sesión, así que la
devolución se hace sola en el primer tick tras volver a entrar. Si cierras el cliente antes de volver a entrar, se
quedan como estaban en vuelo y el módulo te lo dice con un toast.

**El patrón se vuela sin aterrizar en cada waypoint.** `#elytra` le dice a Baritone "vuela hasta el objetivo **y
pósate**": en cuanto entra en los 48 bloques de su objetivo da la ruta por terminada y se pone a buscar sitio donde
aterrizar, y no tiene ningún ajuste que lo desactive. Por eso el módulo le cambia el objetivo **antes** de que
llegue: el ajuste `waypoint-margin` (150 bloques de fábrica, mínimo 100) es cuántos bloques antes de cada waypoint
intermedio se le pasa al siguiente. Sólo el **último** waypoint —el destino real— se deja llegar, que es donde
aterrizar es justo lo que quieres.

El precio es que las esquinas del patrón se redondean: nunca llegas a tocar el vértice, así que con el zigzag de
fábrica te quedas a unos 29 bloques de sus 200 de amplitud (el 15 %) y con el quiebro a unos 46 de sus 800 (el 6 %).
Subir `waypoint-margin` redondea más; bajarlo acerca el aterrizaje.

**Y por eso hay patrones que ahora se rechazan.** Dos waypoints seguidos tienen que estar al menos al doble del
margen —300 bloques de fábrica, que es también lo más corto que una elytra con cohetes vuela como tramo en vez de
como bamboleo—. Si tus ajustes los dejan más juntos, el módulo **no vuela y dice a cuánto subir qué**: pasa con un
zigzag o un quiebro de paso y amplitud pequeños, con un señuelo en un viaje muy corto, y con la espiral en modo
autopista si dejas el `highway-max-amplitude` de fábrica (300), porque una espiral que no puede apartarse más de 300
bloques del eje deja pasos de 8 —súbelo a 338 o más—. La espiral sí recorta por su cuenta sus últimos grados: ahí el
radio ya va camino de cero y no hay ajuste que lo haga volable, así que se deja de emitir ese tramo y el resto de la
curva se vuela entera.

**Si vienes de `kitbot-0.1.0.jar`:** borra ese jar de `mods/` antes de poner el nuevo, o tendrás los módulos duplicados.

**`auto-pvp` decide dos cosas a la vez, no una.** «Me están cristaleando» y «él está rodeado» son
verdad al mismo tiempo, así que meterlas en una sola fase obligaba a elegir entre atacar y
defenderte. Ahora hay dos ejes y se enciende la **unión** de lo que pide cada uno:

- **La fase**, que sale del enemigo: `SIN_COMBATE`, `ACERCAMIENTO`, `SUPERFICIE`, `RODEADO`,
  `ENTERRADO`, `PERSECUCION`. Enciende lo ofensivo —`crystal-aura`, `auto-trap`, `auto-web`,
  `auto-anvil`, `auto-city`— y nunca fuera del alcance real de cada módulo.
- **La postura**, que sales tú: `TRANQUILO` o `AMENAZADO`. Es `AMENAZADO` cuando el daño que **ya
  está colocado** contra ti te dejaría por debajo de `threat-margin` (12 de fábrica), y entonces
  enciende `hole-filler`, `anti-anvil`, `anti-bed` y `anti-anchor` —ninguno te inmoviliza: tapan
  formas concretas de matarte— y además `surround` **solo** si estás en un agujero y pisando suelo,
  que es su único sitio. `self-trap`, `self-web` y `burrow` no se dirigen a propósito: te encierran,
  y si el criterio se equivoca te inmoviliza tu propio cliente en una pelea que ibas ganando.

`.xploits pvp` enseña las dos en la primera línea, con tu vida, el daño que te apunta y el margen
debajo. En el chat cada eje avisa por su cuenta y solo al cambiar.

**El aura casi nunca se apaga.** `crystal-aura` es la única dirigida con una mitad útil a coste cero
—rompe los cristales que te ponen, y romper no gasta ninguno tuyo—, que es justo lo que te mantiene
vivo cuando no tienes con qué responder. Así que queda fuera del filtro de recursos: sin cristales
sube igual y te lo dice como aviso, no como omisión. Y si hay alguien a rango de cristal sin
proteger, se queda encendida aunque la fase sea `ENTERRADO`: el cebo obvio era que uno se enterrase
mientras el otro te cristaleaba. Lo único que todavía la puede apagar es no llevar tótems **y** tener
el `anti-suicide` de `crystal-aura` apagado; con `anti-suicide` puesto —viene puesto— Meteor ya se
niega por su cuenta a colocar o romper un cristal que te mate, y lo mide con el daño exacto en vez de
con un contador de ítems.

**`auto-pvp` no ataca nunca a los tuyos:** tus amigos de Meteor, los couriers de `kit-requester`
(su ajuste `known-couriers`) y la lista `users` de `auto-tpy`. El trato es el mismo que Meteor da a
su lista de amigos, **incondicional**: no es "no iniciar pero responder si te pega", es que no les
ataca. Sin esto, el courier que tú mismo invitas con `/tpy` aparecía pegado a ti y se comía tus
cristales, tu trap y tus telarañas, y el pedido se perdía. Las tres listas cuentan **estén esos
módulos encendidos o apagados**: la lista dice de quién te fías, no qué módulo está funcionando, y
un courier de un pedido anterior sigue pegado a ti después de que `kit-requester` se apague. Los
nombres son exactos y distinguen mayúsculas, igual que en los ajustes de los otros dos módulos; solo
se perdonan los espacios de alrededor.

Tener uno de los tuyos a tiro **no deja a `auto-pvp` sin objetivo**: se descarta antes de elegir, así
que un enemigo de verdad diez bloques detrás del courier sigue siendo el objetivo. Cuando descarta a
alguien lo dice una vez en el chat (con `notify` puesto) y lo muestra siempre en `.xploits pvp`.

**Y para que los otros cinco módulos tampoco les ataquen, `auto-pvp` escribe en tu lista de amigos de
Meteor.** `auto-pvp` no ataca: enciende módulos, y los cinco que dirige —`crystal-aura`, `auto-trap`,
`auto-web`, `auto-anvil` y `auto-city`— eligen **su propio** objetivo, y el único filtro social que
conocen es la lista de amigos de Meteor. Con un courier pegado a ti, `auto-pvp` elegía bien al
enemigo, encendía `auto-web`, y `auto-web` elegía por su cuenta al más cercano —el courier— y lo
entelaba. Así que mientras `auto-pvp` está encendido añade a `.friends` a los couriers y a la lista
`users`, y los quita al apagarse. Lo gobierna el ajuste `sync-friends` (encendido de fábrica):

- **Solo quita lo que puso él.** A un amigo que ya tuvieras no lo toca nunca, ni al poner ni al
  quitar. Si le borras a mano uno de los suyos, se da por enterado y no lo vuelve a poner.
- **Solo escribe cuando algo cambia** —editas las listas, `kit-requester` aprende un courier,
  enciendes o apagas el módulo—, porque cada alta o baja de amigo guarda `friends.nbt` en el disco.
- **Con `trust-unknown-couriers` encendido no sincroniza ningún courier.** Ese ajuste deja que
  cualquiera que imite el mensaje READY entre solo en `known-couriers`; sin esta excepción, una línea
  de chat metería a un desconocido en tu lista de amigos y los cinco módulos dejarían de tocarle. Si
  lo enciendes, aprendes a alguien y lo vuelves a apagar, ese nombre ya no se distingue de los tuyos:
  repasa `known-couriers` antes.
- Con la sincronización apagada, `auto-pvp` sigue sin atacar a los tuyos, pero los otros cinco
  módulos sí pueden. `.xploits pvp` te dice en qué situación estás.

**Tras un cierre anormal del cliente** (cuelgue, kill del proceso, corte de luz), Meteor persiste el estado de sus módulos tal como quedó. Si `auto-pvp` tenía algo tomado en ese momento, conviene mirar la ClickGUI al volver a entrar: puede haber quedado un módulo encendido que `auto-pvp` creía suyo pero que no va a soltar hasta que vuelva a decidir hacerlo. Y si estaba sincronizando amigos, los que hubiera puesto se quedan en tu lista: el addon no los borrará en la siguiente sesión porque no puede demostrar que fueran suyos, así que repásala con `.friends list`.

- Diseño: [docs/superpowers/specs/2026-09-11-kitrequester-design.md](docs/superpowers/specs/2026-09-11-kitrequester-design.md)
