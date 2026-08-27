#ifndef LED_H
#define LED_H

#include <stdbool.h>

int  led_init(void);
void led_set(bool r, bool g, bool b);
void led_off(void);

#endif
