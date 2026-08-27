#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/sys/printk.h>
#include <hal/nrf_gpio.h>
#include "selftest.h"
#include "max30102.h"

/* Physical pins behind i2c1, from xiao_ble-pinctrl.dtsi (i2c1_default):
 * SDA = P0.04 = D4, SCL = P0.05 = D5. Hardcoded because the pinctrl psel
 * encoding is not worth unpacking at compile time for a diagnostic — but if
 * the overlay ever moves the bus, these must move with it. */
#define SDA_PIN  NRF_GPIO_PIN_MAP(0, 4)
#define SCL_PIN  NRF_GPIO_PIN_MAP(0, 5)

#define MUX_NODE   DT_NODELABEL(mux)
#define MUX_ADDR   DT_REG_ADDR(MUX_NODE)
#define PPG_ADDR   0x57
#define PART_ID_REG 0xFF
#define PART_ID_EXPECTED 0x15

/* The root controller the mux hangs off — i2c1 on this board. Taken from the
 * devicetree rather than hardcoded so this cannot drift from the overlay. */
static const struct device *root = DEVICE_DT_GET(DT_BUS(MUX_NODE));

/* The two sensor nodes, each with the channel its devicetree parent declares
 * and the INT line wired straight to the SoC. Verifying that those two agree
 * with the hardware is what verify_int_mapping() below is for. */
#define PPG0_NODE   DT_NODELABEL(ppg0)
#define PPG1_NODE   DT_NODELABEL(ppg1)
#define PPG0_CHAN   DT_REG_ADDR(DT_PARENT(PPG0_NODE))
#define PPG1_CHAN   DT_REG_ADDR(DT_PARENT(PPG1_NODE))

static const struct device *chan_dev[2] = {
	DEVICE_DT_GET(DT_PARENT(PPG0_NODE)),
	DEVICE_DT_GET(DT_PARENT(PPG1_NODE)),
};

static const struct gpio_dt_spec int_gpio[2] = {
	GPIO_DT_SPEC_GET(PPG0_NODE, int_gpios),
	GPIO_DT_SPEC_GET(PPG1_NODE, int_gpios),
};

/* D2 / P0.28, active low. The tca954x driver already deasserts this at init;
 * we re-drive it here to read the pad back and to give a mute mux a clean
 * reset pulse. */
static const struct gpio_dt_spec mux_reset =
	GPIO_DT_SPEC_GET_OR(MUX_NODE, reset_gpios, {0});

/*
 * Pulse the mux RESET, then read the pad back.
 *
 * Two things are being tested. First, a mux that came up before its RESET line
 * was driven can sit latched in reset, mute on the bus while everything around
 * it looks healthy — a clean pulse recovers that. Second, driving the line
 * HIGH and reading the pad tells us whether anything external is fighting it.
 *
 * What this CANNOT detect: a RESET pin that is simply not wired to D2 at all.
 * The pad then reads back exactly as it should while the mux's own RESET pin
 * floats, and on a part with no internal pull-up a floating RESET can hold it
 * in reset indefinitely. If this test comes back clean and the mux is still
 * mute, confirming that D2 physically reaches the mux RESET pin is the next
 * thing to check with a meter, not with firmware.
 */
