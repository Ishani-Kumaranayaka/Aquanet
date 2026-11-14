package aquanet.common;

/**
 * Constants and helpers for the AquaNet wire protocol.
 *
 * TCP (client <-> server), UTF-8, one message per line, fields separated by '|':
 *   Client -> Server
 *     HELLO|name                  introduce yourself (first message)
 *     CMD|PUMP|P1|ON             commands (see NetworkController.execute)
 *     CMD|VALVE|V1|75
 *     CMD|MODE|AUTO              (AUTO or MANUAL)
 *     CMD|LEAK|T2|ON
 *     CHAT|text
 *     PING
 *     QUIT
 *   Server -> Client
 *     WELCOME|clientId|tickMillis
 *     STATE|<snapshot wire string>      (pushed every tick)
 *     ALERT|LEVEL|message               (LEVEL = INFO, WARN, CRIT)
 *     ACK|message
 *     ERR|message
 *     CHAT|from|text
 *     PONG
 *
 * UDP (sensor -> server), one datagram = one text line:
 *     SENSOR|tankId|levelLitres|seq
 *
 * TCP on NODE_PORT (sub-tank program <-> main tank server), UTF-8 lines, '|' separated.
 *   Sub tank -> Server
 *     NODE|id|name|capacityLitres|levelLitres      register (first message). id = 2-16 chars [A-Za-z0-9_]
 *     REPORT|id|levelLitres|demandLps|inflowLps|leak(0/1)   every second (heartbeat + status)
 *     REQUEST|id|litres                             "I am low, please send water"
 *     PING                                          optional, answered by PONG
 *     BYE                                           clean disconnect
 *   Server -> Sub tank
 *     REGISTERED|id|tickMillis                      registration accepted
 *     GRANT|id|litres                               water is on its way: add it to your tank
 *     DENY|id|reason                                request refused (main tank low, supply closed, manual mode)
 *     SUPPLY|id|openPercent                         admin changed the supply valve (0 = closed)
 *     LEAK|id|ON-or-OFF                             admin injects / repairs a leak in this tank
 *     RESET                                         admin reset: go back to your starting level
 *     ERR|message                                   registration refused etc.
 *     PONG
 */
public final class Protocol {
    private Protocol() { }

    public static final int TCP_PORT  = 5000;
    public static final int UDP_PORT  = 5001;
    public static final int HTTP_PORT = 8080;
    /** Sub-tank programs (TankNode) connect to the main tank server here. */
    public static final int NODE_PORT = 5002;
    public static final int TICK_MILLIS = 1000;

    public static final String SEP = "|";
    public static final String SEP_REGEX = "\\|";

    // client -> server
    public static final String HELLO = "HELLO";
    public static final String CMD   = "CMD";
    public static final String CHAT  = "CHAT";
    public static final String PING  = "PING";
    public static final String QUIT  = "QUIT";

    // server -> client
    public static final String WELCOME = "WELCOME";
    public static final String STATE   = "STATE";
    public static final String ALERT   = "ALERT";
    public static final String ACK     = "ACK";
    public static final String ERR     = "ERR";
    public static final String PONG    = "PONG";

    // sub tank <-> main tank
    public static final String NODE = "NODE";
    public static final String REPORT = "REPORT";
    public static final String REQUEST = "REQUEST";
    public static final String BYE = "BYE";
    public static final String REGISTERED = "REGISTERED";
    public static final String GRANT = "GRANT";
    public static final String DENY = "DENY";
    public static final String SUPPLY = "SUPPLY";
    public static final String LEAK = "LEAK";
    public static final String RESET = "RESET";

    // udp
    public static final String SENSOR = "SENSOR";

    /** Split on '|' keeping at most {@code limit} fields (last field keeps any extra '|'). */
    public static String[] split(String line, int limit) {
        return line.split(SEP_REGEX, limit);
    }

    /** Join fields with '|'. */
    public static String join(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(SEP);
            sb.append(fields[i]);
        }
        return sb.toString();
    }

    /** Remove characters that would break the wire format. */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
