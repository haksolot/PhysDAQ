cmake_minimum_required(VERSION 3.20.0)

# Expose firmware-hub/dts/bindings/ so the maxim,max30102 binding is found.
# Must come before find_package(Zephyr).
list(APPEND DTS_ROOT ${CMAKE_CURRENT_SOURCE_DIR})

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(physdaq_hub)

target_sources(app PRIVATE
    src/main.c
    src/led.c
    src/imu.c
    src/max30102.c
    src/ppg.c
    src/selftest.c
    src/storage.c
    src/power.c
    src/ble.c
    src/battery.c
    src/contact.c
    src/watchdog.c
    src/command.c
    src/satellites.c
)
