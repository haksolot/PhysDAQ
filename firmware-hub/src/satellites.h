#ifndef SATELLITES_H
#define SATELLITES_H

#include <stdint.h>
#include <stdbool.h>

/*
 * Satellite roster — configuration only.
 *
 * A hub can be told which single-PPG nodes belong to it, and remembers that
 * across reboots. It does NOT connect to them, scan for them, or record
 * anything they produce: prj.conf enables BT_PERIPHERAL alone, with
 * BT_MAX_CONN=1, and the hub never takes the central role.
 *
 * That ingestion is Phase 2, and it is blocked on a question this roster does
 * not answer: multi-site PPG correlation is a timing measurement, and until
 * remote timestamps can be resolved onto the hub's time base with a bounded
 * error below roughly one sample period, the resulting dataset does not answer
 * the research question. See docs/firmware-hub.md.
 *
 * What this buys today is that the assignment survives a power cycle and lives
 * on the hub rather than in one particular laptop's config file. The desktop
 * app must say plainly that no satellite data is being recorded yet — a roster
 * that looks like it is doing something is worse than no roster at all.
 *
 * Source IDs are handed out from PPG_SRC_REMOTE_BASE (0x10), which the log
 * format already reserves for remote sources, and the header already marks
 * non-local sources with src_mux_ch = 0xFF. So when ingestion is written, no
 * file-format change is needed.
 */

/* Eight is the number of body positions the desktop app models, so a roster
 * cannot be the thing that runs out first. */
#define SATELLITES_MAX        8

/* "AA:BB:CC:DD:EE:FF" plus NUL. */
#define SATELLITE_ADDR_LEN    18
#define SATELLITE_LABEL_LEN   16

/* Load the roster from persistent storage. Returns 0 on success, or a negative
 * errno if the backend is unavailable — in which case the roster still works
 * for the current boot, it just will not survive a reset. */
int satellites_init(void);

uint8_t satellites_count(void);

typedef void (*satellites_cb)(uint8_t slot, const char *addr,
			      uint8_t source_id, const char *label, void *ctx);

void satellites_foreach(satellites_cb cb, void *ctx);

/* Add or update an entry, keyed on address. `out_source_id` receives the
 * assigned source id. Returns -ENOSPC when the roster is full, -EINVAL on a
 * malformed address. */
int satellites_add(const char *addr, const char *label, uint8_t *out_source_id);

/* Remove one entry. -ENOENT if the address is not in the roster. */
int satellites_remove(const char *addr);

/* Empty the roster. */
int satellites_clear(void);

#endif /* SATELLITES_H */
