#ifndef STORAGE_H
#define STORAGE_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stdbool.h>

/*
 * On-disk log format — binary, fixed-width, little-endian throughout.
 *
 * A file is one 512-byte header followed by a stream of 16-byte records. No
 * CSV anywhere in the hot path; convert offline.
 *
 * Why 16 bytes and a 512-byte header: 32 records make exactly one 512-byte SD
 * block, and the header occupies one block on its own, so in steady state the
 * storage thread hands the card whole aligned blocks. A sync that falls due
 * with a partially filled buffer writes the whole records it has and shifts
 * that alignment — bounding data loss matters more than keeping it, and the
 * FAT layer buffers either way. At two sensors and 100 Hz this is 3.2 kB/s;
 * the constraint is write latency, not bandwidth.
 */

#define LOG_MAGIC           "MAIDLOG1"
#define LOG_FORMAT_VERSION  1
#define LOG_HEADER_SIZE     512
#define LOG_RECORD_SIZE     16
#define LOG_RECORDS_PER_BLOCK  32          /* 32 × 16 = 512 B */
#define LOG_BLOCK_SIZE      (LOG_RECORDS_PER_BLOCK * LOG_RECORD_SIZE)

/*
 * Source identifiers.
 *
 * Deliberately sparse: 0x00-0x0F is reserved for sensors physically on this
 * board, 0x10 and up for remote nodes ingested over the radio in Phase 2.
 * Every record carries one, so a single file can hold local and remote
 * streams interleaved without the reader needing to know which is which.
 */
#define PPG_SRC_LOCAL_0     0x00   /* first local sensor; its mux channel is
                                    * recorded in the header's src_mux_ch, not
                                    * fixed here — see the overlay */
#define PPG_SRC_LOCAL_1     0x01   /* second local sensor */
#define PPG_SRC_REMOTE_BASE 0x10   /* Phase 2 — first remote node */

/* Record flags */
#define PPG_FLAG_BACKDATED  BIT(0)  /* timestamp derived from the ODR, not an
                                     * interrupt capture — true for every
                                     * sample in a batch except the newest */
#define PPG_FLAG_OVERFLOW   BIT(1)  /* the part reported dropped samples since
                                     * the previous drain; there is a gap
                                     * immediately before this record */
#define PPG_FLAG_UNTIMED    BIT(2)  /* recovered on the poll timeout path, so
                                     * dated when the thread ran rather than
                                     * when the sample was taken. Treat the
                                     * timestamp as approximate. */

struct ppg_record {
	uint8_t  source_id;
	uint8_t  flags;
	uint16_t seq;        /* per-source, wraps at 65536 — a discontinuity
	                      * means records were dropped, which is the only
	                      * way a reader can tell loss from a quiet sensor */
	uint32_t t_ticks;    /* ticks since the header's epoch_ticks, at
	                      * tick_hz. 32 bits at 32768 Hz covers ~36 h of
	                      * session before it would wrap. */
	uint32_t red;        /* raw 18-bit count, zero-extended */
	uint32_t ir;
} __packed;

BUILD_ASSERT(sizeof(struct ppg_record) == LOG_RECORD_SIZE,
	     "ppg_record must stay exactly 16 bytes — the 512-byte block "
	     "alignment and every offline reader depend on it");

struct log_header {
	char     magic[8];        /* LOG_MAGIC, not NUL-terminated */
	uint16_t header_size;     /* LOG_HEADER_SIZE */
	uint16_t record_size;     /* LOG_RECORD_SIZE */
	uint16_t format_version;
	uint16_t odr_hz;          /* per sensor */
	uint32_t session_id;      /* the NNNN in SESSNNNN.BIN */
	uint32_t tick_hz;         /* timestamp resolution — 32768 on nRF52 */
	uint64_t epoch_ticks;     /* absolute k_uptime_ticks() at session open;
	                           * record t_ticks are relative to this */
	uint8_t  led_pa;          /* LED pulse amplitude register value */
	uint8_t  spo2_cfg;        /* SPO2_CONFIG register value */
	uint8_t  src_count;
	uint8_t  reserved0;
	uint8_t  src_mux_ch[8];   /* src_mux_ch[i] = mux channel for source i,
	                           * 0xFF for a source that is not on the local
	                           * I2C mux (i.e. a Phase 2 remote node) */
	uint8_t  fw_version[4];   /* major, minor, patch, tweak */

	/* Pads the header out to one full SD block so records begin on a block
	 * boundary. Also leaves room to add fields later without moving the
	 * start of the data — readers key off header_size, not sizeof(). */
	uint8_t  reserved[464];
} __packed;

