# Seguridad

Qué toca cada módulo, qué se queda escrito y qué puede escaparse. En un servidor anarchy la
ubicación de tu base es lo único irreemplazable: todo lo de aquí está ordenado por lo que cuesta
equivocarse.

---

## 1. Lo que puede llegar al chat del servidor

**El riesgo:** `auto-travel` y `nether-sweep` dirigen a Baritone **escribiendo comandos en el chat**,
porque su API está ofuscada y no se puede compilar contra ella. Si Baritone no intercepta uno, ese
texto **sale al servidor**: acabas de anunciar a todo el mundo que vas con Baritone y hacia dónde.

**Por qué no basta con confiar en Baritone.** Leído en su bytecode, su mixin cancela el comando solo
si su gestor lo reconoce. Quedan dos huecos reales:

1. Que el prefijo no coincida (lo cambiaste en Baritone, o desactivaste el control por prefijo).
2. Que no haya instancia de Baritone ligada a tu jugador — ahí su mixin **retorna sin cancelar**.

**La red.** Mientras un módulo dirige, cancela él mismo todo paquete de chat saliente que empiece por
el prefijo. Cancela **por debajo** de donde cancela Baritone, así que:

- Si Baritone acepta el comando, no llega a existir ningún paquete y la red ni se entera.
- Si no lo acepta, el paquete nace y **muere dentro de tu cliente**.

**Si alguna vez te sale el aviso de que la red canceló algo, léelo**: no es un fallo, es la prueba de
que Baritone no está interceptando y de que cualquier comando que escribas a mano **sí** se
publicaría.

**Dónde vive esa red y por qué.** En un oyente suscrito aparte del módulo, no en un `@EventHandler`
suyo. Meteor desuscribe un módulo **antes** de llamar a su `onDeactivate()`, y la restauración
—seis comandos seguidos— se emite justo ahí. Una red montada sobre el propio módulo estaría muerta
exactamente en el momento de mayor tráfico. Esto costó una ronda entera descubrirlo.

**El prefijo no puede empezar por `/`.** Con barra el comando va por el camino de comando del
servidor, que Baritone no escucha, y la red se comería además todos los demás comandos con barra
mientras durase el vuelo — incluidos los de `auto-tpy` y `kit-requester`. Se rechaza al lanzar.

---

## 2. Tus coordenadas, y dónde viven sin que lo pienses

| Sitio | Qué guarda | Quién lo escribe |
|---|---|---|
| `logs/latest.log` | **Cada objetivo que recibe Baritone**, en claro | Baritone |
| `meteor-client/modules.nbt` | El destino si lo pones en coordenadas absolutas | Meteor |
| `xaero/` | Cientos de MB de terreno mapeado | Xaero |
| `TrouserStreak/NewChunks/` | Qué chunks te han llegado, por servidor y dimensión | Trouser Streak |

**Si compartes un log para que te ayuden a depurar algo, límpialo antes.** Es el fichero más
indiscreto que tienes y el que más fácil se manda.

**Destino relativo en vez de absoluto.** `auto-travel` admite dar el destino como desplazamiento
desde donde estás. Además de ser más cómodo, evita que tus coordenadas acaben escritas en la
configuración de Meteor: un desplazamiento sin saber de dónde no vale nada.

**Y lo que el módulo no puede proteger:** esto cuida la traza de tu vuelo. No cuida que dejes un
túnel, que rompas bloques a la vista, que la base se vea desde el aire o que cargues chunks donde no
toca.

---

## 3. Lo que los módulos escriben fuera de sí mismos

### Ajustes de Baritone — persisten a disco

`auto-travel` y `nether-sweep` escriben cinco ajustes de Baritone al despegar y los devuelven al
aterrizar. **Baritone los guarda en disco**, así que un fallo aquí sobrevive al reinicio.

