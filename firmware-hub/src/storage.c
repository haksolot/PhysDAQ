#include <zephyr/kernel.h>
#include <zephyr/devicetree.h>
#include <zephyr/fs/fs.h>
#include <zephyr/fs/fs_sys.h>
#include <zephyr/storage/disk_access.h>
#include <zephyr/sys/printk.h>
#include <string.h>
#include <ff.h>

#include "storage.h"
#include "version.h"
#include "max30102.h"

/* Disk name comes from CONFIG_SDMMC_VOLUME_NAME, which defaults to "SD" when
 * FAT_FILESYSTEM_ELM is enabled. The FATFS mount point must be that name
 * followed by a colon. */
#define DISK_NAME    CONFIG_SDMMC_VOLUME_NAME
#define MOUNT_POINT  "/" CONFIG_SDMMC_VOLUME_NAME ":"

#define SESSION_MAX  9999

/*
 * Queue depth, in records.
 *
 * The number that matters is the worst-case SD stall: a card doing internal
 * wear-levelling can block a write for tens of milliseconds, and occasionally
 * a couple of hundred. Two sensors at 100 Hz produce 200 records/s, so 512
 * records is about 2.5 s of headroom — an order of magnitude over the stalls
 * we expect, which is the right margin for something that costs 8 kB of RAM
 * on a part with 256 kB. records_dropped in the stats is the check that this
 * was actually enough.
 */
#define QUEUE_DEPTH  512

#define STORAGE_STACK_SIZE  4096   /* FATFS + the SD stack are stack-hungry */
#define STORAGE_PRIORITY    7      /* below the acquisition threads, which sit
                                    * at 4 — a card stall must never delay a
                                    * FIFO drain */

K_MSGQ_DEFINE(record_q, sizeof(struct ppg_record), QUEUE_DEPTH, 4);

static FATFS fat_fs;
static struct fs_mount_t mp = {
	.type      = FS_FATFS,
	.fs_data   = &fat_fs,
	.mnt_point = MOUNT_POINT,
};

static struct fs_file_t     session_file;
static bool                 active;

/* Whether a session file is open right now.
 *
 * Distinct from `active`, which means "card mounted and storage thread
 * running". The two diverge when the host stops recording without unmounting:
 * the thread keeps servicing maintenance requests, but there is nothing to
 * write records into. Written only by the storage thread, read by the
 * acquisition threads — one byte, so no lock, but it must be volatile or the
 * compiler is free to hoist the check out of the submit path. */
static volatile bool        session_open;
static uint64_t             epoch_ticks;
static int                  last_error;
static struct storage_stats stats;
static struct k_spinlock    stats_lock;

static K_THREAD_STACK_DEFINE(storage_stack, STORAGE_STACK_SIZE);
static struct k_thread storage_thread;

/* ── Disk bring-up ────────────────────────────────────────────────── */

static int mount_card(void)
{
	int ret = disk_access_init(DISK_NAME);

	if (ret != 0) {
		printk("Storage: disk_access_init(%s) failed (%d) — card "
		       "absent, unseated, or SPI wiring bad\n", DISK_NAME, ret);
		return ret;
	}

	/* Report the geometry: it is the cheapest proof that the SPI link is
	 * actually working rather than just not erroring, and it is what step 4
	 * of the bring-up checklist asks to see. */
	uint32_t sector_count = 0;
	uint32_t sector_size  = 0;

	if (disk_access_ioctl(DISK_NAME, DISK_IOCTL_GET_SECTOR_COUNT,
			      &sector_count) == 0 &&
	    disk_access_ioctl(DISK_NAME, DISK_IOCTL_GET_SECTOR_SIZE,
			      &sector_size) == 0) {
		printk("Storage: card %u sectors × %u B (%u MB)\n",
		       sector_count, sector_size,
		       (uint32_t)(((uint64_t)sector_count * sector_size) >> 20));
	}

	ret = fs_mount(&mp);
	if (ret != 0) {
		printk("Storage: fs_mount(%s) failed (%d) — is the card "
		       "formatted FAT32?\n", MOUNT_POINT, ret);
		return ret;
	}

	printk("Storage: mounted %s\n", MOUNT_POINT);
	return 0;
}

