#ifndef VERSION_H
#define VERSION_H

/*
 * Firmware version, in one place.
 *
 * Two consumers have to agree on it: the ID line the supervisor emits (which
 * is how the desktop app knows what it is talking to) and, on the hub, the
 * fw_version field written into every session file's header. They were
 * separate literals before; a recording that disagreed with the device
 * reporting itself would be silently wrong about which build produced it.
 *
 * Keep in step with the git tag on release.
 */
#define PHYSDAQ_FW_MAJOR  1
#define PHYSDAQ_FW_MINOR  2
#define PHYSDAQ_FW_PATCH  0
#define PHYSDAQ_FW_TWEAK  0

#define PHYSDAQ_FW_STRING \
	STRINGIFY(PHYSDAQ_FW_MAJOR) "." \
	STRINGIFY(PHYSDAQ_FW_MINOR) "." \
	STRINGIFY(PHYSDAQ_FW_PATCH)

#endif /* VERSION_H */
