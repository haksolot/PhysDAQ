#include <zephyr/kernel.h>
#include <zephyr/fatal.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/hwinfo.h>
#include <string.h>
#include "crashlog.h"

/*
 * Why this exists. Vanilla Zephyr's fatal-error path ends in
 * arch_system_halt(): interrupts locked, spin forever. On this board that is
 * the worst possible outcome — the crash dump goes into the USB CDC ring
 * buffer but the USB interrupt that would transmit it can never run, so the
 * console just stops mid-line; the host sees a BLE supervision timeout 5 s
 * later; the hardware watchdog fires after 8 s; and the next boot says
 * "cause=watchdog", which is true but names the symptom. Every one of those
 * traces was chased before anyone suspected a crash.
 *
 * This override does two things instead: keeps the essentials (reason, PC,
 * LR, the thread that faulted) in a RAM region the linker does not zero, and
 * reboots at once. RAM survives a soft reset on the nRF52840, so the next
 * boot finds the record, and main.c sends it to the host in the boot report
 * on both transports. The magic guards against reading garbage after a real
 * power-on.
 */
struct crash_rec {
	uint32_t magic;
	uint32_t reason;
	uint32_t pc;
	uint32_t lr;
	char     thread[CONFIG_THREAD_MAX_NAME_LEN];
	uint8_t  in_isr;
};

#define CRASH_MAGIC 0xC5A5D0C5U

static __noinit struct crash_rec rec;

static bool have_last;
static struct crash_rec last;

/* Same idea for a hang. The starvation monitor in watchdog.c fires from the
 * system-clock interrupt when main() has stopped feeding the watchdog; it
 * also prints, but a console line is useless when the reason main is starved
 * is a cooperative thread hogging the CPU — the USB work item that would
 * transmit it never runs either. Writing here costs nothing and survives the
 * watchdog reset that follows. */
struct hang_rec {
	uint32_t magic;
	uint32_t starved_ms;
	uint32_t pc;             /* of the thread holding the CPU */
	int32_t  prio;
	char     stage[16];
	char     main_state[16];
	char     thread[CONFIG_THREAD_MAX_NAME_LEN];
};

#define HANG_MAGIC 0x4A46A4A6U

static __noinit struct hang_rec hang;

static bool have_hang;
static struct hang_rec last_hang;

/* Why the SoC last started, read once at boot and cleared so the next boot
 * reports its own cause. Repeated to every host that connects: a hub that
 * keeps "disconnecting" may in fact be rebooting. */
static uint32_t boot_cause;
static int64_t  boot_ms;

static const char *boot_cause_str(uint32_t c)
{
	if (c & RESET_WATCHDOG)       return "watchdog";
	if (c & RESET_SOFTWARE)       return "software (fatal error or reboot)";
	if (c & RESET_LOW_POWER_WAKE) return "wake from deep sleep";
	if (c & RESET_PIN)            return "reset pin";
	if (c & RESET_POR)            return "power-on";
	if (c & RESET_BROWNOUT)       return "brownout";
	if (c & RESET_DEBUG)          return "debugger";
	return "unknown";
}

static void copy_str(char *dst, size_t len, const char *src)
{
	strncpy(dst, src ? src : "?", len - 1);
	dst[len - 1] = '\0';
}

static const char *reason_str(uint32_t r)
{
	switch (r) {
	case K_ERR_CPU_EXCEPTION:  return "cpu exception (hard/bus/usage/mpu fault)";
	case K_ERR_SPURIOUS_IRQ:   return "spurious irq";
	case K_ERR_STACK_CHK_FAIL: return "stack overflow";
	case K_ERR_KERNEL_OOPS:    return "kernel oops";
	case K_ERR_KERNEL_PANIC:   return "kernel panic";
	default:                   return "arch-specific";
	}
}

void k_sys_fatal_error_handler(unsigned int reason, const z_arch_esf_t *esf)
{
	rec.magic  = CRASH_MAGIC;
	rec.reason = reason;
	rec.pc     = esf ? esf->basic.pc : 0;
	rec.lr     = esf ? esf->basic.lr : 0;
	rec.in_isr = k_is_in_isr();
	copy_str(rec.thread, sizeof(rec.thread),
		 k_is_in_isr() ? "ISR" : k_thread_name_get(k_current_get()));

	/* The kernel has already queued its own dump for the console; it will
	 * never be transmitted from here, and this reboot is what makes the
	 * node reappear in ~1 s instead of 8. */
	sys_reboot(SYS_REBOOT_COLD);
	CODE_UNREACHABLE;
}

void crashlog_note_hang(uint32_t starved_ms, const char *stage,
			const char *main_state, const char *thread,
			int prio, uint32_t pc)
{
	hang.starved_ms = starved_ms;
	hang.pc         = pc;
	hang.prio       = prio;
	copy_str(hang.stage, sizeof(hang.stage), stage);
	copy_str(hang.main_state, sizeof(hang.main_state), main_state);
	copy_str(hang.thread, sizeof(hang.thread), thread);
	hang.magic = HANG_MAGIC;
}

void crashlog_init(void)
{
	boot_ms = k_uptime_get();
	if (hwinfo_get_reset_cause(&boot_cause) == 0) {
		hwinfo_clear_reset_cause();
	}
	if (rec.magic == CRASH_MAGIC) {
		last      = rec;
		have_last = true;
	}
	if (hang.magic == HANG_MAGIC) {
		last_hang = hang;
		have_hang = true;
	}
	/* One report per event: a clean boot after this one says nothing. */
	rec.magic  = 0;
	hang.magic = 0;
}

int crashlog_format_boot(char *buf, size_t len)
{
	return snprintk(buf, len, "Boot: cause=0x%x (%s), up %lld s\n",
			(unsigned int)boot_cause, boot_cause_str(boot_cause),
			(long long)((k_uptime_get() - boot_ms) / 1000));
}

int crashlog_format(char *buf, size_t len)
{
	if (!have_last) {
		return 0;
	}
	return snprintk(buf, len,
			"Crash: %s (reason %u) in %s%s pc=0x%08x lr=0x%08x\n",
			reason_str(last.reason), (unsigned int)last.reason,
			last.thread, last.in_isr ? " (isr)" : "",
			(unsigned int)last.pc, (unsigned int)last.lr);
}

int crashlog_format_hang(char *buf, size_t len)
{
	if (!have_hang) {
		return 0;
	}
	return snprintk(buf, len,
			"Hang: main starved %u ms at stage \"%s\" (main %s); "
			"CPU held by \"%s\" prio %d pc=0x%08x\n",
			(unsigned int)last_hang.starved_ms, last_hang.stage,
			last_hang.main_state, last_hang.thread,
			(int)last_hang.prio, (unsigned int)last_hang.pc);
}
