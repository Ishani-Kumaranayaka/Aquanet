package aquanet.common;

import java.util.function.Consumer;

/**
 * The contract between the simulation (server package) and everything that
 * wants to look at or control it (TCP client handlers, the web server, tests).
 *
 * Command strings for execute() (fields separated by '|'):
 *   PUMP|P1|ON        PUMP|P1|OFF
 *   VALVE|V1|75       open percent 0..100
 *   MODE|AUTO         MODE|MANUAL
 *   LEAK|T2|ON        LEAK|T2|OFF      (inject / repair a leak)
 *   RESET             refill to the starting state
 * Return value: "OK|message" or "ERR|message".
 * All methods must be thread safe.
 */
public interface NetworkController {
    NetworkSnapshot snapshot();

    /** @param who name of the caller, for logging/alerts (e.g. client name or "web") */
    String execute(String who, String command);

    /** Called once per tick with the new snapshot. */
    void addStateListener(Consumer<NetworkSnapshot> listener);

    /** Called for every alert/event, text form "ALERT|LEVEL|message" without trailing newline. */
    void addEventListener(Consumer<String> listener);
}
