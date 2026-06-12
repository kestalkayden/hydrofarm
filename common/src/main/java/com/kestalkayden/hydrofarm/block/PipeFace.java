package com.kestalkayden.hydrofarm.block;

import net.minecraft.util.StringRepresentable;

/** Per-face connection state of a pipe terminal, stored as a blockstate {@code EnumProperty} on
 *  each of the six faces. This single enum drives the multipart model (arm vs. extract/insert
 *  nozzle), the collision shape, and the transfer logic — no block-entity sync needed for any of
 *  it, exactly like the boolean arm props on a plain pipe.
 *
 *  <ul>
 *    <li>{@link #NONE}    — nothing attached on this face.</li>
 *    <li>{@link #PIPE}    — a neighbouring pipe/terminal; renders the same arm a plain pipe does.</li>
 *    <li>{@link #EXTRACT} — a configured pull port: items/fluid are drawn FROM the neighbour here.</li>
 *    <li>{@link #INSERT}  — a configured push port: items/fluid are pushed INTO the neighbour here.</li>
 *  </ul>
 *
 *  {@code EXTRACT}/{@code INSERT} faces carry a filter + whitelist flag in the terminal's BE,
 *  keyed by {@link net.minecraft.core.Direction}; {@code NONE}/{@code PIPE} faces carry no BE state. */
public enum PipeFace implements StringRepresentable {
    NONE("none"),
    PIPE("pipe"),
    PORT("port"),
    EXTRACT("extract"),
    INSERT("insert");

    private final String name;

    PipeFace(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** True for {@link #EXTRACT}/{@link #INSERT} — the configured states that actually transfer and
     *  carry BE filter data. {@link #PORT} touches an inventory but is inert until configured. */
    public boolean isPort() {
        return this == EXTRACT || this == INSERT;
    }

    /** True when this face touches an inventory in any state — a gray {@link #PORT} nozzle, or a
     *  configured {@link #EXTRACT}/{@link #INSERT} one. Drives the auto-appearing nozzle geometry. */
    public boolean isInventory() {
        return this == PORT || this == EXTRACT || this == INSERT;
    }
}
