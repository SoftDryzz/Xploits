package com.xploits.printer.core;

/** Test-only builders for the printer's cores: block facts as the adapter would measure them. */
final class Fixtures {
    private Fixtures() {
    }

    static String ns(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** A plain full block with no properties whose item places it, unless told otherwise. */
    static F block(String id) {
        return new F(ns(id));
    }

    static BlockFacts stone() {
        return block("stone").build();
    }

    static BlockFacts air() {
        return BlockFacts.AIR;
    }

    /** Water as Minecraft reports it: a LEVEL property, replaceable, a fluid, no item that places it. */
    static BlockFacts water() {
        return block("water").props().noItem().notFull().replaceable().fluid().build();
    }

    static Cell cell(int x, int y, int z, Target target, BlockFacts world) {
        return new Cell(new Pos(x, y, z), target, world, true);
    }

    static Cell outside(int x, int y, int z, BlockFacts world) {
        return new Cell(new Pos(x, y, z), Target.AIR, world, false);
    }

    static final class F {
        private final String id;
        private String item;
        private boolean air;
        private boolean propertyFree = true;
        private boolean itemPlacesIt = true;
        private boolean fullCube = true;
        private boolean blockEntity;
        private boolean falling;
        private boolean waterloggable;
        private boolean replaceable;
        private boolean fluid;
        private boolean unbreakable;

        F(String id) {
            this.id = id;
            this.item = id;
        }

        F props() {
            propertyFree = false;
            return this;
        }

        F noItem() {
            itemPlacesIt = false;
            item = "minecraft:air";
            return this;
        }

        F item(String other) {
            item = ns(other);
            return this;
        }

        F notFull() {
            fullCube = false;
            return this;
        }

        F blockEntity() {
            blockEntity = true;
            return this;
        }

        F falling() {
            falling = true;
            return this;
        }

        F waterloggable() {
            waterloggable = true;
            return this;
        }

        F replaceable() {
            replaceable = true;
            return this;
        }

        F fluid() {
            fluid = true;
            return this;
        }

        F unbreakable() {
            unbreakable = true;
            return this;
        }

        F air() {
            air = true;
            replaceable = true;
            fullCube = false;
            itemPlacesIt = false;
            item = "minecraft:air";
            return this;
        }

        BlockFacts build() {
            return new BlockFacts(id, item, air, propertyFree, itemPlacesIt, fullCube, blockEntity, falling, waterloggable,
                replaceable, fluid, unbreakable);
        }
    }
}
