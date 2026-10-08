package dev.minecraftaibot.common;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class BotCore {
    private static final Logger LOG = Logger.getLogger(BotCore.class.getName());
    private final AtomicReference<BotState> state = new AtomicReference<>(BotState.STOPPED);

    public BotState state() {
        return state.get();
    }

    public String start() {
        if (state.compareAndSet(BotState.STOPPED, BotState.RUNNING)) {
            LOG.info("Bot started");
            return "Bot started.";
        }
        return "Bot is already active.";
    }

    public String pause() {
        if (state.compareAndSet(BotState.RUNNING, BotState.PAUSED)) {
            LOG.info("Bot paused");
            return "Bot paused.";
        }
        return "Cannot pause from state " + state.get() + ".";
    }

    public String resume() {
        if (state.compareAndSet(BotState.PAUSED, BotState.RUNNING)) {
            LOG.info("Bot resumed");
            return "Bot resumed.";
        }
        return "Cannot resume from state " + state.get() + ".";
    }

    public String stop() {
        BotState previous = state.getAndSet(BotState.STOPPED);
        if (previous != BotState.STOPPED) {
            LOG.info("Bot stopped");
            return "Bot stopped.";
        }
        return "Bot is already stopped.";
    }
}
