#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/sys/base64.h>
#include <zephyr/sys/crc.h>
#include <zephyr/console/console.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>

#include "command.h"
#include "storage.h"
#include "satellites.h"
#include "ppg.h"
#include "ble.h"
#include "version.h"

/* Longest command line accepted. Nothing in the grammar comes close; the cap
 * exists so a transport that never sends a newline cannot grow a buffer. */
#define CMD_LINE_MAX   96
#define CMD_QUEUE_LEN  4

#define CMD_STACK_SIZE 3072   /* base64 chunk buffers plus vsnprintk */
/* Below storage (7) so a queued command can never be why a card write is late,
 * and far below the acquisition threads (4). Nothing here is time-critical —
 * every one of these is triggered by a human. */
#define CMD_PRIORITY   8

K_MSGQ_DEFINE(cmd_q, CMD_LINE_MAX, CMD_QUEUE_LEN, 4);

static K_THREAD_STACK_DEFINE(cmd_stack, CMD_STACK_SIZE);
static struct k_thread cmd_thread;

static volatile bool download_abort;

/* ── Line assembly ────────────────────────────────────────────────────
 *
 * One buffer, shared by every transport. A hub has one host: the USB console
 * and the BLE link are two ways for that host to reach it, not two independent
 * operators, so interleaving is not a case worth carrying state for. Written
 * from the BLE write callback, hence a spinlock — that callback must not
 * block.
 */
static char              line_buf[CMD_LINE_MAX];
static size_t            line_len;
static struct k_spinlock line_lock;

/* Abort is recognised here rather than in the dispatcher, and this is not an
 * optimisation — it is the only place it can work.
 *
 * A download runs inside the command thread's dispatch call, so that thread is
 * busy for the whole transfer and will not pick another line off the queue
 * until it finishes. An abort routed the normal way could therefore only be
 * read once there was nothing left to abort. Recognising it during line
 * assembly costs one strcmp on a complete line and takes effect between the
 * very next pair of chunks.
 */
static bool is_abort_line(const char *line)
{
	return strcmp(line, "CMD sd.abort") == 0;
}

void command_feed(const uint8_t *data, size_t len)
{
	K_SPINLOCK(&line_lock) {
		for (size_t i = 0; i < len; i++) {
			char c = (char)data[i];

			if (c == '\r') {
				continue;
			}

			if (c == '\n') {
				if (line_len > 0) {
					line_buf[line_len] = '\0';

					if (is_abort_line(line_buf)) {
						download_abort = true;
					} else {
						/* K_NO_WAIT: may run in the BLE
						 * write callback. A full queue
						 * drops the command, which the
						 * host sees as a missing reply
						 * and can retry — better than
						 * blocking the radio. */
						(void)k_msgq_put(&cmd_q,
								 line_buf,
								 K_NO_WAIT);
					}
				}
				line_len = 0;
				continue;
			}

			if (line_len < sizeof(line_buf) - 1) {
				line_buf[line_len++] = c;
			} else {
				/* Overlong line: drop it whole rather than
				 * dispatch a truncated prefix, which could be
				 * a different valid command. */
				line_len = 0;
			}
		}
	}
}

/* ── Replies ──────────────────────────────────────────────────────────── */

static void reply(const char *fmt, ...)
{
	char    line[224];
	va_list ap;

	va_start(ap, fmt);
	int n = vsnprintk(line, sizeof(line) - 1, fmt, ap);
	va_end(ap);

	if (n < 0) {
		return;
	}
	if (n > (int)sizeof(line) - 2) {
		n = (int)sizeof(line) - 2;
	}
	line[n++] = '\n';
	line[n] = '\0';

	printk("%s", line);
	ble_send((const uint8_t *)line, n);
}

/* Errno as a number, not a string: the host can branch on it, and the app
 * turns the handful that matter into readable text. */
static void reply_err(const char *verb, int err)
{
	reply("SD err %s %d", verb, err < 0 ? -err : err);
}