Los valores de reposo son **los de fábrica reales de Baritone**, leídos de su bytecode — no valores
que nos parecieran razonables. Dos de los que se usaron al principio estaban mal
(`elytraConserveFireworks` es `false` de fábrica, no `true`; `elytraFireworkSpeed` es `1.2`, no `1`)
y habrían dejado todo vuelo manual del jugador más lento para siempre, sin forma de relacionarlo con
el addon.

**Límite conocido:** si te desconectas con el jugador ya nulo, los comandos de restauración no salen
y esos ajustes se quedan con sus valores de vuelo.

### Tu lista de amigos de Meteor — la escribe `auto-pvp`

Los cinco módulos de combate eligen su propio objetivo y el único filtro social que respetan es la
lista de amigos. Así que `auto-pvp`, mientras está encendido, **mete ahí a los tuyos** —couriers de
`kit-requester` y lista de `auto-tpy`— para que ellos tampoco les ataquen.

**Escribe en configuración que es tuya y persiste a disco**, así que:

- Lleva registro de qué nombres puso **él**, y al limpiar **solo quita esos**. Un amigo que ya
  tuvieras nunca se toca.
- Si quitas a mano uno que él puso, no lo vuelve a poner.
- **Si el cliente se cae con el módulo encendido**, lo que puso se queda en `friends.nbt` y en la
  siguiente sesión **no lo borra**: no puede demostrar que fuera suyo. Un `.friends list` de vez en
  cuando.

**Una vía de ataque que conviene conocer:** con `trust-unknown-couriers` encendido en
`kit-requester`, cualquiera que imite el mensaje de kit listo y mande una TPA se añade solo a tu
lista de couriers. Por eso **`auto-pvp` no sincroniza ningún courier mientras ese ajuste esté
encendido** — ni los aprendidos ni los que escribiste tú, porque tras un reinicio son
indistinguibles.

Agujero residual: si lo enciendes, aprendes a alguien y lo vuelves a apagar, ese nombre ya no se
distingue de los tuyos. Repasa `known-couriers` antes de apagarlo.

### Módulos de Meteor prestados

`auto-travel` y `nether-sweep` apagan `elytra-fly` y encienden `elytra-replace` durante el vuelo.
Los devuelven **al estado que tenían**, no a uno declarado, y **un cambio a mano durante el vuelo
manda** sobre lo anotado.

Durante el desmontaje de salida del mundo **no se toca ningún módulo**: Meteor no los marca como
inactivos ahí y su bus de eventos no deduplica, así que encender uno lo dejaría suscrito **dos
veces** el resto de la sesión, con todos sus manejadores ejecutándose por duplicado. La devolución se
aplica en el primer tick tras volver a entrar.

---

## 4. Lo que el módulo nunca hace en tu nombre

- **`auto-pvp` no ejecuta acciones de combate.** Solo enciende y apaga módulos de Meteor.
- **`auto-tpy` nunca acepta `/tpahere`.** Solo trae gente hacia ti; nunca te mueve a ti.
- **`stash-keeper` no mueve nada.** Solo mira.
- **`nether-sweep` no detecta nada.** Solo hace que el terreno pase por delante del cliente.
- **Ningún módulo huye, ni deja de volar porque haya alguien cerca.** Un barrido es una hora lejos de
  casa con todo puesto, y el módulo no gestiona ese riesgo. Barrer es una actividad que se hace con
  lo que estés dispuesto a perder.

---

## 5. El principio de fondo

**Un fallo nunca puede parecerse a un resultado normal.**

Es de donde salen casi todas las decisiones raras del código: por qué un patrón que no cabe se
rechaza en vez de volar recto, por qué una zona mal cubierta avisa fuerte en vez de decir
«terminado», por qué una medida que no existe lanza una excepción en vez de devolver cero.

El caso que mejor lo ilustra: si el barrido separa mal las pasadas, quedan franjas sin mirar y la
zona queda marcada como peinada. **Eso no se descubre nunca** — simplemente no vuelves, y la base que
buscabas estaba ahí.
