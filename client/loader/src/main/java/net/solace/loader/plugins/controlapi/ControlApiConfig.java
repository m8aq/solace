package net.solace.loader.plugins.controlapi;

import net.solace.api.plugins.config.Config;
import net.solace.api.plugins.config.ConfigGroup;
import net.solace.api.plugins.config.ConfigItem;
import net.solace.api.plugins.config.Range;

@ConfigGroup(ControlApiConfig.GROUP)
public interface ControlApiConfig extends Config {
    String GROUP = "solacecontrolapi";

    @ConfigItem(
            keyName = "port",
            name = "Port",
            description = "Loopback port for the control API. 0 picks a free port; the one actually "
                    + "bound is recorded in ~/.solace/controlapi/<pid>.json.",
            position = 0
    )
    @Range(min = 0, max = 65535)
    default int port() {
        return 7780;
    }

    @ConfigItem(
            keyName = "commandTimeoutMillis",
            name = "Client thread timeout (ms)",
            description = "How long a command may wait for the client thread before failing.",
            position = 1
    )
    @Range(min = 250, max = 60000)
    default int commandTimeoutMillis() {
        return 5000;
    }

    @ConfigItem(
            keyName = "edtTimeoutMillis",
            name = "EDT timeout (ms)",
            description = "How long a command may wait for the Swing event thread before failing.",
            position = 2
    )
    @Range(min = 250, max = 60000)
    default int edtTimeoutMillis() {
        return 5000;
    }

    @ConfigItem(
            keyName = "screenshotTimeoutMillis",
            name = "Screenshot timeout (ms)",
            description = "How long to wait for the client to render a frame. A minimized window may "
                    + "never render.",
            position = 3
    )
    @Range(min = 250, max = 60000)
    default int screenshotTimeoutMillis() {
        return 5000;
    }

    @ConfigItem(
            keyName = "logHistoryLimit",
            name = "Log history",
            description = "How many log events to retain per plugin for stream replay.",
            position = 4
    )
    @Range(min = 0, max = 5000)
    default int logHistoryLimit() {
        return 500;
    }
}