void command_send_identity(void)
{
	/* `sd=` reports whether the card is usable, not whether the board has
	 * a slot: a hub with an unseated card must not advertise storage the
	 * app would then offer to browse. */
	reply("ID model=hub proto=%d fw=%s ppg=%d sd=%d name=%s",
	      PHYSDAQ_AD_PROTO_VERSION, PHYSDAQ_FW_STRING, PPG_SENSOR_COUNT,
	      storage_is_active() ? 1 : 0, ble_device_name());
}

/* ── Storage commands ─────────────────────────────────────────────────── */

static void cmd_sd_stat(void)
{
	if (!storage_is_active()) {
		reply("SD stat mounted=0 err=%d", storage_last_error());
		return;
	}

	uint64_t used = 0, total = 0;
	int ret = storage_stat_card(&used, &total);

	if (ret != 0) {
		reply_err("sd.stat", ret);
		return;
	}

	struct storage_stats st;

	storage_get_stats(&st);
	reply("SD stat mounted=1 used=%llu total=%llu sess=%u open=%d "
	      "w=%u drop=%u err=%u",
	      used, total, st.session_id, storage_session_is_open() ? 1 : 0,
	      st.records_written, st.records_dropped, st.write_errors);
}

static void list_entry(const char *name, uint32_t size, void *ctx)
{
	ARG_UNUSED(ctx);
	reply("SD file %s %u", name, size);
}

static void cmd_sd_list(void)
{
	if (!storage_is_active()) {
		reply_err("sd.list", -ENODEV);
		return;
	}

	/* Entries stream out from inside the directory walk, so the "end" line
	 * is what tells the host the listing is complete — there is no count
	 * available without walking the directory twice. */
	uint32_t n = 0;
	int ret = storage_list(list_entry, NULL, &n);

	if (ret != 0) {
		reply_err("sd.list", ret);
		return;
	}
	reply("SD list end n=%u", n);
}

static void cmd_sd_del(const char *name)
{
	int ret = storage_delete(name);

	if (ret != 0) {
		reply_err("sd.del", ret);
		return;
	}
	reply("SD ok sd.del %s", name);
}

static void cmd_sd_format(void)
{
	uint32_t deleted = 0;
	int ret = storage_format(&deleted);

	if (ret != 0) {
		reply_err("sd.format", ret);
		return;
	}
	reply("SD ok sd.format n=%u", deleted);
}

static void cmd_rec_start(void)
{
	uint32_t id = 0;
	int ret = storage_session_start(&id);

	if (ret != 0) {
		reply_err("rec.start", ret);
		return;
	}
	reply("REC state=on sess=%u", id);
}

static void cmd_rec_stop(void)
{
	int ret = storage_session_stop();

	if (ret != 0) {
		reply_err("rec.stop", ret);
		return;
	}
	reply("REC state=off");
}

/* ── File download ────────────────────────────────────────────────────
 *
 * base64 over the same ASCII line protocol as everything else. That costs 33 %
 * in size, which matters here: a one-hour session is ~11 MB, so ~50 min over
 * BLE and ~20 min over USB CDC. The app is expected to steer the operator to
 * USB and to show the estimate before starting.
 *
 * Chunked and interruptible rather than one blocking loop, so an abort takes
 * effect promptly and the sample stream keeps flowing in between.
 */
