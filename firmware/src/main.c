#include <zephyr/kernel.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/sys/printk.h>
#include "led.h"
#include "imu.h"

static const struct device *uart = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));

int main(void)
{
    if (led_init() < 0) return 0;

    if (!device_is_ready(uart)) {
        printk("UART not ready\n");
        return 0;
    }

    if (imu_init() < 0) return 0;

    printk("\n=== Xiao Sense IMU ===\n");
    printk("Colors: r=red  g=green  b=blue  w=white  0=off\n");
    printk("Streaming accel + gyro @ 20 Hz...\n\n");

    while (1) {
        imu_print_sample();

        unsigned char c;
        if (uart_poll_in(uart, &c) == 0) {
            switch (c) {
            case 'r': led_set(1,0,0); printk(">>> RED\n");   break;
            case 'g': led_set(0,1,0); printk(">>> GREEN\n"); break;
            case 'b': led_set(0,0,1); printk(">>> BLUE\n");  break;
            case 'w': led_set(1,1,1); printk(">>> WHITE\n"); break;
            case '0': led_off();      printk(">>> OFF\n");   break;
            }
        }

        k_sleep(K_MSEC(50));
    }

    return 0;
}