/* Claim the lowest-numbered SESSNNNN.BIN that does not exist yet.
 *
 * There is no RTC and no wall clock on this board, so a session cannot be
 * named by date. Creating with FS_O_CREATE and checking for an existing file
 * first keeps numbering monotonic across power cycles, which is enough to
 * order sessions and to match them to lab notes by hand (see the report's
 * open question on node identification). */
static int open_session_file(uint32_t *out_id)
{
	char path[32];

	fs_file_t_init(&session_file);

	for (uint32_t id = 1; id <= SESSION_MAX; id++) {
		struct fs_dirent ent;

		snprintk(path, sizeof(path), MOUNT_POINT "/SESS%04u.BIN", id);

		if (fs_stat(path, &ent) == 0) {
			continue;   /* taken */
		}

		int ret = fs_open(&session_file, path,
				  FS_O_CREATE | FS_O_WRITE);
		if (ret != 0) {
			printk("Storage: fs_open(%s) failed (%d)\n", path, ret);
			return ret;
		}

		*out_id = id;
		printk("Storage: logging to %s\n", path);
		return 0;
	}

	printk("Storage: no free session slot (all %d used)\n", SESSION_MAX);
	return -ENOSPC;
}

static int write_header(uint32_t session_id, uint64_t epoch_ticks)
{
	struct log_header hdr;

	memset(&hdr, 0, sizeof(hdr));
	memcpy(hdr.magic, LOG_MAGIC, sizeof(hdr.magic));
	hdr.header_size    = LOG_HEADER_SIZE;
	hdr.record_size    = LOG_RECORD_SIZE;
	hdr.format_version = LOG_FORMAT_VERSION;
	hdr.odr_hz         = CONFIG_PHYSDAQ_PPG_ODR_HZ;
	hdr.session_id     = session_id;
	hdr.tick_hz        = CONFIG_SYS_CLOCK_TICKS_PER_SEC;
	hdr.epoch_ticks    = epoch_ticks;
	hdr.led_pa         = MAX30102_LED_PA;
	hdr.spo2_cfg       = MAX30102_SPO2_CFG;
	hdr.src_count      = 2;

	/* Sources 0 and 1 are the two local sensors. The channel numbers come
	 * from the devicetree rather than being written out here: which mux
	 * channel a sensor sits on is a wiring fact that already moved once
	 * (the breakout's pad labels are offset from the part's channel
	 * indices), and a header that disagrees with the overlay would
	 * mislabel every recording made with it.
	 * The rest stay 0xFF: "not on the local mux", which is what a Phase 2
	 * remote node will be. */
	memset(hdr.src_mux_ch, 0xFF, sizeof(hdr.src_mux_ch));
	hdr.src_mux_ch[PPG_SRC_LOCAL_0] =
		DT_REG_ADDR(DT_PARENT(DT_NODELABEL(ppg0)));
	hdr.src_mux_ch[PPG_SRC_LOCAL_1] =
		DT_REG_ADDR(DT_PARENT(DT_NODELABEL(ppg1)));

	hdr.fw_version[0] = PHYSDAQ_FW_MAJOR;
	hdr.fw_version[1] = PHYSDAQ_FW_MINOR;
	hdr.fw_version[2] = PHYSDAQ_FW_PATCH;
	hdr.fw_version[3] = PHYSDAQ_FW_TWEAK;

	ssize_t n = fs_write(&session_file, &hdr, sizeof(hdr));

	if (n != (ssize_t)sizeof(hdr)) {
		printk("Storage: header write failed (%d)\n", (int)n);
		return -EIO;
	}

	return fs_sync(&session_file);
}

/* ── Storage thread ───────────────────────────────────────────────── */

static void bump_write_error(void)
{
	K_SPINLOCK(&stats_lock) {
		stats.write_errors++;
	}
}

static int flush_block(uint8_t *buf, size_t used)
{
	if (used == 0) {
		return 0;
	}

	/* The session can be closed between a record being queued and this
	 * running. Writing to the closed handle would be a use-after-close. */
	if (!session_open) {
		return -ENODEV;
	}

	ssize_t n = fs_write(&session_file, buf, used);

	if (n != (ssize_t)used) {
		printk("Storage: write failed (%d of %u B)\n",
		       (int)n, (unsigned)used);
		bump_write_error();
		return -EIO;
	}

	K_SPINLOCK(&stats_lock) {
		stats.records_written += used / LOG_RECORD_SIZE;
	}
	return 0;
}