static void cmd_sd_get(const char *name, uint32_t offset)
{
	uint8_t raw[STORAGE_CHUNK_MAX];
	/* base64 of 512 B is 684 B; +1 for the NUL base64_encode writes. */
	char    enc[((STORAGE_CHUNK_MAX + 2) / 3) * 4 + 1];

	if (!storage_is_active()) {
		reply_err("sd.get", -ENODEV);
		return;
	}

	download_abort = false;
	uint32_t crc  = 0;
	uint32_t sent = 0;

	while (true) {
		if (download_abort) {
			reply("SD data abort %s %u", name, offset + sent);
			return;
		}

		int n = storage_read_chunk(name, offset + sent, raw,
					   sizeof(raw));

		if (n < 0) {
			reply_err("sd.get", n);
			return;
		}
		if (n == 0) {
			break;   /* end of file */
		}

		crc = crc32_ieee_update(crc, raw, (size_t)n);

		size_t enc_len = 0;

		if (base64_encode((uint8_t *)enc, sizeof(enc), &enc_len,
				  raw, (size_t)n) != 0) {
			reply_err("sd.get", -ENOMEM);
			return;
		}
		enc[enc_len] = '\0';

		reply("SD data %s %u %d %s", name, offset + sent, n, enc);
		sent += (uint32_t)n;

		/* Yield between chunks. Without this the command thread holds
		 * the CPU for the whole transfer and the ~25 Hz sample stream
		 * stalls for minutes — a live view going dead during a
		 * download looks exactly like a crash. */
		k_sleep(K_MSEC(2));

		if ((size_t)n < sizeof(raw)) {
			break;   /* short read: end of file */
		}
	}

	reply("SD data end %s %u crc=%08x", name, sent, crc);
}

/* ── Satellite roster ─────────────────────────────────────────────────── */

static void sat_entry(uint8_t slot, const char *addr, uint8_t source_id,
		      const char *label, void *ctx)
{
	ARG_UNUSED(ctx);
	reply("SAT entry %u addr=%s src=0x%02X label=%s",
	      slot, addr, source_id, label);
}

static void cmd_sat_list(void)
{
	reply("SAT n=%u max=%u", satellites_count(), SATELLITES_MAX);
	satellites_foreach(sat_entry, NULL);
	reply("SAT list end");
}

static void cmd_sat_add(const char *addr, const char *label)
{
	uint8_t source_id = 0;
	int ret = satellites_add(addr, label, &source_id);

	if (ret != 0) {
		reply("SAT err sat.add %d", ret < 0 ? -ret : ret);
		return;
	}
	reply("SAT ok sat.add %s src=0x%02X", addr, source_id);
}

static void cmd_sat_del(const char *addr)
{
	int ret = satellites_remove(addr);

	if (ret != 0) {
		reply("SAT err sat.del %d", ret < 0 ? -ret : ret);
		return;
	}
	reply("SAT ok sat.del %s", addr);
}

static void cmd_sat_clear(void)
{
	int ret = satellites_clear();

	if (ret != 0) {
		reply("SAT err sat.clear %d", ret < 0 ? -ret : ret);
		return;
	}
	reply("SAT ok sat.clear");
}

/* ── Dispatch ─────────────────────────────────────────────────────────── */

/* Split off the next whitespace-delimited token, NUL-terminating it in place
 * and advancing *cursor past it. NULL when nothing is left. */
static char *next_token(char **cursor)
{
	char *p = *cursor;

	while (*p == ' ' || *p == '\t') {
		p++;
	}
	if (*p == '\0') {
		*cursor = p;
		return NULL;
	}

	char *start = p;

	while (*p && *p != ' ' && *p != '\t') {
		p++;
	}
	if (*p) {
		*p++ = '\0';
	}
	*cursor = p;
	return start;
}