static void pulse_mux_reset(void)
{
	if (mux_reset.port == NULL) {
		printk("  reset line:            not described in devicetree\n");
		return;
	}

	if (!gpio_is_ready_dt(&mux_reset)) {
		printk("  reset line:            GPIO controller not ready\n");
		return;
	}

	/* Ask for output plus input so the pad can be read back. If the driver
	 * refuses the combination, fall back to output only and skip the
	 * readback rather than reporting a level we did not actually measure. */
	bool can_read = (gpio_pin_configure_dt(&mux_reset,
					       GPIO_OUTPUT_ACTIVE | GPIO_INPUT) == 0);

	if (!can_read && gpio_pin_configure_dt(&mux_reset,
					       GPIO_OUTPUT_ACTIVE) != 0) {
		printk("  reset line:            cannot configure D2\n");
		return;
	}

	k_sleep(K_MSEC(2));            /* hold in reset */
	gpio_pin_set_dt(&mux_reset, 0);  /* deassert: logical 0 = pad HIGH */
	k_sleep(K_MSEC(5));            /* let the part come out of reset */

	if (!can_read) {
		printk("  reset line:            pulsed (pad readback "
		       "unavailable)\n");
		return;
	}

	/* gpio_pin_get_dt returns the LOGICAL level: 1 means asserted, which
	 * for this active-low line means the pad is LOW. */
	int logical = gpio_pin_get_dt(&mux_reset);

	if (logical < 0) {
		printk("  reset line:            pulsed, readback failed (%d)\n",
		       logical);
	} else if (logical == 0) {
		printk("  reset line:            pulsed, D2 reads HIGH as "
		       "driven — nothing external is holding it down\n");
	} else {
		printk("  reset line:            PULSED BUT D2 STILL READS "
		       "LOW. Something external is pulling it to GND, so the "
		       "mux is held in reset permanently and can never "
		       "answer. Check what else D2/P0.28 is connected to.\n");
	}
}

/*
 * Probe one address with a one-byte read.
 *
 * A read is used rather than the more usual zero-length write: nRF TWI does
 * not handle a zero-length transfer uniformly, and a write-probe can disturb a
 * device that happens to be listening. A read costs one junk byte from
 * whatever register pointer the device is sitting on, which is harmless for
 * both the PCA9546A (returns its control register) and the MAX30102.
 */
static bool probe(uint8_t addr)
{
	uint8_t b;

	return i2c_read(root, &b, 1, addr) == 0;
}

/*
 * Report the idle voltage level of SDA and SCL.
 *
 * This is the measurement that separates the two remaining causes when nothing
 * at all answers, and it is the one you cannot get from a scan. I2C is
 * open-drain: with working pull-ups an idle bus sits HIGH on both lines, and
 * every device merely pulls them down. So:
 *
 *   both HIGH  the pull-ups work and the bus is idle — wiring to the pins is
 *              fine, and the fault is that no device is powered or connected
 *              beyond them
 *   any LOW    no pull-up, a line shorted to GND, or a device jamming SDA
 *
 * Non-invasive: nRF TWI leaves the pin input buffers connected (it has to read
 * SDA), so the GPIO IN register reflects the true line state without touching
 * the peripheral's configuration. Nothing here needs restoring afterwards.
 */
static void report_bus_levels(void)
{
	uint32_t sda = nrf_gpio_pin_read(SDA_PIN);
	uint32_t scl = nrf_gpio_pin_read(SCL_PIN);

	printk("  line levels:           SDA(P0.04)=%s  SCL(P0.05)=%s\n",
	       sda ? "HIGH" : "LOW", scl ? "HIGH" : "LOW");

	if (sda && scl) {
		printk("  Both lines idle HIGH, so the 4.7k pull-ups to 3V3 "
		       "are present and working. Stop looking at the pull-ups "
		       "and at D4/D5 — the fault is downstream: the mux is "
		       "unpowered (check 3V3 actually reaches its VDD), its "
		       "GND is not shared with the XIAO, or its SDA/SCL are "
		       "not landing on the mux pins.\n");
	} else {
		printk("  A line that will not idle HIGH means the bus can "
		       "never signal. Either the 4.7k pull-ups to 3V3 are "
		       "missing or not actually tied to 3V3, or that line is "
		       "shorted to GND. Measure D4 and D5 against GND with "
		       "nothing transmitting: both should read ~3.3 V.\n");
	}
}

/* Scan the standard 7-bit address range and print what answers. */
static int scan(const char *label)
{
	int found = 0;

	printk("  %-22s", label);

	for (uint8_t addr = 0x08; addr <= 0x77; addr++) {
		if (probe(addr)) {
			printk(" 0x%02X", addr);
			found++;
		}
	}

	if (found == 0) {
		printk(" (nothing)");
	}
	printk("\n");
	return found;
}