/* ── Maintenance requests ─────────────────────────────────────────────
 *
 * The storage thread is the only thread that touches the file system. Other
 * threads ask for work by posting one of these and waiting on its semaphore;
 * the storage thread services at most one per loop iteration, between block
 * writes, so a listing can never land in the middle of a partially filled
 * block. See the header for why this indirection exists.
 */
enum storage_op {
	OP_STAT,
	OP_LIST,
	OP_DELETE,
	OP_FORMAT,
	OP_SESSION_STOP,
	OP_SESSION_START,
	OP_READ,
};

struct storage_req {
	enum storage_op op;

	/* in */
	char             name[STORAGE_NAME_MAX];
	uint32_t         offset;
	uint8_t         *buf;
	size_t           len;
	storage_list_cb  cb;
	void            *ctx;

	/* out */
	int       result;      /* 0 or -errno; for OP_READ, the byte count */
	uint64_t  used;
	uint64_t  total;
	uint32_t  count;       /* files deleted, or the new session id */

	struct k_sem done;
};

/* Depth 1: these are human-triggered, one at a time, and a queue of pending
 * card operations would only let the UI get further ahead of the hardware. */
K_MSGQ_DEFINE(request_q, sizeof(struct storage_req *), 1, sizeof(void *));

/* One request at a time, so a second caller waits rather than having its
 * request rejected for a reason it cannot act on. */
K_MUTEX_DEFINE(request_lock);

static int submit_request(struct storage_req *req)
{
	if (!active) {
		return -ENODEV;
	}

	k_mutex_lock(&request_lock, K_FOREVER);

	k_sem_init(&req->done, 0, 1);
	req->result = -EIO;

	struct storage_req *p = req;
	int ret = k_msgq_put(&request_q, &p, K_SECONDS(2));

	if (ret == 0) {
		/* Generous: a card mid-wear-levelling can stall for a good
		 * fraction of a second, and a full-card listing walks every
		 * directory entry. Bounded all the same, so a wedged card
		 * cannot hang the command thread for ever. */
		if (k_sem_take(&req->done, K_SECONDS(10)) != 0) {
			ret = -ETIMEDOUT;
		} else {
			ret = req->result;
		}
	}

	k_mutex_unlock(&request_lock);
	return ret;
}

/* Everything from here to service_request() runs ON the storage thread. */

static bool is_session_name(const char *name)
{
	/* SESSNNNN.BIN, exactly. Anything else on the card is somebody else's
	 * file and this firmware has no business deleting or serving it. */
	if (strlen(name) != 12) {
		return false;
	}
	if (strncmp(name, "SESS", 4) != 0 || strcmp(name + 8, ".BIN") != 0) {
		return false;
	}
	for (int i = 4; i < 8; i++) {
		if (name[i] < '0' || name[i] > '9') {
			return false;
		}
	}
	return true;
}

/* Name of the session file currently open, or "" when none is. */
static char open_name[STORAGE_NAME_MAX];

static int do_stat(struct storage_req *req)
{
	struct fs_statvfs sbuf;
	int ret = fs_statvfs(MOUNT_POINT, &sbuf);

	if (ret != 0) {
		return ret;
	}

	uint64_t block = sbuf.f_frsize;

	req->total = (uint64_t)sbuf.f_blocks * block;
	req->used  = (uint64_t)(sbuf.f_blocks - sbuf.f_bfree) * block;
	return 0;
}

static int do_list(struct storage_req *req)
{
	struct fs_dir_t dir;

	fs_dir_t_init(&dir);

	int ret = fs_opendir(&dir, MOUNT_POINT);

	if (ret != 0) {
		return ret;
	}

	uint32_t n = 0;

	while (true) {
		struct fs_dirent ent;

		if (fs_readdir(&dir, &ent) != 0 || ent.name[0] == '\0') {
			break;   /* error or end of directory */
		}
		if (ent.type != FS_DIR_ENTRY_FILE || !is_session_name(ent.name)) {
			continue;
		}
		if (req->cb) {
			req->cb(ent.name, (uint32_t)ent.size, req->ctx);
		}
		n++;
	}

	fs_closedir(&dir);
	req->count = n;
	return 0;
}