BUILD_ASSERT(sizeof(struct log_header) == LOG_HEADER_SIZE,
	     "log_header must stay exactly one 512-byte block");

/* Mount the card, pick the next free session file, write its header, and
 * start the storage thread. Returns 0, or a negative errno if the card is
 * absent or unreadable. */
int storage_init(void);

/* Queue one record for writing. Safe to call from the acquisition threads;
 * never blocks. Returns 0, or -ENOMEM if the queue is full, in which case the
 * record is dropped and the drop is counted (see storage_get_stats). */
int storage_submit(const struct ppg_record *rec);

/* Errno from the last storage_init() failure, or 0. Kept because the reason a
 * card was rejected is printed once at boot, and on a USB CDC console the boot
 * output is usually gone before a terminal can attach. */
int storage_last_error(void);

/* True once the card is mounted and the storage thread is running. Says
 * nothing about whether a session file is open — the host can stop and start
 * one at will, so those two states came apart when commands arrived. */
bool storage_is_active(void);

/* True while a session file is open and accepting records. */
bool storage_session_is_open(void);

/* Absolute k_uptime_ticks() value the session was anchored to — the same one
 * written into the header's epoch_ticks. Acquisition subtracts it so each
 * record's t_ticks stays a small 32-bit offset instead of a 64-bit absolute.
 * Returns 0 before storage_init() has opened a session. */
uint64_t storage_epoch_ticks(void);

struct storage_stats {
	uint32_t records_written;
	uint32_t records_dropped;  /* queue full — the storage thread could not
	                            * keep up, which should never happen and is
	                            * the number to watch on an endurance run */
	uint32_t syncs;
	uint32_t write_errors;
	uint32_t max_queue_used;   /* high-water mark, in records */
	uint32_t session_id;
};
void storage_get_stats(struct storage_stats *out);

/* ── Maintenance operations ───────────────────────────────────────────
 *
 * Everything below touches the file system, and the file system has exactly
 * one owner: the storage thread. These calls do not do the work themselves —
 * they hand a request to that thread and block until it comes back.
 *
 * That indirection is the point. `storage_thread_fn()` holds the session file
 * open and is the only writer; a second thread calling fs_* concurrently would
 * race it, and FATFS is not reentrant across a single volume. Routing through
 * the queue also means a listing can never interleave with a half-written
 * 512-byte block.
 *
 * Latency: the storage thread services requests once per loop iteration, and
 * that loop is bounded by CONFIG_PHYSDAQ_SD_SYNC_INTERVAL_MS. Worst case with
 * both sensors quiet is therefore about one sync interval — fine for
 * operations a human triggers, and deliberately not worth restructuring the
 * acquisition loop's timing to improve.
 *
 * All of them return -ENODEV when no card is mounted, which is the normal
 * answer on a board whose card is absent or unreadable.
 */

/** Longest session filename we deal with: "SESS0001.BIN" plus NUL. */
#define STORAGE_NAME_MAX  16

/** Bytes returned per storage_read_chunk() call. One SD block. */
#define STORAGE_CHUNK_MAX  512

/** Card usage, in bytes. */
int storage_stat_card(uint64_t *used, uint64_t *total);

/** Called once per session file found, on the storage thread. Keep it short:
 * it runs inside the directory walk, with the volume held. */
typedef void (*storage_list_cb)(const char *name, uint32_t size, void *ctx);

/** Walk the session files on the card. `count`, if given, receives how many
 * were found — the callback streams entries out as they are read, so no count
 * exists before the walk finishes. */
int storage_list(storage_list_cb cb, void *ctx, uint32_t *count);

/** Delete one session file by bare name (no path). Refuses the session that is
 * currently open — stop it with storage_session_stop() first. */
int storage_delete(const char *name);

/** Delete every session file. Refuses while a session is open, for the same
 * reason as storage_delete().
 *
 * Deliberately not fs_mkfs(): unlinking the files we wrote leaves anything
 * else on the card alone, and reformatting a researcher's card because they
 * clicked "erase" in a PPG app is a surprise nobody wants. */
int storage_format(uint32_t *deleted);

/** Close the current session file, leaving the card mounted. Acquisition keeps
 * running; its records are dropped, and counted as dropped. */
int storage_session_stop(void);

/** Open the next free session file and resume recording. */
int storage_session_start(uint32_t *session_id);

/** Read up to `len` bytes at `offset` from a session file. Returns the byte
 * count, or a negative errno. A short read means end of file. */
int storage_read_chunk(const char *name, uint32_t offset,
		       uint8_t *buf, size_t len);

/* Flush and close the session file cleanly. Call before deep sleep or reset. */
void storage_close(void);

#endif /* STORAGE_H */
