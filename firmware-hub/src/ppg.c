#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <string.h>

#include "ppg.h"
#include "max30102.h"
#include "storage.h"

#define PPG_STACK_SIZE  1536
#define PPG_PRIORITY    4       /* above the storage thread (7): a stalled
                                 * card must never delay a FIFO drain */

/* How long a sensor may produce nothing before we treat it as wedged and
 * re-run init. Same one-second rule the single-PPG firmware used, now applied
 * per sensor so one dead sensor cannot take the other down with it. */
#define STALL_TIMEOUT_MS  1000

/* Poll timeout on the interrupt wait. Short enough that a missed edge costs
 * little, long enough that a healthy sensor essentially never hits it. */
#define WAIT_TIMEOUT_MS   200

struct ppg_ctx {
	struct max30102_dev  dev;
	struct ppg_live      live;
	struct k_spinlock    lock;
	struct k_thread      thread;
	k_thread_stack_t    *stack;
	const char          *thread_name;
	uint16_t             seq;
};

static K_THREAD_STACK_DEFINE(ppg0_stack, PPG_STACK_SIZE);
static K_THREAD_STACK_DEFINE(ppg1_stack, PPG_STACK_SIZE);

/*
 * The two instances. Both parts are strapped to 0x57; what distinguishes them
 * is the mux channel their devicetree node sits under — see the overlay, the
 * indices are not the numbers on the breakout's silk screen — and the INT
 * line, which is a plain GPIO wired straight to the SoC and bypasses the mux
 * entirely. That is what lets a sample-ready event be timestamped regardless
 * of which channel is currently selected, and it is also why the boot
 * self-test measures the channel-to-INT pairing instead of trusting it: get it
 * wrong and acquisition still works, with every timestamp on the wrong site.
 */
static struct ppg_ctx sensors[PPG_SENSOR_COUNT] = {
	{
		.dev = MAX30102_DT_DEFINE(DT_NODELABEL(ppg0),
					  PPG_SRC_LOCAL_0, "PPG0"),
		.stack = ppg0_stack,
		.thread_name = "ppg0",
	},
	{
		.dev = MAX30102_DT_DEFINE(DT_NODELABEL(ppg1),
					  PPG_SRC_LOCAL_1, "PPG1"),
		.stack = ppg1_stack,
		.thread_name = "ppg1",
	},
};

