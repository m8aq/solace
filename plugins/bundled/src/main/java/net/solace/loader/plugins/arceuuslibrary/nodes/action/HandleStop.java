package net.solace.loader.plugins.arceuuslibrary.nodes.action;

import net.solace.api.plugins.exception.PluginStoppedException;
import net.solace.loader.plugins.arceuuslibrary.SolaceArceuusLibrary;
import net.solace.loader.plugins.arceuuslibrary.tree.ActionNode;
import net.solace.api.game.Game;
import net.solace.api.items.Bank;
import net.solace.api.items.GrandExchange;
import net.solace.api.widgets.Widgets;

public class HandleStop extends ActionNode {

    public HandleStop(SolaceArceuusLibrary context) {
        super(context);
    }

    @Override
    public int process() {
        if (Game.isLoggedIn()) {
            if (Bank.isOpen() || GrandExchange.isOpen()) {
                Widgets.closeInterfaces();
                return 600;
            }

            Game.logout();
            return 600;
        }

        throw new PluginStoppedException("Plugin due to be stopped.");
    }

    @Override
    public String toString() {
        return "Handle Stop";
    }
}