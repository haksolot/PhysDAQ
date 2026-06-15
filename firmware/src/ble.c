#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/services/nus.h>
#include <zephyr/sys/printk.h>
#include "ble.h"

/* Advertise with the NUS service UUID in the AD payload so Python's
 * BleakScanner can filter by UUID without knowing the device address. */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS,
		      (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL,
		      BT_UUID_128_ENCODE(0x6e400001, 0xb5a3, 0xf393,
					 0xe0a9, 0xe50e24dcca9e)),
};

/* Full device name in scan-response so bleak can log a human-readable name */
static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE,
		CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static struct bt_conn *current_conn;

static void on_connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		return;
	}
	current_conn = bt_conn_ref(conn);
	printk("BLE: connected\n");
}

static void on_disconnected(struct bt_conn *conn, uint8_t reason)
{
	if (current_conn) {
		bt_conn_unref(current_conn);
		current_conn = NULL;
	}
	/* Resume advertising so the PC can reconnect */
	bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	printk("BLE: disconnected (reason %u) — advertising\n", reason);
}

BT_CONN_CB_DEFINE(conn_cbs) = {
	.connected    = on_connected,
	.disconnected = on_disconnected,
};

static void nus_received(struct bt_conn *conn,
			 const uint8_t *data, uint16_t len)
{
	/* RX reserved for future PC→device commands */
	ARG_UNUSED(conn);
	ARG_UNUSED(data);
	ARG_UNUSED(len);
}

static const struct bt_nus_cb nus_cbs = {
	.received = nus_received,
};

int ble_init(void)
{
	int err = bt_enable(NULL);
	if (err) {
		printk("BLE: enable failed (%d)\n", err);
		return err;
	}

	err = bt_nus_init(&nus_cbs);
	if (err) {
		printk("BLE: NUS init failed (%d)\n", err);
		return err;
	}

	err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad),
			      sd, ARRAY_SIZE(sd));
	if (err) {
		printk("BLE: advertising start failed (%d)\n", err);
		return err;
	}

	printk("BLE: advertising as \"%s\" (NUS UUID)\n",
	       CONFIG_BT_DEVICE_NAME);
	return 0;
}

void ble_send(const uint8_t *data, size_t len)
{
	if (!current_conn) {
		return;
	}
	bt_nus_send(current_conn, data, len);
}
