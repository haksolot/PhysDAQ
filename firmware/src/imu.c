#include <zephyr/drivers/sensor.h>
#include <zephyr/sys/printk.h>
#include "imu.h"

static const struct device *imu = DEVICE_DT_GET_ONE(st_lsm6dsl);

static void print_val(const char *label, struct sensor_value *val)
{
    int32_t frac = val->val2 < 0 ? -val->val2 : val->val2;
    const char *neg = (val->val1 < 0 || val->val2 < 0) ? "-" : "";
    int32_t whole = val->val1 < 0 ? -val->val1 : val->val1;
    printk("%s:%s%d.%03d ", label, neg, whole, frac / 1000);
}

int imu_init(void)
{
    if (!device_is_ready(imu)) {
        printk("IMU not ready — check wiring & overlay\n");
        return -1;
    }

    struct sensor_value odr = { .val1 = 104, .val2 = 0 };
    if (sensor_attr_set(imu, SENSOR_CHAN_ACCEL_XYZ, SENSOR_ATTR_SAMPLING_FREQUENCY, &odr) < 0 ||
        sensor_attr_set(imu, SENSOR_CHAN_GYRO_XYZ,  SENSOR_ATTR_SAMPLING_FREQUENCY, &odr) < 0) {
        printk("IMU ODR config failed\n");
        return -1;
    }

    return 0;
}

void imu_print_sample(void)
{
    struct sensor_value accel[3], gyro[3], temp;

    if (sensor_sample_fetch(imu) < 0) {
        printk("IMU fetch error\n");
        return;
    }

    sensor_channel_get(imu, SENSOR_CHAN_ACCEL_XYZ, accel);
    sensor_channel_get(imu, SENSOR_CHAN_GYRO_XYZ, gyro);
    sensor_channel_get(imu, SENSOR_CHAN_DIE_TEMP, &temp);

    print_val("AX", &accel[0]); print_val("AY", &accel[1]); print_val("AZ", &accel[2]);
    printk(" | ");
    print_val("GX", &gyro[0]);  print_val("GY", &gyro[1]);  print_val("GZ", &gyro[2]);
    printk(" | ");
    print_val("T", &temp);
    printk("\n");
}
