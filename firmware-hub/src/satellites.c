#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/drivers/flash.h>
#include <string.h>

#include "satellites.h"
#include "storage.h"

/*
 * Persistence uses NVS on the board's `storage_partition` — 32 kB at 0xEC000,
 * which xiao_ble_common.dtsi reserves for exactly this and which sits clear of
 * both the code partition and the UF2 bootloader.
 *
 * The whole roster is one NVS record rather than one per entry. It is under
 * 350 bytes, it is only ever read and written whole, and a single record means
 * an interrupted write can never leave half a roster behind.
 */
#define NVS_PARTITION         storage_partition
#define NVS_PARTITION_DEVICE  FIXED_PARTITION_DEVICE(NVS_PARTITION)
#define NVS_PARTITION_OFFSET  FIXED_PARTITION_OFFSET(NVS_PARTITION)
#define NVS_PARTITION_SIZE    FIXED_PARTITION_SIZE(NVS_PARTITION)

#define ROSTER_NVS_ID   1
#define ROSTER_MAGIC    0x50445351u   /* "PDSQ" */
#define ROSTER_VERSION  1

struct sat_entry {
	char    addr[SATELLITE_ADDR_LEN];
	char    label[SATELLITE_LABEL_LEN];
	uint8_t source_id;
	uint8_t used;
};

struct sat_roster {
	uint32_t magic;
	uint8_t  version;
	uint8_t  reserved[3];
	struct sat_entry e[SATELLITES_MAX];
};

static struct sat_roster  roster;
static struct nvs_fs      fs;
static bool               nvs_ready;

/* One mutex for the whole module: every entry point either reads or rewrites
 * the roster as a unit, and they are all called from the command thread today.
 * Locking anyway costs nothing and means a second caller later is not a bug. */
K_MUTEX_DEFINE(roster_lock);

/* ── Address handling ─────────────────────────────────────────────────
 *
 * Validated rather than trusted: this string is echoed back to the host, and
 * eventually it will be parsed into a bt_addr_le_t. Rejecting a malformed one
 * at the door beats storing it and failing to connect much later, for reasons
 * nobody will be able to reconstruct.
 */
static bool is_hex(char c)
{
	return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') ||
	       (c >= 'a' && c <= 'f');
}

static bool addr_valid(const char *addr)
{
	if (!addr || strlen(addr) != 17) {
		return false;
	}
	for (int i = 0; i < 17; i++) {
		if ((i % 3) == 2) {
			if (addr[i] != ':') {
				return false;
			}
		} else if (!is_hex(addr[i])) {
			return false;
		}
	}
	return true;
}

/* Case-insensitive: a MAC typed by hand and one from a scan differ only in
 * case, and storing both as separate satellites would be a silent duplicate. */
static bool addr_eq(const char *a, const char *b)
{
	for (int i = 0; i < 17; i++) {
		char ca = a[i], cb = b[i];

		if (ca >= 'a' && ca <= 'z') {
			ca -= 32;
		}
		if (cb >= 'a' && cb <= 'z') {
			cb -= 32;
		}
		if (ca != cb) {
			return false;
		}
	}
	return true;
}

/* ── Persistence ──────────────────────────────────────────────────────── */

static void roster_reset(void)
{
	memset(&roster, 0, sizeof(roster));
	roster.magic   = ROSTER_MAGIC;
	roster.version = ROSTER_VERSION;
}

static int roster_save(void)
{
	if (!nvs_ready) {
		/* The in-RAM roster still works for this boot. Saying so once
		 * is better than failing the command: the operator's edit did
		 * take effect, it just will not outlive a reset. */
		return -ENODEV;
	}

	ssize_t n = nvs_write(&fs, ROSTER_NVS_ID, &roster, sizeof(roster));

	/* nvs_write returns 0 when the value is byte-identical to what is
	 * already stored — it skips the erase cycle. That is a success. */
	if (n < 0) {
		printk("Satellites: nvs_write failed (%d)\n", (int)n);
		return (int)n;
	}
	return 0;
}

int satellites_init(void)
{
	roster_reset();

	struct flash_pages_info info;
	const struct device *flash_dev = NVS_PARTITION_DEVICE;

	if (!device_is_ready(flash_dev)) {
		printk("Satellites: flash device not ready — roster will not "
		       "persist\n");
		return -ENODEV;
	}

	int ret = flash_get_page_info_by_offs(flash_dev, NVS_PARTITION_OFFSET,
					      &info);

	if (ret != 0) {
		printk("Satellites: flash page info failed (%d)\n", ret);
		return ret;
	}

	fs.flash_device = flash_dev;
	fs.offset       = NVS_PARTITION_OFFSET;
	fs.sector_size  = info.size;
	/* Two sectors is the NVS minimum: it needs a spare to garbage-collect
	 * into. The partition is 32 kB against a 4 kB page, so there is room
	 * to spare. */
	fs.sector_count = 2U;

	ret = nvs_mount(&fs);
	if (ret != 0) {
		printk("Satellites: nvs_mount failed (%d) — roster will not "
		       "persist\n", ret);
		return ret;
	}

	nvs_ready = true;

	struct sat_roster stored;
	ssize_t n = nvs_read(&fs, ROSTER_NVS_ID, &stored, sizeof(stored));

	if (n == sizeof(stored) && stored.magic == ROSTER_MAGIC &&
	    stored.version == ROSTER_VERSION) {
		roster = stored;
		printk("Satellites: %u configured\n", satellites_count());
	} else if (n > 0) {
		/* Something is stored but it is not a roster this build
		 * understands. Starting empty is the only safe reading: acting
		 * on a misinterpreted layout would assign source ids that
		 * collide with the local sensors. */
		printk("Satellites: stored roster unrecognised — starting "
		       "empty\n");
		roster_reset();
	}

	return 0;
}