static int do_delete(const char *name)
{
	if (!is_session_name(name)) {
		return -EINVAL;
	}
	/* Refusing rather than closing-and-deleting: silently ending a
	 * recording because a listing was stale would lose data the operator
	 * still believed was being captured. */
	if (session_open && strcmp(name, open_name) == 0) {
		return -EBUSY;
	}

	char path[40];

	snprintk(path, sizeof(path), MOUNT_POINT "/%s", name);
	return fs_unlink(path);
}

struct fmt_ctx {
	char     names[16][STORAGE_NAME_MAX];
	uint32_t n;
};

static void fmt_collect(const char *name, uint32_t size, void *ctx)
{
	struct fmt_ctx *f = ctx;

	ARG_UNUSED(size);

	if (f->n < ARRAY_SIZE(f->names)) {
		strncpy(f->names[f->n], name, STORAGE_NAME_MAX - 1);
		f->names[f->n][STORAGE_NAME_MAX - 1] = '\0';
		f->n++;
	}
}

static int do_format(struct storage_req *req)
{
	/* Collect first, unlink after: deleting entries while walking the same
	 * directory handle is undefined in FATFS, and skipped entries would
	 * leave a "format" that quietly did not. The batch cap means a card
	 * with more than 16 sessions needs the command run more than once,
	 * which the reported count makes visible. */
	if (session_open) {
		return -EBUSY;
	}

	uint32_t deleted = 0;

	while (true) {
		struct fmt_ctx f = { .n = 0 };
		struct storage_req sub = { .cb = fmt_collect, .ctx = &f };

		int ret = do_list(&sub);

		if (ret != 0) {
			return ret;
		}
		if (f.n == 0) {
			break;
		}

		for (uint32_t i = 0; i < f.n; i++) {
			char path[40];

			snprintk(path, sizeof(path), MOUNT_POINT "/%s",
				 f.names[i]);
			if (fs_unlink(path) == 0) {
				deleted++;
			}
		}

		if (f.n < ARRAY_SIZE(f.names)) {
			break;
		}
	}

	req->count = deleted;
	return 0;
}

static int do_read(struct storage_req *req)
{
	if (!is_session_name(req->name)) {
		return -EINVAL;
	}

	char path[40];

	snprintk(path, sizeof(path), MOUNT_POINT "/%s", req->name);

	struct fs_file_t f;

	fs_file_t_init(&f);

	int ret = fs_open(&f, path, FS_O_READ);

	if (ret != 0) {
		return ret;
	}

	ret = fs_seek(&f, req->offset, FS_SEEK_SET);
	if (ret == 0) {
		/* fs_read returns the byte count, and OP_READ is the one op
		 * whose result is a count rather than a status. A short read
		 * means end of file, which the caller uses as the terminator. */
		ret = (int)fs_read(&f, req->buf, req->len);
	}

	fs_close(&f);
	return ret;
}

static int do_session_stop(void)
{
	if (!session_open) {
		return -EALREADY;
	}

	fs_sync(&session_file);
	fs_close(&session_file);
	open_name[0] = '\0';
	session_open = false;
	printk("Storage: session %u stopped on request\n", stats.session_id);
	return 0;
}

static int do_session_start(struct storage_req *req)
{
	if (session_open) {
		return -EALREADY;
	}

	uint32_t id;
	int ret = open_session_file(&id);

	if (ret != 0) {
		return ret;
	}

	/* Re-anchor the clock. Acquisition reads storage_epoch_ticks() per
	 * record rather than caching it, so the new file's timestamps are
	 * relative to its own header from the first record on. */
	epoch_ticks = k_uptime_ticks();

	ret = write_header(id, epoch_ticks);
	if (ret != 0) {
		fs_close(&session_file);
		return ret;
	}

	snprintk(open_name, sizeof(open_name), "SESS%04u.BIN", id);
	session_open = true;

	K_SPINLOCK(&stats_lock) {
		stats.session_id = id;
	}
	req->count = id;
	return 0;
}

