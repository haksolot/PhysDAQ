#include <zephyr/drivers/gpio.h>
#include <zephyr/sys/printk.h>
#include "led.h"

static const struct gpio_dt_spec leds[] = {
    GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios),  /* red   */
    GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios),  /* green */
    GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios),  /* blue  */
};

int led_init(void)
{
    for (int i = 0; i < 3; i++) {
        if (!gpio_is_ready_dt(&leds[i])) {
            printk("LED %d not ready\n", i);
            return -1;
        }
        gpio_pin_configure_dt(&leds[i], GPIO_OUTPUT_INACTIVE);
    }
    return 0;
}

void led_set(bool r, bool g, bool b)
{
    gpio_pin_set_dt(&leds[0], r);
    gpio_pin_set_dt(&leds[1], g);
    gpio_pin_set_dt(&leds[2], b);
}

void led_off(void)
{
    led_set(false, false, false);
}
