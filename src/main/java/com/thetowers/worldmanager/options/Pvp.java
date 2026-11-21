package com.thetowers.worldmanager.options;

import com.thetowers.worldmanager.world.WorldData;
import com.thetowers.worldmanager.world.WorldOption;
import org.bukkit.World;

public class Pvp extends WorldOption {
    public Pvp() {
        super("pvp", "-p", "-p <true or false>", "Enable pvp", true);
    }

    @Override
    public void optionExecutor(String value, WorldData worldData) {
        boolean parsedValue = Boolean.parseBoolean(value);
        worldData.getWorld().setPVP(parsedValue);
        worldData.addOption(this, parsedValue);
    }

    @Override
    public Object getDefaultValue(World world) {
        return world.getPVP();
    }
}
