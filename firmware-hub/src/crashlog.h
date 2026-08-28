#ifndef PHYSDAQ_CRASHLOG_H
#define PHYSDAQ_CRASHLOG_H

#include <stddef.h>
#include <stdint.h>

/* Persist the essentials of a fatal error across the reboot it triggers, and
 * report them on the next boot. See crashlog.c for why the default halt was
 * unacceptable on this board. */

/* Call first thing in main(): captures and clears the record from the
 * previous boot before anything can clobber it. */
void crashlog_init(void);

/* "Boot: cause=... , up N s" — why the SoC last started. Always available. */
int crashlog_format_boot(char *buf, size_t len);

/* Format the previous boot's crash as one line ("Crash: ... pc=0x...") into
 * buf. Returns the length, or 0 when the previous boot did not crash. Resolve
 * pc/lr with `arm-zephyr-eabi-addr2line -e build/zephyr/zephyr.elf 0x...`. */
int crashlog_format(char *buf, size_t len);

/* Record from the starvation monitor: main has not fed the watchdog for
 * starved_ms; `thread`/`prio`/`pc` describe whoever holds the CPU. Kept in
 * noinit RAM for the boot that follows the watchdog reset. */
void crashlog_note_hang(uint32_t starved_ms, const char *stage,
			const char *main_state, const char *thread,
			int prio, uint32_t pc);

/* Previous boot's hang, as one line ("Hang: ..."), or 0 if there was none. */
int crashlog_format_hang(char *buf, size_t len);

#endif /* PHYSDAQ_CRASHLOG_H */
