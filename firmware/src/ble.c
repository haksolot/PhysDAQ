#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/sys/util.h>
#include <zephyr/sys/printk.h>
#include "ble.h"

/* NUS-compatible UUIDs — identical to Nordic UART Service, so any NUS
 * client (bleak, nRF Connect app) works without any extra configuration. */
#define BT_UUID_NUS_SERVICE_VAL \
	BT_UUID_128_ENCODE(0x6e400001, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)
#define BT_UUID_NUS_TX_VAL \
	BT_UUID_128_ENCODE(0x6e400003, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)
#define BT_UUID_NUS_RX_VAL \
	BT_UUID_128_ENCODE(0x6e400002, 0xb5a3, 0xf393, 0xe0a9, 0xe50e24dcca9e)

#define BT_UUID_NUS_SERVICE BT_UUID_DECLARE_128(BT_UUID_NUS_SERVICE_VAL)
#define BT_UUID_NUS_TX      BT_UUID_DECLARE_128(BT_UUID_NUS_TX_VAL)
#define BT_UUID_NUS_RX      BT_UUID_DECLARE_128(BT_UUID_NUS_RX_VAL)

static struct bt_conn              *current_conn;
static bool                         notify_enabled;
static struct bt_gatt_exchange_params mtu_params;

static void tx_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	notify_enabled = (value == BT_GATT_CCC_NOTIFY);
}

static ssize_t on_rx_write(struct bt_conn *conn,
			   const struct bt_gatt_attr *attr,
			   const void *buf, uint16_t len,
			   uint16_t offset, uint8_t flags)
{
	/* PC→device RX reserved for future commands */
	ARG_UNUSED(conn); ARG_UNUSED(attr);
	ARG_UNUSED(buf);  ARG_UNUSED(offset); ARG_UNUSED(flags);
	return len;
}

/* GATT service attribute table.  Indices matter for ble_send():
 * [0] Primary service
 * [1] TX characteristic declaration
 * [2] TX characteristic value  ← bt_gatt_notify() target
 * [3] TX CCC descriptor        (subscribe/unsubscribe)
 * [4] RX characteristic declaration
 * [5] RX characteristic value  */
BT_GATT_SERVICE_DEFINE(nus_svc,
	BT_GATT_PRIMARY_SERVICE(BT_UUID_NUS_SERVICE),

	BT_GATT_CHARACTERISTIC(BT_UUID_NUS_TX,
			       BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE,
			       NULL, NULL, NULL),
	BT_GATT_CCC(tx_ccc_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

	BT_GATT_CHARACTERISTIC(BT_UUID_NUS_RX,
			       BT_GATT_CHRC_WRITE_WITHOUT_RESP,
			       BT_GATT_PERM_WRITE,
			       NULL, on_rx_write, NULL),
);

/* NUS service UUID in AD so Python BleakScanner can filter by UUID */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS,
		      (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, BT_UUID_NUS_SERVICE_VAL),
};

static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE,
		CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static void mtu_exchanged(struct bt_conn *conn, uint8_t err,
			  struct bt_gatt_exchange_params *params)
{
	/* Log negotiated MTU so we can confirm payload capacity in make term */
	printk("BLE: MTU %u bytes (%u payload)\n",
	       bt_gatt_get_mtu(conn), bt_gatt_get_mtu(conn) - 3);
}

static void on_connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		return;
	}
	current_conn = bt_conn_ref(conn);

	/* Request larger ATT MTU so a full data line (~120 B) fits in one notify.
	 * Default ATT MTU = 23 B → 20 B payload, which is far too small.
	 * Requesting from both sides ensures negotiation even if the central
	 * (bleak) doesn't initiate it first. */
	mtu_params.func = mtu_exchanged;
	bt_gatt_exchange_mtu(conn, &mtu_params);

	printk("BLE: connected\n");
}

static void on_disconnected(struct bt_conn *conn, uint8_t reason)
{
	if (current_conn) {
		bt_conn_unref(current_conn);
		current_conn   = NULL;
		notify_enabled = false;
	}
	bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	printk("BLE: disconnected (reason %u) — advertising\n", reason);
}

BT_CONN_CB_DEFINE(conn_cbs) = {
	.connected    = on_connected,
	.disconnected = on_disconnected,
};

int ble_init(void)
{
	int err = bt_enable(NULL);
	if (err) {
		printk("BLE: enable failed (%d)\n", err);
		return err;
	}

	err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad),
			      sd, ARRAY_SIZE(sd));
	if (err) {
		printk("BLE: advertising start failed (%d)\n", err);
		return err;
	}

	printk("BLE: advertising as \"%s\" (NUS UUID)\n", CONFIG_BT_DEVICE_NAME);
	return 0;
}

void ble_send(const uint8_t *data, size_t len)
{
	if (!current_conn || !notify_enabled) {
		return;
	}

	uint16_t payload = bt_gatt_get_mtu(current_conn) - 3;
	/* Safety: clamp in case MTU not yet negotiated or returns unexpected value */
	if (payload < 20 || payload > 244) {
		payload = 20;
	}

	size_t offset = 0;
	while (offset < len) {
		size_t chunk = MIN(payload, len - offset);
		int err = bt_gatt_notify(current_conn, &nus_svc.attrs[2],
					 data + offset, chunk);
		if (err) {
			/* TX queue full (-ENOMEM) or disconnected — drop rest of sample */
			return;
		}
		offset += chunk;
	}
}

void ble_stop(void)
{
	bt_le_adv_stop();
	if (current_conn) {
		bt_conn_disconnect(current_conn,
				   BT_HCI_ERR_REMOTE_USER_TERM_CONN);
		k_sleep(K_MSEC(150));
	}
}