/* Write the mux control register directly, bypassing the tca954x driver.
 *
 * Legitimate here only because this runs before acquisition and the test
 * restores 0x00 at the end, which is what the driver cached at its own init —
 * so its idea of the selected channel stays correct. Do not do this at
 * runtime. */
static int mux_select(uint8_t mask)
{
	return i2c_write(root, &mask, 1, MUX_ADDR);
}

/* Raw register access to the sensor on the currently selected mux channel. */
static int ppg_reg_write(uint8_t reg, uint8_t val)
{
	uint8_t buf[2] = { reg, val };

	return i2c_write(root, buf, sizeof(buf), PPG_ADDR);
}

static int ppg_reg_read(uint8_t reg, uint8_t *val)
{
	return i2c_write_read(root, PPG_ADDR, &reg, 1, val, 1);
}

/*
 * Put both sensors back to a state where neither drives its INT line, and
 * confirm both lines actually came back up.
 *
 * A reset alone is not enough. PPG_RDY holds INT low until INT_STATUS1 is
 * read, and this test deliberately never reads it — that is how it detects
 * which line a sensor owns. So the sensor probed in the previous iteration is
 * still holding its line down when the next one starts, and without an
 * explicit clear the second measurement sees two low lines and cannot tell
 * them apart. That is a flaw in the measurement, not a short in the wiring,
 * and reporting it as a short would send you chasing a fault that is not there.
 *
 * Returns true when both lines idle high, i.e. the baseline is trustworthy.
 */
static bool quiesce_sensors(const uint8_t *chans)
{
	for (int i = 0; i < 2; i++) {
		mux_select(BIT(chans[i]));
		ppg_reg_write(0x09, 0x40);   /* MODE_CONFIG: reset */
	}
	k_sleep(K_MSEC(20));

	for (int i = 0; i < 2; i++) {
		uint8_t status;

		mux_select(BIT(chans[i]));
		ppg_reg_write(0x02, 0x00);        /* INT_ENABLE1: all off */
		ppg_reg_read(0x00, &status);      /* INT_STATUS1: clears INT */
	}
	k_sleep(K_MSEC(5));

	bool a_low = (gpio_pin_get_dt(&int_gpio[0]) == 1);
	bool b_low = (gpio_pin_get_dt(&int_gpio[1]) == 1);

	if (a_low || b_low) {
		printk("    baseline NOT clean: %s%s%s still low with both "
		       "sensors silenced — that line is held down by "
		       "something other than a sensor interrupt\n",
		       a_low ? "P0.02" : "",
		       (a_low && b_low) ? " and " : "",
		       b_low ? "P0.03" : "");
		return false;
	}

	return true;
}

/*
 * Work out which INT line belongs to which mux channel, and check it against
 * what the devicetree claims.
 *
 * This matters more than it looks. The INT lines bypass the mux by design, so
 * nothing in the I2C path can tell you which sensor raised one. If the
 * devicetree pairs a channel with the wrong INT pin, acquisition still works
 * perfectly: a thread waits on one sensor's interrupt and then drains the
 * other sensor's FIFO. The data looks entirely plausible and every timestamp
 * is attached to the wrong site — which, on an instrument built to measure
 * timing between sites, is worse than no data at all and impossible to spot
 * afterwards.
 *
 * Method: a MAX30102 comes out of reset with INT_ENABLE1 = 0, so it drives
 * nothing. Reset both, then configure exactly one to stream with PPG_RDY
 * enabled. It pulls its own INT low on the first sample and holds it there
 * until INT_STATUS1 is read, which we deliberately do not do. Whichever line
 * is LOW belongs to that channel.
 */
