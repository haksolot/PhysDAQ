#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/sys/printk.h>

#define LED0_NODE DT_ALIAS(led0)
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED0_NODE, gpios);
const struct device *uart = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));

int main(void)
{
    if (!gpio_is_ready_dt(&led)) {
        printk("LED not ready\n");
        return 0;
    }
    gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);

    if (!device_is_ready(uart)) {
        printk("UART not ready\n");
        return 0;
    }

    printk("\n=== Xiao Sense Interactive ===\n");
    printk("Commands: 1=ON  0=OFF  t=TOGGLE\n\n");

    while (1) {
        unsigned char c;
        if (uart_poll_in(uart, &c) == 0) {
            switch (c) {
            case '1':
                gpio_pin_set_dt(&led, 1);
                printk(">>> LED ON\n");
                break;
            case '0':
                gpio_pin_set_dt(&led, 0);
                printk(">>> LED OFF\n");
                break;
            case 't':
            case 'T':
                gpio_pin_toggle_dt(&led);
                printk(">>> LED TOGGLED\n");
                break;
            }
        }
        k_sleep(K_MSEC(50));
    }

    return 0;
}
