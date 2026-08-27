#ifndef COMMAND_H
#define COMMAND_H

#include <stddef.h>
#include <stdint.h>

/*
 * Downlink command channel.
 *
 * The host sends newline-terminated ASCII lines; the hub answers with
 * newline-terminated ASCII lines on the same transports it already streams
 * over. Both directions are text for the same reason the sample stream is:
 * one format works over BLE and USB alike, and it is readable in a terminal
 * when something goes wrong.
 *
 * Requests, all prefixed "CMD ":
 *
 *   CMD id                      re-send the identity line
 *   CMD sd.stat                 card usage and session state
 *   CMD sd.list                 enumerate session files
 *   CMD sd.del <name>           delete one session file
 *   CMD sd.format               delete every session file
 *   CMD rec.start               open the next session file
 *   CMD rec.stop                close the current session file
 *   CMD sd.get <name> [offset]  stream a session file back, base64
 *   CMD sd.abort                stop an in-flight sd.get
 *   CMD sat.list                list configured satellites
 *   CMD sat.add <addr> [label]  remember a satellite
 *   CMD sat.del <addr>          forget one
 *   CMD sat.clear               forget all
 *
 * Replies are documented in docs/protocol.md. None of them begins with "PPG",
 * which matters: the host parser discards any unmatched line starting with
 * those three characters.
 */

/* Start the command thread. Safe to call whether or not a card mounted —
 * storage commands answer with an error rather than going missing. */
int command_init(void);

/* Emit the identity line on both transports. Lives here rather than in main.c
 * because it is part of the host protocol this module owns, and because "CMD
 * id" has to be able to re-send it on demand. */
void command_send_identity(void);

/* Feed bytes arriving from a transport. Called from the BLE write callback and
 * from the console reader; assembles lines and queues the complete ones.
 * Never blocks, and never touches the file system. */
void command_feed(const uint8_t *data, size_t len);

#endif /* COMMAND_H */
