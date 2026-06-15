#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/sensor.h>
#include "imu.h"
#include "max30102.h"

/* Format a sensor_value (val1 + val2/1e6) as "±integer.milli" using printk.
 * Mirrors the print_val helper in imu.c, kept local to avoid coupling. */
#define SV_SIGN(v)  (((v)->val1 < 0 || (v)->val2 < 0) ? "-" : "")
#define SV_INT(v)   ((v)->val1 < 0 ? -(v)->val1 : (v)->val1)
#define SV_MILLI(v) (((v)->val2 < 0 ? -(v)->val2 : (v)->val2) / 1000)

int main(void)
{
    if (imu_init() < 0) {
        return 0;
    }

    if (max30102_init() < 0) {
        printk("MAX30102 init failed — halting\n");
        return 0;
    }

    printk("\n=== MAID: PPG + IMU acquisition ===\n");
    printk("PPG: SpO2 mode, 100 Hz, 18-bit ADC\n");
    printk("IMU: accel [m/s^2], gyro [dps]\n\n");

    while (1) {
        /* Block until the MAX30102 PPG_RDY interrupt fires.
         * A 500 ms timeout guards against a stalled sensor. */
        if (max30102_wait_ready(K_MSEC(500)) < 0) {
            continue;
        }

        /* Drain all samples that arrived since the last wake-up.
         * Normally one sample per interrupt; FIFO can hold up to 32. */
        struct ppg_sample ppg;
        struct imu_sample imu;

        while (max30102_fetch(&ppg) == 0) {
            if (imu_fetch_sample(&imu) < 0) {
                continue;
            }

            printk("PPG red=%u ir=%u | IMU "
                   "ax=%s%d.%03d ay=%s%d.%03d az=%s%d.%03d "
                   "gx=%s%d.%03d gy=%s%d.%03d gz=%s%d.%03d\n",
                   ppg.red, ppg.ir,
                   SV_SIGN(&imu.accel[0]), SV_INT(&imu.accel[0]), SV_MILLI(&imu.accel[0]),
                   SV_SIGN(&imu.accel[1]), SV_INT(&imu.accel[1]), SV_MILLI(&imu.accel[1]),
                   SV_SIGN(&imu.accel[2]), SV_INT(&imu.accel[2]), SV_MILLI(&imu.accel[2]),
                   SV_SIGN(&imu.gyro[0]),  SV_INT(&imu.gyro[0]),  SV_MILLI(&imu.gyro[0]),
                   SV_SIGN(&imu.gyro[1]),  SV_INT(&imu.gyro[1]),  SV_MILLI(&imu.gyro[1]),
                   SV_SIGN(&imu.gyro[2]),  SV_INT(&imu.gyro[2]),  SV_MILLI(&imu.gyro[2]));
        }
    }

    return 0;
}
