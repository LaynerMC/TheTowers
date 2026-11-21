package com.thetowers.worldmanager.options;

import com.thetowers.worldmanager.world.WorldOption;
import org.bukkit.World;
import org.bukkit.WorldCreator;

public class Seed extends WorldOption {
    public Seed() {
        super("seed", "-s", "-s <seed>", "Seed of the world", false);
    }

    @Override
    public void optionExecutor(String value, WorldCreator worldCreator) throws Exception {
        try {
            long seed = Long.parseLong(value);
            worldCreator.seed(seed);
        } catch (NumberFormatException exception) {
            throw new Exception("§cInvalid seed: " + value + " Need to contain numbers only.");
        }
    }

    @Override
    public Object getDefaultValue(World world) {
        return world.getSeed();
    }
}
