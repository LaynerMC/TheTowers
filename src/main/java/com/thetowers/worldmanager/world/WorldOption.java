package com.thetowers.worldmanager.world;

import com.thetowers.worldmanager.WorldManager;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/**
 * A configurable option of a managed world (environment, type, seed, pvp,
 * difficulty, structures). Each option is registered with the
 * {@link WorldOptionManager} on construction.
 */
public abstract class WorldOption {
    protected String name;
    protected String shortName;
    protected String usage;
    protected String description;
    protected boolean needWorld;
    WorldOptionManager worldOptionManager = WorldManager.getWorldOptionManager();

    public WorldOption(String name, String shortName, String usage, String description, boolean needWorld) {
        this.name = name;
        this.shortName = shortName;
        this.usage = usage;
        this.description = description;
        this.needWorld = needWorld;
        worldOptionManager.addNewOption(this);
    }

    public void optionExecutor(String value, WorldCreator worldCreator) throws Exception {
        throw new UnsupportedOperationException("Impossible to set the value " + value + " with worldcreator");
    }

    public void optionExecutor(String value, WorldData worldData) throws Exception {
        throw new UnsupportedOperationException("Impossible to set the value " + value + " with worldData");
    }

    public boolean isWorldRequired() {
        return this.needWorld;
    }

    public boolean matches(String option) {
        return this.name.equalsIgnoreCase(option) || this.shortName.equalsIgnoreCase(option);
    }

    public abstract Object getDefaultValue(World world);

    public String getName() {
        return this.name;
    }

    public String getShortName() {
        return this.shortName;
    }

    public String getUsage() {
        return this.usage;
    }

    public String getDescription() {
        return this.description;
    }
}
