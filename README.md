# Xploits

Addon de Meteor Client (MC 1.21.11) para 6b6t con tres módulos independientes.

| Módulo | Qué hace |
|---|---|
| `kit-requester` | Pide kits a SnifferBuddy en lotes de hasta 5 y acepta la TPA del courier que los entrega. |
| `auto-tpy` | Acepta al instante las TPA de tu lista y de tus amigos de Meteor. |
| `stash-keeper` | Apunta pasivamente el contenido de los contenedores que abres y de los shulkers que ves, sin mover nada. |

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
```

## Uso

1. Compilar: `./gradlew build`.
2. Copiar `build/libs/xploits-0.1.0.jar` a `mods/`.
3. Los datos (cola de kits y progreso) se guardan en `<instancia>/meteor-client/xploits/`. El índice de
   stash-keeper, por mundo, en `<instancia>/meteor-client/xploits/stash/<mundo>/index.json`.
4. Comandos: `.xploits status`, `.xploits reload`, `.xploits stash` y `.xploits find <ítem>`.

**Si vienes de `kitbot-0.1.0.jar`:** borra ese jar de `mods/` antes de poner el nuevo, o tendrás los módulos duplicados.

- Diseño: [docs/superpowers/specs/2026-09-11-kitrequester-design.md](docs/superpowers/specs/2026-09-11-kitrequester-design.md)