uint8_t satellites_count(void)
{
	uint8_t n = 0;

	for (int i = 0; i < SATELLITES_MAX; i++) {
		if (roster.e[i].used) {
			n++;
		}
	}
	return n;
}

void satellites_foreach(satellites_cb cb, void *ctx)
{
	if (!cb) {
		return;
	}

	k_mutex_lock(&roster_lock, K_FOREVER);
	for (int i = 0; i < SATELLITES_MAX; i++) {
		if (roster.e[i].used) {
			cb((uint8_t)i, roster.e[i].addr, roster.e[i].source_id,
			   roster.e[i].label, ctx);
		}
	}
	k_mutex_unlock(&roster_lock);
}

/* Lowest source id not already taken. Ids start at PPG_SRC_REMOTE_BASE so they
 * cannot collide with the two local sensors, which the log format numbers from
 * zero. Reusing a freed id is deliberate: a file's header is what maps ids to
 * sources, so ids only have to be unique within one session, and letting them
 * climb for ever would eventually run past the 0x10..0xFF range. */
static uint8_t next_source_id(void)
{
	for (uint8_t id = PPG_SRC_REMOTE_BASE; id != 0; id++) {
		bool taken = false;

		for (int i = 0; i < SATELLITES_MAX; i++) {
			if (roster.e[i].used && roster.e[i].source_id == id) {
				taken = true;
				break;
			}
		}
		if (!taken) {
			return id;
		}
	}
	return PPG_SRC_REMOTE_BASE;
}

int satellites_add(const char *addr, const char *label, uint8_t *out_source_id)
{
	if (!addr_valid(addr)) {
		return -EINVAL;
	}

	k_mutex_lock(&roster_lock, K_FOREVER);

	int slot = -1;

	/* An existing address is an update, not a duplicate: re-adding a
	 * satellite to relabel it must not consume a second slot, and must not
	 * change its source id — a session file recorded earlier refers to
	 * that id. */
	for (int i = 0; i < SATELLITES_MAX; i++) {
		if (roster.e[i].used && addr_eq(roster.e[i].addr, addr)) {
			slot = i;
			break;
		}
	}

	bool is_new = (slot < 0);

	if (is_new) {
		for (int i = 0; i < SATELLITES_MAX; i++) {
			if (!roster.e[i].used) {
				slot = i;
				break;
			}
		}
	}

	if (slot < 0) {
		k_mutex_unlock(&roster_lock);
		return -ENOSPC;
	}

	struct sat_entry *e = &roster.e[slot];

	if (is_new) {
		memset(e, 0, sizeof(*e));
		strncpy(e->addr, addr, SATELLITE_ADDR_LEN - 1);
		e->source_id = next_source_id();
		e->used = 1;
	}

	memset(e->label, 0, sizeof(e->label));
	if (label && label[0]) {
		strncpy(e->label, label, SATELLITE_LABEL_LEN - 1);
	}

	if (out_source_id) {
		*out_source_id = e->source_id;
	}

	int ret = roster_save();

	k_mutex_unlock(&roster_lock);

	/* -ENODEV means "kept, but not persisted". The entry is usable now, so
	 * this is not a failure of the command; the caller reports it and the
	 * app can warn. */
	return (ret == -ENODEV) ? 0 : ret;
}

int satellites_remove(const char *addr)
{
	if (!addr_valid(addr)) {
		return -EINVAL;
	}

	k_mutex_lock(&roster_lock, K_FOREVER);

	int ret = -ENOENT;

	for (int i = 0; i < SATELLITES_MAX; i++) {
		if (roster.e[i].used && addr_eq(roster.e[i].addr, addr)) {
			memset(&roster.e[i], 0, sizeof(roster.e[i]));
			ret = roster_save();
			if (ret == -ENODEV) {
				ret = 0;
			}
			break;
		}
	}

	k_mutex_unlock(&roster_lock);
	return ret;
}

int satellites_clear(void)
{
	k_mutex_lock(&roster_lock, K_FOREVER);

	roster_reset();
	int ret = roster_save();

	k_mutex_unlock(&roster_lock);
	return (ret == -ENODEV) ? 0 : ret;
}