/* Service at most one pending maintenance request. Called once per storage
 * loop iteration, never while a block is half-written. */
static void service_request(void)
{
	struct storage_req *req;

	if (k_msgq_get(&request_q, &req, K_NO_WAIT) != 0) {
		return;
	}

	switch (req->op) {
	case OP_STAT:          req->result = do_stat(req);           break;
	case OP_LIST:          req->result = do_list(req);           break;
	case OP_DELETE:        req->result = do_delete(req->name);   break;
	case OP_FORMAT:        req->result = do_format(req);         break;
	case OP_SESSION_STOP:  req->result = do_session_stop();      break;
	case OP_SESSION_START: req->result = do_session_start(req);  break;
	case OP_READ:          req->result = do_read(req);           break;
	default:               req->result = -ENOTSUP;               break;
	}

	k_sem_give(&req->done);
}

static void storage_thread_fn(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	uint8_t block[LOG_BLOCK_SIZE];
	size_t  used = 0;
	int64_t next_sync = k_uptime_get() + CONFIG_PHYSDAQ_SD_SYNC_INTERVAL_MS;

	while (true) {
		int64_t now       = k_uptime_get();
		int64_t remaining = next_sync - now;

		if (remaining < 0) {
			remaining = 0;
		}

		struct ppg_record rec;

		/* Wake either on a new record or when the sync falls due,
		 * whichever comes first — so the sync interval is a real upper
		 * bound even when the sensors go quiet. */
		if (k_msgq_get(&record_q, &rec, K_MSEC(remaining)) == 0) {
			memcpy(&block[used], &rec, LOG_RECORD_SIZE);
			used += LOG_RECORD_SIZE;

			if (used == sizeof(block)) {
				flush_block(block, used);
				used = 0;
			}
		}

		if (k_uptime_get() >= next_sync) {
			/* Push out the partial block before syncing. Leaving it
			 * in RAM would mean the sync interval no longer bounds
			 * what a power cut costs, which is the entire point of
			 * having one. */
			flush_block(block, used);
			used = 0;

			if (session_open) {
				if (fs_sync(&session_file) != 0) {
					bump_write_error();
				} else {
					K_SPINLOCK(&stats_lock) {
						stats.syncs++;
					}
				}
			}

			next_sync = k_uptime_get() +
				    CONFIG_PHYSDAQ_SD_SYNC_INTERVAL_MS;
		}

		/* Maintenance last, and only between whole iterations: `block`
		 * is consistent here, and a listing or a delete can take tens
		 * of milliseconds that must not sit between two halves of a
		 * write. Records keep queueing while it runs — that is what
		 * the 2.5 s of queue depth is for. */
		service_request();
	}
}

/* ── Public API ───────────────────────────────────────────────────── */

int storage_init(void)
{
	int ret = mount_card();

	if (ret != 0) {
		last_error = ret;
		return ret;
	}

	uint32_t session_id;

	ret = open_session_file(&session_id);
	if (ret != 0) {
		last_error = ret;
		fs_unmount(&mp);
		return ret;
	}

	/* Anchor the session clock here, immediately before records can start
	 * arriving, so every t_ticks in the file is a small non-negative offset
	 * from a value the header records in full. This is why storage_init()
	 * must run before ppg_init(): the acquisition threads read the epoch
	 * once at start-up and subtract it from every timestamp. */
	epoch_ticks = k_uptime_ticks();

	ret = write_header(session_id, epoch_ticks);
	if (ret != 0) {
		last_error = ret;
		fs_close(&session_file);
		fs_unmount(&mp);
		return ret;
	}

	stats.session_id = session_id;
	snprintk(open_name, sizeof(open_name), "SESS%04u.BIN", session_id);
	session_open = true;
	active = true;

	k_thread_create(&storage_thread, storage_stack, STORAGE_STACK_SIZE,
			storage_thread_fn, NULL, NULL, NULL,
			STORAGE_PRIORITY, 0, K_NO_WAIT);
	k_thread_name_set(&storage_thread, "storage");

	printk("Storage: session %u open, sync every %d ms "
	       "(max loss ~%d samples)\n",
	       session_id, CONFIG_PHYSDAQ_SD_SYNC_INTERVAL_MS,
	       (CONFIG_PHYSDAQ_SD_SYNC_INTERVAL_MS *
		CONFIG_PHYSDAQ_PPG_ODR_HZ * 2) / 1000);

	return 0;
}

