package net.solace.loader.controlapi.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import lombok.RequiredArgsConstructor;

/**
 * Taps the root logger. logback is a compile-scope dependency of {@code net.runelite:client}, so no
 * new dependency is needed - but the root logger is only a logback {@code Logger} when logback is the
 * bound SLF4J backend, which the installer checks before attaching this.
 */
@RequiredArgsConstructor
public final class PluginLogAppender extends AppenderBase<ILoggingEvent> {
    private final PluginLogService service;

    @Override
    protected void append(ILoggingEvent event) {
        var throwable = event.getThrowableProxy() == null
                ? null
                : ThrowableProxyUtil.asString(event.getThrowableProxy());

        service.publish(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                throwable);
    }
}