static void verify_int_mapping(void)
{
	static const uint8_t chans[2] = { PPG0_CHAN, PPG1_CHAN };

	printk("  INT mapping:\n");

	for (int i = 0; i < 2; i++) {
		if (gpio_pin_configure_dt(&int_gpio[i], GPIO_INPUT) != 0) {
			printk("    cannot configure INT pin %u\n",
			       int_gpio[i].pin);
			return;
		}
	}

	for (int target = 0; target < 2; target++) {
		/* Both lines must idle high before this measurement means
		 * anything — otherwise we cannot attribute a low line to the
		 * sensor we just enabled. */
		if (!quiesce_sensors(chans)) {
			printk("    ch%u -> skipped, baseline unusable\n",
			       chans[target]);
			continue;
		}

		/* Bring up only the target. */
		mux_select(BIT(chans[target]));
		ppg_reg_write(0x08, 0x10);                  /* FIFO_CONFIG   */
		ppg_reg_write(0x09, 0x03);                  /* MODE: SpO2    */
		ppg_reg_write(0x0A, MAX30102_SPO2_CFG);     /* SPO2_CONFIG   */
		ppg_reg_write(0x0C, MAX30102_LED_PA);       /* LED1_PA       */
		ppg_reg_write(0x0D, MAX30102_LED_PA);       /* LED2_PA       */
		ppg_reg_write(0x02, 0x40);                  /* INT: PPG_RDY  */

		/* Two sample periods is ample for the first PPG_RDY. */
		k_sleep(K_MSEC(50));

		int low_a = (gpio_pin_get_dt(&int_gpio[0]) == 1);
		int low_b = (gpio_pin_get_dt(&int_gpio[1]) == 1);

		const char *verdict;

		if (low_a && !low_b) {
			verdict = (target == 0) ? "matches devicetree"
						: "WRONG — swap the int-gpios";
			printk("    ch%u -> P0.%02u (%s)\n", chans[target],
			       int_gpio[0].pin, verdict);
		} else if (low_b && !low_a) {
			verdict = (target == 1) ? "matches devicetree"
						: "WRONG — swap the int-gpios";
			printk("    ch%u -> P0.%02u (%s)\n", chans[target],
			       int_gpio[1].pin, verdict);
		} else if (low_a && low_b) {
			printk("    ch%u -> both lines low even from a clean "
			       "baseline. The two INT wires are shorted "
			       "together, or both sensors share one line.\n",
			       chans[target]);
		} else {
			printk("    ch%u -> neither line went low. That sensor "
			       "is not driving its INT: check the INT wire "
			       "reaches D0 or D1.\n", chans[target]);
		}
	}

	mux_select(0x00);
	printk("    (sensors left reset; ppg_init reconfigures them)\n");
}

static void check_part_id(const struct device *chan, const char *name)
{
	if (!device_is_ready(chan)) {
		printk("  %s: channel device not ready\n", name);
		return;
	}

	uint8_t id = 0;
	int ret = i2c_reg_read_byte(chan, PPG_ADDR, PART_ID_REG, &id);

	if (ret != 0) {
		printk("  %s: no answer at 0x%02X (%d)\n", name, PPG_ADDR, ret);
	} else if (id != PART_ID_EXPECTED) {
		printk("  %s: 0x%02X answered but part ID is 0x%02X, "
		       "expected 0x%02X\n", name, PPG_ADDR, id,
		       PART_ID_EXPECTED);
	} else {
		printk("  %s: MAX30102 confirmed (part ID 0x%02X)\n", name, id);
	}
}

