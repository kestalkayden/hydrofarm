package com.kestalkayden.hydrofarm.compat;

import com.kestalkayden.hydrofarm.block.HydroponicsBedBlock;
import com.kestalkayden.hydrofarm.block.ItemPipeTerminalBlock;
import com.kestalkayden.hydrofarm.block.LiquidPipeTerminalBlock;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade entry point — registered via the "jade" entrypoint in fabric.mod.json. Hooks our providers
 *  into Jade's tooltip pipeline. Class only loads when Jade is present, so it has no effect on
 *  players who don't have Jade installed. */
@WailaPlugin("hydrofarm")
public class HydrofarmJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new HydroponicsBedJadeProvider(), HydroponicsBedBlock.class);

        PipeTerminalJadeProvider terminals = new PipeTerminalJadeProvider();
        registration.registerBlockComponent(terminals, ItemPipeTerminalBlock.class);
        registration.registerBlockComponent(terminals, LiquidPipeTerminalBlock.class);
    }
}