static void dispatch(char *line)
{
	char *cursor = line;
	char *tok = next_token(&cursor);

	if (!tok || strcmp(tok, "CMD") != 0) {
		/* Not addressed to us. Silent on purpose: the same transport
		 * carries the host's own noise, and answering every stray byte
		 * with an error is worse than ignoring it. */
		return;
	}

	char *verb = next_token(&cursor);

	if (!verb) {
		return;
	}

	if (strcmp(verb, "id") == 0) {
		command_send_identity();
	} else if (strcmp(verb, "sd.stat") == 0) {
		cmd_sd_stat();
	} else if (strcmp(verb, "sd.list") == 0) {
		cmd_sd_list();
	} else if (strcmp(verb, "sd.del") == 0) {
		char *name = next_token(&cursor);

		if (name) {
			cmd_sd_del(name);
		} else {
			reply_err("sd.del", -EINVAL);
		}
	} else if (strcmp(verb, "sd.format") == 0) {
		cmd_sd_format();
	} else if (strcmp(verb, "rec.start") == 0) {
		cmd_rec_start();
	} else if (strcmp(verb, "rec.stop") == 0) {
		cmd_rec_stop();
	} else if (strcmp(verb, "sd.get") == 0) {
		char *name = next_token(&cursor);
		char *off  = next_token(&cursor);

		if (name) {
			cmd_sd_get(name,
				   off ? (uint32_t)strtoul(off, NULL, 10) : 0);
		} else {
			reply_err("sd.get", -EINVAL);
		}
	} else if (strcmp(verb, "sat.list") == 0) {
		cmd_sat_list();
	} else if (strcmp(verb, "sat.add") == 0) {
		char *addr = next_token(&cursor);

		/* The label is the rest of the line, not the next token: it is
		 * operator-supplied text and "left wrist" must not silently
		 * become "left". Leading whitespace is skipped; the line has
		 * already had its newline stripped by the assembler. */
		while (*cursor == ' ' || *cursor == '\t') {
			cursor++;
		}

		if (addr) {
			cmd_sat_add(addr, cursor);
		} else {
			reply("SAT err sat.add %d", EINVAL);
		}
	} else if (strcmp(verb, "sat.del") == 0) {
		char *addr = next_token(&cursor);

		if (addr) {
			cmd_sat_del(addr);
		} else {
			reply("SAT err sat.del %d", EINVAL);
		}
	} else if (strcmp(verb, "sat.clear") == 0) {
		cmd_sat_clear();
	} else {
		/* sd.abort never reaches here: command_feed() consumes it. */
		reply("SD err unknown 0");
	}
}

static void cmd_thread_fn(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	char line[CMD_LINE_MAX];

	while (true) {
		if (k_msgq_get(&cmd_q, line, K_FOREVER) == 0) {
			dispatch(line);
		}
	}
}

/* ── USB console reader ───────────────────────────────────────────────
 *
 * BLE gets its input for free — the RX characteristic already had a write
 * callback. USB did not: this firmware only ever printk()ed, and a
 * printk-only build has no path for characters coming the other way.
 *
 * CONFIG_CONSOLE_GETCHAR gives it one on the same CDC ACM device, so a cabled
 * hub answers the same commands as a wireless one. That matters most for
 * downloads: base64 over USB is roughly 2.5x the BLE rate, which is the
 * difference between a 20-minute transfer and a 50-minute one.
 *
 * If console input ever turns out to conflict with printk on this CDC ACM
 * device, dropping CONSOLE_GETCHAR from prj.conf disables just this thread and
 * leaves the BLE command path intact.
 */
#define CONSOLE_STACK_SIZE  1024
#define CONSOLE_PRIORITY    9   /* lowest of ours: it spends its life blocked */

static K_THREAD_STACK_DEFINE(console_stack, CONSOLE_STACK_SIZE);
static struct k_thread console_thread;

static void console_thread_fn(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	if (console_init() != 0) {
		printk("Command: console input unavailable — BLE commands "
		       "only\n");
		return;
	}

	while (true) {
		int c = console_getchar();

		if (c < 0) {
			/* The console device went away — on USB CDC ACM that is
			 * what unplugging the cable looks like. Retry rather
			 * than spin: it comes back on re-enumeration, and a
			 * tight loop here would starve everything below it. */
			k_sleep(K_MSEC(100));
			continue;
		}

		uint8_t ch = (uint8_t)c;

		command_feed(&ch, 1);
	}
}

int command_init(void)
{
	k_thread_create(&cmd_thread, cmd_stack, CMD_STACK_SIZE,
			cmd_thread_fn, NULL, NULL, NULL,
			CMD_PRIORITY, 0, K_NO_WAIT);
	k_thread_name_set(&cmd_thread, "cmdproc");

	k_thread_create(&console_thread, console_stack, CONSOLE_STACK_SIZE,
			console_thread_fn, NULL, NULL, NULL,
			CONSOLE_PRIORITY, 0, K_NO_WAIT);
	k_thread_name_set(&console_thread, "cmdconsole");

	return 0;
}