void selftest_run(void)
{
	printk("\n=== Bus self-test ===\n");

	if (!device_is_ready(root)) {
		printk("  I2C controller %s NOT READY — nothing else can work\n",
		       root->name);
		return;
	}

	printk("  controller: %s, mux expected at 0x%02X\n",
	       root->name, MUX_ADDR);

	/* Step 1 — the bus with all mux channels closed. Only the mux itself
	 * should answer. Anything else means a device is wired to the upstream
	 * bus that should be behind a channel. */
	mux_select(0x00);
	int n = scan("all channels off:");

	bool mux_present = probe(MUX_ADDR);

	if (!mux_present) {
		printk("\n  VERDICT: the PCA9546A at 0x%02X does not answer.\n",
		       MUX_ADDR);
		if (n == 0) {
			printk("  Nothing at all answers anywhere in "
			       "0x08-0x77, so this is not an address or "
			       "strapping problem — a mis-strapped mux would "
			       "still have shown up somewhere. It is "
			       "electrical.\n");
			report_bus_levels();
			pulse_mux_reset();

			/* A mux latched in reset since power-up would have been
			 * mute for the scan above but is alive now. Cheap to
			 * re-check, and it distinguishes "was stuck" from
			 * "never there". */
			if (probe(MUX_ADDR)) {
				printk("  0x%02X ANSWERS after the reset "
				       "pulse — the mux had come up latched "
				       "in reset. Reset the board and it "
				       "should now enumerate normally.\n",
				       MUX_ADDR);
			} else {
				printk("  still nothing after the reset "
				       "pulse.\n");
			}
		} else {
			printk("  Something else does answer, so the bus and "
			       "its pull-ups are alive — check the mux's own "
			       "power, its A0/A1/A2 straps (all to GND for "
			       "0x70) and its RESET pin on D2/P0.28.\n");
		}
		printk("=== end self-test ===\n\n");
		return;
	}

	printk("  mux at 0x%02X answers.\n", MUX_ADDR);

	/*
	 * Step 2 — walk ALL FOUR channels and report which ones hold a sensor.
	 *
	 * Deliberately not limited to the two the overlay declares. The silk
	 * screen on a breakout is not authoritative: vendors label the four
	 * channels 0-3 or 1-4, and a board labelled 1-4 puts the pads marked
	 * "SD3/SC3" on channel *index* 2. Guessing from the labels is how you
	 * end up wiring a sensor to a channel the firmware never selects, so
	 * this asks the part itself which channels are populated.
	 */
	uint8_t populated = 0;

	for (uint8_t index = 0; index < 4; index++) {
		uint8_t mask = BIT(index);
		char label[32];

		snprintk(label, sizeof(label), "ch%u on (0x%02X):", index, mask);

		mux_select(mask);
		scan(label);

		bool seen = probe(PPG_ADDR);

		mux_select(0x00);

		bool gone = !probe(PPG_ADDR);

		if (seen && gone) {
			populated |= mask;
			printk("  ch%u: OK — 0x%02X appears with the channel "
			       "open and disappears when it closes\n",
			       index, PPG_ADDR);
		} else if (seen && !gone) {
			printk("  ch%u: 0x%02X still answers with all channels "
			       "closed — that device is wired to the upstream "
			       "bus, not behind the mux\n", index, PPG_ADDR);
		}
		/* An empty channel is the normal case for two of the four, so
		 * it is not worth a line each; the summary below covers it. */
	}

	printk("\n  POPULATED CHANNEL INDICES:");
	if (populated == 0) {
		printk(" none");
	} else {
		for (uint8_t index = 0; index < 4; index++) {
			if (populated & BIT(index)) {
				printk(" %u", index);
			}
		}
	}
	printk("\n  The overlay declares %u and %u. If the indices above "
	       "differ, the breakout's pad labels are offset from the real "
	       "channel numbers and the overlay's reg = <> values must follow "
	       "the indices printed here, not the silk screen.\n\n",
	       PPG0_CHAN, PPG1_CHAN);

	/* Step 3 — part ID through the normal driver path, which also proves
	 * the tca954x channel devices themselves work. */
	mux_select(0x00);

	char name[8];

	snprintk(name, sizeof(name), "ch%u", PPG0_CHAN);
	check_part_id(chan_dev[0], name);
	snprintk(name, sizeof(name), "ch%u", PPG1_CHAN);
	check_part_id(chan_dev[1], name);

	/* Step 3b — which INT line belongs to which channel. Only meaningful
	 * once both channels answer; with one sensor missing there is nothing
	 * to disambiguate. */
	if ((populated & BIT(PPG0_CHAN)) && (populated & BIT(PPG1_CHAN))) {
		verify_int_mapping();
	} else {
		printk("  INT mapping: skipped, both channels must answer "
		       "first\n");
	}

	/* Leave the mux closed: it is the part's reset state and matches what
	 * the tca954x driver cached, so the first real transfer re-selects
	 * correctly. */
	mux_select(0x00);

	printk("=== end self-test ===\n\n");
}