static void ppg_thread_fn(void *ctx_ptr, void *b, void *c)
{
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	struct ppg_ctx *ctx = ctx_ptr;
	struct ppg_sample batch[MAX30102_FIFO_DEPTH];
	int64_t last_data_ms = k_uptime_get();

	/* Fixed for the lifetime of the session — read once rather than on
	 * every record. */
	const uint64_t epoch = storage_epoch_ticks();

	while (true) {
		uint64_t isr_ticks = 0;
		bool     overflow  = false;

		/* Blocks on this sensor's own interrupt. */
		bool timed_out = (max30102_wait_ready(&ctx->dev,
						K_MSEC(WAIT_TIMEOUT_MS)) != 0);

		/* isr_ticks comes back from the ISR capture, paired with the
		 * FIFO write pointer — see max30102.c for why the pairing
		 * matters. */
		int count = max30102_drain(&ctx->dev, batch, ARRAY_SIZE(batch),
					   &overflow, &isr_ticks);

		if (timed_out) {
			/* No interrupt arrived, so the ISR timestamp is stale —
			 * it belongs to whatever sample last raised INT, which
			 * may be a full timeout ago. Anything the FIFO still
			 * holds gets dated now instead, and flagged, so the
			 * offline reader can see that its timing is only as
			 * good as this poll. */
			isr_ticks = k_uptime_ticks();
		}

		if (count > 0) {
			last_data_ms = k_uptime_get();

			for (int i = 0; i < count; i++) {
				struct ppg_record rec;

				/* The interrupt belongs to the newest entry;
				 * everything older is back-dated from the ODR,
				 * and says so in its flags so the offline
				 * reader can tell a measured timestamp from a
				 * derived one. */
				uint64_t t = isr_ticks -
					     max30102_backdate_ticks(i, count);

				rec.source_id = ctx->dev.source_id;
				rec.flags     = 0;
				if (i != count - 1) {
					rec.flags |= PPG_FLAG_BACKDATED;
				}
				if (timed_out) {
					rec.flags |= PPG_FLAG_UNTIMED;
				}
				if (overflow && i == 0) {
					/* Mark only the first record of the
					 * batch: the gap is immediately before
					 * it, not spread across the batch. */
					rec.flags |= PPG_FLAG_OVERFLOW;
				}
				rec.seq    = ctx->seq++;
				rec.red    = batch[i].red;
				rec.ir     = batch[i].ir;

				/* Stored relative to the session epoch that
				 * storage.c wrote into the header, so it stays
				 * a small 32-bit offset. */
				rec.t_ticks = (uint32_t)(t - epoch);

				storage_submit(&rec);
			}

			K_SPINLOCK(&ctx->lock) {
				ctx->live.red     = batch[count - 1].red;
				ctx->live.ir      = batch[count - 1].ir;
				ctx->live.t_ticks = isr_ticks;
				ctx->live.samples += count;
				ctx->live.alive   = true;
				if (overflow) {
					ctx->live.overflows++;
				}
			}
		} else if (count < 0) {
			/* Bus error — let the stall timer below decide whether
			 * this is transient or a wedge worth reinitialising. */
		}

		/* Per-sensor stall recovery. If the FIFO produced nothing for
		 * over a second this sensor or its mux channel has wedged. The
		 * CPU is fine, so the watchdog will not help and must not be
		 * allowed to: re-running init recovers the bus and fully
		 * reconfigures the part, restarting this stream without
		 * touching the other sensor or the storage thread. */
		int64_t now = k_uptime_get();

		if (now - last_data_ms > STALL_TIMEOUT_MS) {
			printk("%s: no data for >%d ms — reinitialising\n",
			       ctx->dev.name, STALL_TIMEOUT_MS);

			K_SPINLOCK(&ctx->lock) {
				ctx->live.alive = false;
				ctx->live.reinits++;
			}

			max30102_init(&ctx->dev);
			last_data_ms = k_uptime_get();
		}
	}
}

int ppg_init(void)
{
	int live_count = 0;

	for (int i = 0; i < PPG_SENSOR_COUNT; i++) {
		struct ppg_ctx *ctx = &sensors[i];

		if (max30102_init(&ctx->dev) < 0) {
			printk("%s: init failed — this sensor will not be "
			       "logged\n", ctx->dev.name);
			continue;
		}

		k_thread_create(&ctx->thread, ctx->stack, PPG_STACK_SIZE,
				ppg_thread_fn, ctx, NULL, NULL,
				PPG_PRIORITY, 0, K_NO_WAIT);
		k_thread_name_set(&ctx->thread, ctx->thread_name);
		live_count++;
	}

	if (live_count == 0) {
		printk("PPG: no sensor responded — see the boot self-test "
		       "above for which mux channels are actually populated\n");
		return -ENODEV;
	}

	printk("PPG: %d of %d sensors acquiring\n",
	       live_count, PPG_SENSOR_COUNT);
	return 0;
}

void ppg_get_live(uint8_t index, struct ppg_live *out)
{
	if (index >= PPG_SENSOR_COUNT) {
		memset(out, 0, sizeof(*out));
		return;
	}

	struct ppg_ctx *ctx = &sensors[index];

	K_SPINLOCK(&ctx->lock) {
		*out = ctx->live;
	}
}

void ppg_shutdown_all(void)
{
	for (int i = 0; i < PPG_SENSOR_COUNT; i++) {
		max30102_shutdown(&sensors[i].dev);
	}
}
