#ifndef IMU_H
#define IMU_H

#include <zephyr/drivers/sensor.h>

struct imu_sample {
	struct sensor_value accel[3];  /* X, Y, Z  [m/s²] */
	struct sensor_value gyro[3];   /* X, Y, Z  [dps]  */
};

int  imu_init(void);
void imu_print_sample(void);
int  imu_fetch_sample(struct imu_sample *out);

#endif /* IMU_H */
