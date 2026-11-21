package com.thetowers.worldmanager.options;

import com.thetowers.worldmanager.world.WorldOption;
import org.bukkit.World;
import org.bukkit.WorldCreator;

public class Environment extends WorldOption {
    public Environment() {
        super("environment", "-e", "-e <environment>", "Environment such as nether, normal..", false);
    }

    @Override
    public void optionExecutor(String value, WorldCreator worldCreator) throws Exception {
        try {
            World.Environment environment = World.Environment.valueOf(value.toUpperCase());
            worldCreator.environment(environment);
        } catch (IllegalArgumentException exception) {
            throw new Exception("The environment " + value + " don't exist");
        }
    }

    @Override
    public Object getDefaultValue(World world) {
        return world.getEnvironment().name();
    }
}