int storage_submit(const struct ppg_record *rec)
{
	/* Not counted as a drop: records_dropped exists to catch the storage
	 * thread falling behind, and burying that signal under samples the
	 * operator deliberately stopped recording would make it useless. */
	if (!active || !session_open) {
		return -ENODEV;
	}

	/* K_NO_WAIT: this runs on the acquisition threads, and blocking one of
	 * them behind a stalled card is exactly the failure the queue exists to
	 * prevent. A full queue drops the record and says so. */
	if (k_msgq_put(&record_q, rec, K_NO_WAIT) != 0) {
		K_SPINLOCK(&stats_lock) {
			stats.records_dropped++;
		}
		return -ENOMEM;
	}

	uint32_t used = k_msgq_num_used_get(&record_q);

	K_SPINLOCK(&stats_lock) {
		if (used > stats.max_queue_used) {
			stats.max_queue_used = used;
		}
	}

	return 0;
}

bool storage_is_active(void)
{
	return active;
}

bool storage_session_is_open(void)
{
	return session_open;
}

uint64_t storage_epoch_ticks(void)
{
	return epoch_ticks;
}

int storage_last_error(void)
{
	return last_error;
}

void storage_get_stats(struct storage_stats *out)
{
	K_SPINLOCK(&stats_lock) {
		*out = stats;
	}
}

/* ── Maintenance API ──────────────────────────────────────────────────
 *
 * Thin wrappers: build a request, hand it to the storage thread, block. The
 * work itself is in the do_* handlers above, which only ever run there.
 */

int storage_stat_card(uint64_t *used, uint64_t *total)
{
	struct storage_req req = { .op = OP_STAT };
	int ret = submit_request(&req);

	if (ret == 0) {
		if (used) {
			*used = req.used;
		}
		if (total) {
			*total = req.total;
		}
	}
	return ret;
}

int storage_list(storage_list_cb cb, void *ctx, uint32_t *count)
{
	struct storage_req req = { .op = OP_LIST, .cb = cb, .ctx = ctx };
	int ret = submit_request(&req);

	if (count) {
		*count = req.count;
	}
	return ret;
}

int storage_delete(const char *name)
{
	struct storage_req req = { .op = OP_DELETE };

	strncpy(req.name, name, sizeof(req.name) - 1);
	return submit_request(&req);
}

int storage_format(uint32_t *deleted)
{
	struct storage_req req = { .op = OP_FORMAT };
	int ret = submit_request(&req);

	if (deleted) {
		*deleted = req.count;
	}
	return ret;
}

int storage_session_stop(void)
{
	struct storage_req req = { .op = OP_SESSION_STOP };

	return submit_request(&req);
}

int storage_session_start(uint32_t *session_id)
{
	struct storage_req req = { .op = OP_SESSION_START };
	int ret = submit_request(&req);

	if (ret == 0 && session_id) {
		*session_id = req.count;
	}
	return ret;
}

int storage_read_chunk(const char *name, uint32_t offset,
		       uint8_t *buf, size_t len)
{
	if (len > STORAGE_CHUNK_MAX) {
		len = STORAGE_CHUNK_MAX;
	}

	struct storage_req req = {
		.op     = OP_READ,
		.offset = offset,
		.buf    = buf,
		.len    = len,
	};

	strncpy(req.name, name, sizeof(req.name) - 1);
	/* submit_request() returns req.result, which for OP_READ is the byte
	 * count rather than a status. */
	return submit_request(&req);
}

void storage_close(void)
{
	if (!active) {
		return;
	}

	active = false;
	if (session_open) {
		fs_sync(&session_file);
		fs_close(&session_file);
		open_name[0] = '\0';
		session_open = false;
	}
	fs_unmount(&mp);
	printk("Storage: session %u closed\n", stats.session_id);
}
