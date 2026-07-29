package net.solace.loader.plugins.solacedevtools;

import net.solace.api.plugins.config.Config;
import net.solace.api.plugins.config.ConfigGroup;
import net.solace.api.plugins.config.ConfigItem;
import net.solace.api.plugins.config.ConfigSection;
import net.solace.api.plugins.config.Range;

@ConfigGroup("solacedevtools")
public interface SolaceDevToolsConfig extends Config {
    @ConfigSection(
            keyName = "settings",
            position = 0,
            name = "Settings",
            description = "",
            closedByDefault = true
    )
    String displayedInfo = "Settings";

    @ConfigItem(
            keyName = "ids",
            name = "IDs",
            description = "Show ids",
            section = displayedInfo,
            position = 1
    )
    default boolean ids() {
        return true;
    }

    @ConfigItem(
            keyName = "names",
            name = "Names",
            description = "Show names",
            section = displayedInfo,
            position = 2
    )
    default boolean names() {
        return true;
    }

    @ConfigItem(
            keyName = "actions",
            name = "Actions",
            description = "Show actions",
            section = displayedInfo,
            position = 3
    )
    default boolean actions() {
        return true;
    }

    @ConfigItem(
            keyName = "locations",
            name = "World locations",
            description = "Show world locations",
            section = displayedInfo,
            position = 4
    )
    default boolean worldLocations() {
        return true;
    }

    @ConfigItem(
            keyName = "indexes",
            name = "Indexes (actors)",
            description = "Show indexes",
            section = displayedInfo,
            position = 5
    )
    default boolean indexes() {
        return true;
    }

    @ConfigItem(
            keyName = "animations",
            name = "Animations",
            description = "Show animations",
            section = displayedInfo,
            position = 6
    )
    default boolean animations() {
        return true;
    }

    @ConfigItem(
            keyName = "graphics",
            name = "Graphic (actors)",
            description = "Show graphics",
            section = displayedInfo,
            position = 7
    )
    default boolean graphics() {
        return true;
    }

    @ConfigItem(
            keyName = "quantities",
            name = "Quantities (tile items)",
            description = "Show quantities",
            section = displayedInfo,
            position = 8
    )
    default boolean quantities() {
        return true;
    }

    @ConfigItem(
            keyName = "trueWorldLocations",
            name = "True world locations",
            description = "Show true world locations in instances",
            section = displayedInfo,
            position = 9
    )
    default boolean trueWorldLocations() {
        return false;
    }

    @ConfigSection(
            keyName = "tileObjects",
            position = 1,
            name = "Tile objects",
            description = "",
            closedByDefault = true
    )
    String tileObjects = "Tile Objects";

    @ConfigItem(
            keyName = "gameObjects",
            name = "Game objects",
            description = "Render game objects",
            section = tileObjects
    )
    default boolean gameObjects() {
        return false;
    }

    @ConfigItem(
            keyName = "decorObjects",
            name = "Decorative objects",
            description = "Render decorative objects",
            section = tileObjects
    )
    default boolean decorObjects() {
        return false;
    }

    @ConfigItem(
            keyName = "wallObjects",
            name = "Wall objects",
            description = "Render wall objects",
            section = tileObjects
    )
    default boolean wallObjects() {
        return false;
    }

    @ConfigItem(
            keyName = "groundObjects",
            name = "Ground objects",
            description = "Render ground objects",
            section = tileObjects
    )
    default boolean groundObjects() {
        return false;
    }

    @ConfigItem(
            keyName = "tileItems",
            name = "Tile items",
            description = "Render tile items",
            section = tileObjects
    )
    default boolean tileItems() {
        return false;
    }

    @ConfigSection(
            keyName = "actors",
            name = "Actors",
            description = "",
            position = 2,
            closedByDefault = true
    )
    String actors = "Actors";

    @ConfigItem(
            keyName = "npcs",
            name = "NPCs",
            description = "Render NPCs",
            section = actors
    )
    default boolean npcs() {
        return false;
    }

    @ConfigItem(
            keyName = "players",
            name = "Players",
            description = "Render players",
            section = actors
    )
    default boolean players() {
        return false;
    }

    @ConfigSection(
            name = "Others",
            keyName = "others",
            description = "",
            position = 9,
            closedByDefault = true
    )
    String others = "Others";

    @ConfigItem(
            keyName = "inventory",
            name = "Inventory",
            description = "Render inventory",
            section = others,
            position = 1
    )
    default boolean inventory() {
        return false;
    }

    @ConfigItem(
            keyName = "tileLocation",
            name = "Tile location",
            description = "Render tile location",
            section = others,
            position = 2
    )
    default boolean tileLocation() {
        return false;
    }

    @ConfigItem(
            keyName = "path",
            name = "Last path",
            description = "Render calculated path",
            position = 3,
            section = others
    )
    default boolean path() {
        return false;
    }

    @Range(
            max = 5
    )
    @ConfigItem(
            keyName = "radius",
            name = "Detection radius",
            description = "Detection radius",
            section = others,
            position = 4
    )
    default int radius() {
        return 0;
    }

    @ConfigSection(
            name = "Regions",
            keyName = "regions",
            description = "",
            position = 4,
            closedByDefault = true
    )
    String regions = "regions";

    @ConfigItem(
            keyName = "collisionOverlay",
            name = "Show collision overlay",
            description = "Show collision overlay",
            position = 2,
            section = regions
    )
    default boolean collisionOverlay() {
        return false;
    }

    @ConfigItem(
            keyName = "collisionLocalOverlay",
            name = "Show local collision overlay",
            description = "Show local collision overlay",
            position = 2,
            section = regions
    )
    default boolean collisionLocalOverlay() {
        return false;
    }

    @ConfigItem(
            keyName = "transportsOverlay",
            name = "Show transports overlay",
            description = "Show transports overlay",
            position = 3,
            section = regions
    )
    default boolean transportsOverlay() {
        return false;
    }

    @ConfigItem(
            keyName = "pathOverlay",
            name = "Show path overlay",
            description = "Show path overlay",
            position = 4,
            section = regions
    )
    default boolean pathOverlay() {
        return false;
    }

    @ConfigSection(
            keyName = "interaction",
            name = "Interaction",
            description = "",
            position = 5,
            closedByDefault = true
    )
    String interaction = "interaction";

    @ConfigItem(
            keyName = "drawMouse",
            name = "Draw mouse events",
            description = "Draws the sent mouse events on screen",
            section = interaction,
            position = 7
    )
    default boolean drawMouse() {
        return false;
    }

    @ConfigItem(
            keyName = "logMenuInteractions",
            name = "Log menu interactions",
            description = "Records every menu entry to ~/.solace/menu-recorder/ and checks it against "
                    + "the opcode ladders. Right-click emits a whole op array at once",
            section = interaction,
            position = 8
    )
    default boolean logMenuInteractions() {
        return false;
    }
}
