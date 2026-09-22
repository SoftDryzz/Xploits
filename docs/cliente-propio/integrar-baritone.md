# Integrar Baritone

El addon habla con Baritone **escribiendo comandos en el chat**. Es una chapuza que costó dos rondas
de trabajo y toda una red de seguridad. En un cliente propio **no hay que hacerlo así**, y esta es la
mejora más grande y más barata de toda la migración.

---

## Por qué el addon acabó escribiendo en el chat

Baritone se distribuye en dos formas, y en esta máquina están las dos. Comprobado abriendo los jars:

| Jar | Clases en `baritone.api` | Qué se puede hacer |
|---|---|---|
| `baritone-api-fabric` | **4**: `BaritoneAPI`, `IBaritone`, `IBaritoneProvider`, `Settings` | **Compilar contra ella** |
| `baritone-standalone-fabric` | **1** | Nada: el resto está ofuscado |

El addon se instaló junto al **standalone**, así que no había API contra la que compilar: compilaría
y reventaría en el juego. La única vía que quedaba era el chat.

**Y eso trae un riesgo que no es teórico.** En un servidor anarchy, si un `#elytra` se escapa al
chat público acabas de anunciar a todos que vas con Baritone y hacia dónde. Leído en el bytecode del
mixin de Baritone, este solo cancela el comando si su gestor lo reconoce: si cambiaste el prefijo o
no hay instancia ligada a tu jugador, **el texto sale al servidor**. De ahí la red que cancela
paquetes de chat, que existe solo por esto.

---

## Cómo se hace bien

Con el jar de API como dependencia de compilación:

```java
IBaritoneProvider provider = BaritoneAPI.getProvider();
IBaritone baritone = provider.getPrimaryBaritone();
Settings settings = BaritoneAPI.getSettings();
```

A partir de ahí, todo lo que el addon hace por chat se hace llamando:

| Lo que el addon escribe | Lo que se llama |
|---|---|
| `#set elytraAutoJump true` | El campo del ajuste en `Settings`, con su tipo |
| `#goal x z` | El gestor de objetivos, con un objetivo tipado |
| `#elytra` | El proceso de elytra, directamente |
| `#cancel` | El control de caminos |

**Lo que desaparece de golpe:**

- La red de seguridad entera y su oyente suscrito aparte.
- Todo el guion de comandos y la validación del prefijo.
- El ajuste `baritone-prefix` y la clase de fallo de configurarlo mal.
- La incertidumbre de no saber si un comando llegó: ahora es una llamada, o compila o no.

**Y lo que ganáis además:** leer sus ajustes. Hoy el addon solo puede **escribirlos**, así que declara
valores de reposo en vez de guardar los que había. Con la API se lee el valor antes, se cambia, y se
devuelve el que era — sin declarar nada y sin poder equivocarse.

---

## Los tres problemas que sí tenéis que resolver

**1. La versión.** El jar de API que hay aquí es para una versión antigua de Minecraft. Para 1.21.11
hace falta un build de API de esa versión. **Comprobadlo antes de diseñar nada encima**: si no
existe para vuestra versión, el problema vuelve. Baritone publica por JitPack y hay forks por
versión.

**2. Solo `baritone.api` está soportado.** Lo dice su propio proyecto: todo lo de fuera puede cambiar
sin aviso. Si os apoyáis en algo de dentro, os romperéis en su siguiente release.

**3. Que esté instalado.** Comprobadlo con `FabricLoader.isModLoaded("baritone")` y **no** con la
bandera de Meteor: su `PathManagers` hace un `Class.forName("baritone.api.BaritoneAPI")` que
**falla con el standalone ofuscado**. En estas instancias, todo lo de Meteor que usa Baritone está
muerto en silencio por eso.

---

## Lo que sí conviene conservar del addon

No todo lo que rodea a Baritone era chapuza. Tres piezas resuelven problemas reales que **siguen
existiendo con la API**, porque no son del transporte sino del comportamiento de Baritone:

**Que aterriza en cada objetivo.** Su vuelo con elytra significa «vuela ahí **y pósate**»: empieza a
aterrizar a **48 bloques** del objetivo —valor sacado de su bytecode, una comparación contra
`2304.0d`— y **no tiene ningún ajuste que lo desactive**. Para volar una ruta de varios puntos hay
que cambiarle el objetivo **antes** de que llegue. En el addon eso es `waypoint-margin`; con la API
será lo mismo, pero llamando.

**Que no avisa de nada.** No dice si va bien, si está atascado ni si se quedó sin ruta. El vigilante
de atasco de Xploits —30 segundos sin acercarse, atado al waypoint por construcción para que un
salto de índice no dispare un corte falso— sigue haciendo falta igual.

**Que cambia la elytra por su cuenta.** Tiene sus propios `elytraAutoSwap` y `elytraMinimumDurability`
con otro criterio que el vuestro. Hay que apagarlos si queréis mandar vosotros.

**Y la regla que engloba las tres:** todo lo que sabéis de Baritone está verificado leyendo su
bytecode, no su documentación. Eso no cambia con la API — solo cambia que ya no tenéis que adivinar
si el comando llegó.

---

## Si Baritone no os convence

Merece la pena plantearse la pregunta, porque un cliente propio puede permitirse lo que un addon no:

**Lo que Baritone os da gratis** es enorme: pathfinding, minería, construcción, y un vuelo con elytra
que funciona de verdad. Reescribir eso son años.

**Lo que os cuesta** es una dependencia que no controláis, ofuscada fuera de su API, sin contrato
sobre su comportamiento, que aterriza cuando no quieres y que no informa de nada.

**La postura razonable** es la que ya tiene el addon: usarlo para volar, y **poner vuestra propia
capa de criterio encima** — qué ruta, cuándo cortar, qué hacer si no responde. Esa capa ya la
tenéis escrita y probada, y es la parte que nadie más tiene.
